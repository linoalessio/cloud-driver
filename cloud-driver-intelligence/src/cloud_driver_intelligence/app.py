"""The FastAPI application.

Read :mod:`cloud_driver_intelligence` (the package docstring) before changing ``/search`` or
``/duplicates``: the one invariant this whole service is built around is that it never decides
what a user may see.
"""

from __future__ import annotations

import base64
import binascii
import logging

from fastapi import Depends, FastAPI, HTTPException, Response, status

from .config import settings
from .embeddings import (
    clip_model,
    embedding_model,
    encode_image_vector,
    extract_embeddable_text,
    tag_vocabulary,
)
from .models import (
    DuplicateGroupResponse,
    DuplicatesRequest,
    HealthResponse,
    IndexRequest,
    SearchHit,
    SearchRequest,
    TagSuggestionResponse,
    TagsRequest,
    UpdateOwnerRequest,
)
from .security import require_shared_secret
from .store import KIND_IMAGE, KIND_TEXT, create_store

_LOGGER = logging.getLogger(__name__)

app = FastAPI(
    title="cloud-driver-intelligence",
    description="Semantic search over cloud-driver file content. Internal service - never expose publicly.",
    version="1.0.6",
)

#: The one store this process owns, built at import time so ``/health`` can report on it immediately.
store = create_store()


