"""The FastAPI application - four endpoints, no persistence beyond the vector store.

Read :mod:`cloud_driver_intelligence` (the package docstring) before changing ``/search``: the one
invariant this whole service is built around is that it never decides what a user may see.
"""

from __future__ import annotations

import base64
import binascii
import logging

from fastapi import Depends, FastAPI, HTTPException, Response, status

from .embeddings import embedding_model, extract_embeddable_text
from .models import HealthResponse, IndexRequest, SearchHit, SearchRequest
from .security import require_shared_secret
from .store import create_store

_LOGGER = logging.getLogger(__name__)

app = FastAPI(
    title="cloud-driver-intelligence",
    description="Semantic search over cloud-driver file content. Internal service - never expose publicly.",
    version="1.0.6",
)

#: The one store this process owns, built at import time so ``/health`` can report on it immediately.
store = create_store()


@app.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness/readiness probe - the only unauthenticated endpoint.

    Deliberately exempt from the shared secret: the Java bridge calls this at startup purely to log
    whether this service is reachable, and it reveals nothing an operator with network access to
    this port could not already infer from the port being open. It reports whether the embedding
    backend and persistent store actually loaded, so a half-configured deployment (service up,
    optional extras missing) is visible directly rather than inferred from silently empty searches.
    """
    return HealthResponse(
        status="ok",
        embeddingsAvailable=embedding_model.available,
        persistentStore=store.persistent,
        indexedDocuments=store.count(),
    )


@app.post("/index", status_code=status.HTTP_204_NO_CONTENT, dependencies=[Depends(require_shared_secret)])
async def index(request: IndexRequest) -> Response:
    """Embed one file and store its vector, replacing any vector previously held for the same id.

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

    store.upsert(request.fileId, request.ownerUserId, vectors[0])
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.delete("/index/{file_id}", status_code=status.HTTP_204_NO_CONTENT, dependencies=[Depends(require_shared_secret)])
async def delete_index(file_id: str) -> Response:
    """Remove one file's vector.

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
    """
    if not request.candidateFileIds or not request.queryText.strip():
        return []

    query_vectors = embedding_model.encode([request.queryText])
    if query_vectors is None:
        return []
    query_vector = query_vectors[0]

    candidates = store.vectors_for(request.candidateFileIds)
    if not candidates:
        return []

    # Both sides are L2-normalised (see EmbeddingModel.encode), so a dot product *is* the cosine
    # similarity - no division, no per-vector normalisation needed here.
    scored = [
        SearchHit(fileId=file_id, score=sum(a * b for a, b in zip(query_vector, vector)))
        for file_id, vector in candidates.items()
    ]
    scored.sort(key=lambda hit: hit.score, reverse=True)
    return scored[: request.limit]


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
