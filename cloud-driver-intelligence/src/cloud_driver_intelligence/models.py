"""Request/response shapes, hand-mirrored against the Java bridge's own records.

Kept in sync by hand rather than generated, the same convention every ``cloud-driver`` client
library already follows (see ``cloud-driver-multiplatform-java``'s ``Dtos``). The counterpart types
live in ``IntelligenceHttpClient`` - ``IndexRequest``, ``SearchRequest`` and ``SearchHit``.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class IndexRequest(BaseModel):
    """``POST /index`` body."""

    fileId: str
    #: The owning account **as a hint only**. It is recorded so a future maintenance job can reason
    #: about the store, and is deliberately never used to filter a search: this service is not
    #: permitted to make an access decision, because its copy of ownership is stale the moment a
    #: share is granted or revoked on the Java side. See the Java ``IntelligenceService`` Javadoc.
    ownerUserId: str
    fileName: str
    contentType: str
    #: Raw file bytes, base64-encoded, or ``None`` when the server never held them (a presigned
    #: direct-to-S3 upload) - in which case only ``fileName`` is embeddable.
    contentBase64: str | None = None


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


class HealthResponse(BaseModel):
    """``GET /health`` body - the shape the Java bridge's own health probe reads."""

    status: str
    #: Whether a real embedding backend loaded. ``False`` means the service is up but can only
    #: answer "no results"; useful to see directly rather than infer from empty searches.
    embeddingsAvailable: bool
    #: Whether the persistent (Chroma) store loaded, as opposed to the in-memory fallback.
    persistentStore: bool
    indexedDocuments: int