def _cosine(left: list[float], right: list[float]) -> float:
    """Dot product, which *is* cosine similarity here - every vector is L2-normalised on encode."""
    return sum(a * b for a, b in zip(left, right))


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness/readiness probe - the only unauthenticated endpoint.

    Deliberately exempt from the shared secret: the Java bridge calls this to log whether this
    service is reachable, and it reveals nothing an operator with network access to this port
    could not already infer from the port being open.

    Reports each optional capability separately, because every one of them fails *silently* when
    absent - a missing embedding backend, an unencrypted store and a disabled image model all look
    identical from the outside to "nothing matched". This endpoint is the only place that
    distinguishes them.
    """
    return HealthResponse(
        status="ok",
        embeddingsAvailable=embedding_model.available,
        persistentStore=store.persistent,
        encryptedStore=store.encrypted,
        imageEmbeddingsAvailable=clip_model is not None and clip_model.available,
        indexedDocuments=store.count(),
        staleTextVectors=store.count_stale(embedding_model.model_id, KIND_TEXT),
        staleImageVectors=store.count_stale(clip_model.model_id, KIND_IMAGE) if clip_model is not None else 0,
    )


@app.post("/index", status_code=status.HTTP_204_NO_CONTENT, dependencies=[Depends(require_shared_secret)])
async def index(request: IndexRequest) -> Response:
    """Embed one file and store its vector(s), replacing anything previously held for the same id.

    Two vectors may result: a text vector (always, built from the file name plus whatever the
    extractors in :mod:`.embeddings` can pull out) and - for an image, when CLIP is enabled - an
    image vector in a *separate* space. They are stored under separate modalities and never
    compared with each other.

    Answers ``204`` even when no embedding backend is available: the Java bridge would otherwise
    retry three times and log a ``SEVERE`` give-up for every single upload on a deployment that
    simply has not installed the ``embeddings`` extra yet - noise describing a configuration state
    that ``/health`` already reports plainly and that no retry can fix.
    """
    content = _decode_content(request.contentBase64)
    text = extract_embeddable_text(request.fileName, request.contentType, content)

    vectors = embedding_model.encode([text])
    if vectors is None:
        _LOGGER.debug("No embedding backend available - not indexing %s", request.fileId)
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    store.upsert(request.fileId, request.ownerUserId, vectors[0], embedding_model.model_id, KIND_TEXT)

    image_vector = encode_image_vector(request.contentType, content)
    if image_vector is not None and clip_model is not None:
        # clip_model is non-None whenever encode_image_vector produced a vector in production
        # (both names are the same module-level object) - the extra check keeps this honest
        # under test doubles that stub encode_image_vector directly.
        store.upsert(request.fileId, request.ownerUserId, image_vector, clip_model.model_id, KIND_IMAGE)

    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.patch(
    "/index/{file_id}/owner",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[Depends(require_shared_secret)],
)
async def update_owner(file_id: str, request: UpdateOwnerRequest) -> Response:
    """Refresh a stored entry's recorded owner without re-embedding anything.

    Exists so the Java side can keep this store's ownership hint current across sharing changes at
    negligible cost. Note what it is *not*: the recorded owner still never gates a search, which
    loads only the ids a caller explicitly offers. Where it does matter is ``/duplicates``, whose
    results a human reads as "these files are the same" - a stale owner there produces a wrong
    grouping rather than a leaked one, but wrong is reason enough.

    Idempotent on absence, matching ``DELETE /index/{file_id}``.
    """
    store.update_owner(file_id, request.ownerUserId)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete("/index/{file_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=[Depends(require_shared_secret)])
async def delete_index(file_id: str) -> Response:
    """Remove every vector held for one file, across all modalities.

    Idempotent on absence - deleting a file that was never indexed is a ``204``, not a ``404``,
    matching ``ObjectStorageService#deleteObject``'s own contract on the Java side, since the caller
    is a best-effort cleanup path that may legitimately re-delete an already-gone entry.
    """
    store.delete(file_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.post("/search", response_model=list[SearchHit], dependencies=[Depends(require_shared_secret)])
async def search(request: SearchRequest) -> list[SearchHit]:
    """Rank ``candidateFileIds`` by similarity to ``queryText``.

    **This endpoint must never return an id that was not in ``candidateFileIds``.** That is not
    enforced by a filter applied after the fact - it is structural: the only vectors ever loaded are
    the ones fetched *by* those ids (:meth:`~.store.VectorStore.vectors_for`), so there is nothing
    else in scope to rank or return. Any future change that introduces a broader nearest-neighbour
    query and narrows it afterwards would turn a structural guarantee into a filter that can be got
    wrong, and must not be made lightly.

    The Java caller re-checks every returned id regardless (see its ``IntelligenceService``
    Javadoc). Both halves are required; neither is a reason to relax the other.

    When CLIP is enabled a file may be scored twice - once against its text vector and once
    against its image vector, each within its own vector space - and keeps the higher score. A
    photo therefore competes on what it *depicts* as well as on what it is called, without image
    and text similarities ever being compared across spaces.
    """
    if not request.candidateFileIds or not request.queryText.strip():
        return []

    query_vectors = embedding_model.encode([request.queryText])
    if query_vectors is None:
        return []

    best: dict[str, float] = {}
    for file_id, vector in store.vectors_for(request.candidateFileIds, embedding_model.model_id, KIND_TEXT).items():
        best[file_id] = _cosine(query_vectors[0], vector)

    if clip_model is not None:
        clip_query = clip_model.encode([request.queryText])
        if clip_query is not None:
            for file_id, vector in store.vectors_for(request.candidateFileIds, clip_model.model_id, KIND_IMAGE).items():
                score = _cosine(clip_query[0], vector)
                if score > best.get(file_id, float("-inf")):
                    best[file_id] = score

    scored = [SearchHit(fileId=file_id, score=score) for file_id, score in best.items()]
    scored.sort(key=lambda hit: hit.score, reverse=True)
    return scored[: request.limit]


@app.post(
    "/duplicates",
    response_model=list[DuplicateGroupResponse],
    dependencies=[Depends(require_shared_secret)],
)
async def duplicates(request: DuplicatesRequest) -> list[DuplicateGroupResponse]:
    """Group ``candidateFileIds`` into sets whose vectors are near-identical.

    Bound by exactly the same structural restriction as :func:`search`, for a stronger reason: a
    group asserts a relationship *between* two files, so an id that leaked in would say more than
    a stray search hit would. Only the offered ids are ever loaded.

    **Near-duplicate, not duplicate.** This is a similarity judgement over meaning, not a hash
    comparison - the Java side already deduplicates byte-identical content exactly and losslessly.
    What this catches is what that cannot: the same invoice scanned twice, a document re-exported
    at a different quality, a photo saved in another format.

    Grouping is single-link agglomerative over the pairwise matrix, which is O(n²) in the
    candidate count - acceptable because a candidate set is one account's files and this is an
    explicitly-invoked action, not something on the upload path. A group's reported similarity is
    its *weakest* internal pair, so a caller's own threshold judges the whole group.
    """
    ids = list(dict.fromkeys(request.candidateFileIds))  # de-duplicate, preserve order
    if len(ids) < 2:
        return []

    threshold = request.minimumSimilarity if request.minimumSimilarity is not None else settings.duplicate_threshold
    vectors = store.vectors_for(ids, embedding_model.model_id, KIND_TEXT)
    known = [file_id for file_id in ids if file_id in vectors]
    if len(known) < 2:
        return []

    # Union-find over every pair above the threshold. Single-link is the right join rule here:
    # transitively-similar files (A~B, B~C) belong in one group for a human to review, even if A
    # and C are not directly above the threshold themselves.
    parent = {file_id: file_id for file_id in known}

    def find(node: str) -> str:
        while parent[node] != node:
            parent[node] = parent[parent[node]]
            node = parent[node]
        return node

    weakest: dict[tuple[str, str], float] = {}
    for i, left in enumerate(known):
        for right in known[i + 1 :]:
            score = _cosine(vectors[left], vectors[right])
            if score >= threshold:
                weakest[(left, right)] = score
                left_root, right_root = find(left), find(right)
                if left_root != right_root:
                    parent[left_root] = right_root

    groups: dict[str, list[str]] = {}
    for file_id in known:
        groups.setdefault(find(file_id), []).append(file_id)

    results: list[DuplicateGroupResponse] = []
    for members in groups.values():
        if len(members) < 2:
            continue
        member_set = set(members)
        internal = [
            score for (left, right), score in weakest.items() if left in member_set and right in member_set
        ]
        results.append(
            DuplicateGroupResponse(storedFileIds=members, similarity=min(internal) if internal else threshold)
        )

    results.sort(key=lambda group: group.similarity, reverse=True)
    return results[: request.limit]


@app.post(
    "/tags",
    response_model=list[TagSuggestionResponse],
    dependencies=[Depends(require_shared_secret)],
)
async def tags(request: TagsRequest) -> list[TagSuggestionResponse]:
    """Suggest descriptive labels for one already-indexed file.

    Zero-shot against a fixed vocabulary (see :class:`~.embeddings.TagVocabulary`): the file's
    stored vector is scored against each label's vector. No training, no per-deployment model, and
    no learning from user behaviour - which is exactly why the returned confidence is a *relative*
    similarity and not a calibrated probability.

    Takes an id rather than content, so a caller never re-uploads bytes this service already
    embedded. A file that was never indexed has no vector to compare and yields an empty list -
    deliberately not a ``404``, since "no suggestions" is a perfectly good answer to render and the
    caller has already established the file exists.
    """
    vectors = store.vectors_for([request.fileId], embedding_model.model_id, KIND_TEXT)
    file_vector = vectors.get(request.fileId)
    if file_vector is None:
        return []
    return [
        TagSuggestionResponse(tag=tag, confidence=confidence)
        for tag, confidence in tag_vocabulary.suggest(file_vector, request.limit)
    ]


def _decode_content(content_base64: str | None) -> bytes | None:
    """Decode the request's base64 content, rejecting a malformed body with a ``400``."""
    if content_base64 is None:
        return None
    try:
        return base64.b64decode(content_base64, validate=True)
    except (binascii.Error, ValueError) as malformed:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="contentBase64 is not valid base64"
        ) from malformed
