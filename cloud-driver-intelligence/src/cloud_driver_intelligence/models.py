"""Request/response shapes, hand-mirrored against the Java bridge's own records.

Kept in sync by hand rather than generated, the same convention every ``cloud-driver`` client
library already follows (see ``cloud-driver-multiplatform-java``'s ``Dtos``). The counterpart types
live in ``IntelligenceHttpClient``.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class IndexRequest(BaseModel):
    """``POST /index`` body."""

    fileId: str
    #: The owning account **as a hint only**. It is recorded so a maintenance job can reason about
    #: the store, and is deliberately never used to filter a search: this service is not permitted
    #: to make an access decision, because its copy of ownership is stale the moment a share is
    #: granted or revoked on the Java side. See the Java ``IntelligenceService`` Javadoc.
    ownerUserId: str
    fileName: str
    contentType: str
    #: Raw file bytes, base64-encoded, or ``None`` when the server never held them (a presigned
    #: direct-to-S3 upload) - in which case only ``fileName`` is embeddable.
    contentBase64: str | None = None


class UpdateOwnerRequest(BaseModel):
    """``PATCH /index/{file_id}/owner`` body - metadata only, no re-embedding."""

    ownerUserId: str


class SearchRequest(BaseModel):
    """``POST /search`` body."""

    queryText: str
    #: The **only** ids that may be ranked. Supplied by the Java side from authoritative
    #: ownership/sharing data, per request. Treated as a hard restriction, never a ranking hint.
    candidateFileIds: list[str]
    limit: int = Field(default=25, ge=1, le=500)


class SearchHit(BaseModel):
    """One entry of ``POST /search``'s response array."""

    fileId: str
    #: Cosine similarity, higher is more similar.
    score: float


class DuplicatesRequest(BaseModel):
    """``POST /duplicates`` body."""

    #: The **only** ids that may be grouped - the same hard restriction ``SearchRequest`` carries,
    #: and for a stronger reason: a grouping asserts a relationship *between* two files, so an id
    #: leaking into a group would reveal more than a stray search hit would.
    candidateFileIds: list[str]
    #: Cosine-similarity floor every pair in a group must meet. ``None`` uses the service default.
    minimumSimilarity: float | None = Field(default=None, ge=0.0, le=1.0)
    limit: int = Field(default=50, ge=1, le=500)


class DuplicateGroupResponse(BaseModel):
    """One entry of ``POST /duplicates``' response array."""

    storedFileIds: list[str]
    #: The group's **weakest** pairwise similarity, so a caller comparing against its own threshold
    #: judges the whole group rather than its best pair.
    similarity: float


class TagsRequest(BaseModel):
    """``POST /tags`` body."""

    fileId: str
    limit: int = Field(default=5, ge=1, le=50)


class TagSuggestionResponse(BaseModel):
    """One entry of ``POST /tags``' response array."""

    tag: str
    #: A *relative* similarity, not a calibrated probability - see the Java ``TagSuggestion``
    #: record's own Javadoc before rendering this to an end user.
    confidence: float


class HealthResponse(BaseModel):
    """``GET /health`` body - the shape the Java bridge's own health probe reads."""

    status: str
    #: Whether a real embedding backend loaded. ``False`` means the service is up but can only
    #: answer "no results"; useful to see directly rather than infer from empty searches.
    embeddingsAvailable: bool
    #: Whether the store survives a restart, as opposed to the in-memory fallback.
    persistentStore: bool
    #: Whether stored vectors are encrypted at rest. ``False`` on a store holding derived plaintext
    #: in the clear - see ``crypto.py`` for why that is worth reporting rather than assuming.
    encryptedStore: bool
    #: Whether a multimodal (CLIP) model is loaded, so image *content* is searchable by text.
    imageEmbeddingsAvailable: bool
    indexedDocuments: int
    #: Text vectors written under a *different* embedding model than the one configured now.
    #: Non-zero after a model change: those files behave as "not indexed" until the operator
    #: re-embeds them (``intelligence backfill --content`` on the Java terminal) - without this
    #: number, a model change silently degrades search with nothing anywhere saying why.
    staleTextVectors: int
    #: Image vectors written under a different CLIP model than the one configured now - same
    #: remediation as ``staleTextVectors``; ``0`` when no CLIP model is loaded at all.
    staleImageVectors: int
