"""Turning a file into a vector.

This module owns every decision the Java side deliberately refuses to make: what part of a file is
embeddable at all, how to decode it, and what to fall back to when nothing useful can be extracted.
Keeping it here is what lets the Java bridge stay a dumb pipe (see ``IntelligenceDocument``'s own
Javadoc) - swapping in OCR, PDF text extraction or a multimodal CLIP model is a change to this file
alone, with no Java rebuild and no wire-format change.
"""

from __future__ import annotations

import logging
from typing import Sequence

from .config import settings

_LOGGER = logging.getLogger(__name__)

#: Content types whose bytes are decoded as text and embedded alongside the file name. Mirrors the
#: Java side's own ``CloudUserService.NON_TEXT_PREFIX_INDEXABLE_CONTENT_TYPES`` plus ``text/*`` -
#: kept identical on purpose, so keyword search and semantic search agree on what "has text".
_TEXTUAL_CONTENT_TYPES = frozenset(
    {"application/json", "application/xml", "application/yaml", "application/toml"}
)


def extract_embeddable_text(file_name: str, content_type: str, content: bytes | None) -> str:
    """Build the string actually handed to the embedding model.

    The file name is *always* included, even when full text is available: a name is frequently the
    most human-meaningful signal a file has ("Rechnung Werkstatt 2026.pdf"), and for the many
    formats nothing can currently be extracted from - PDF, images, archives, office documents - it
    is the only signal at all. That name-only fallback is what makes this service useful on day one
    without OCR or per-format extractors, and is why an unextractable file is still indexed rather
    than skipped.

    :param file_name: the file's display name
    :param content_type: its MIME type, deciding whether ``content`` is decoded at all
    :param content: raw bytes, or ``None`` if the server never held them
    :return: text to embed - never empty, since ``file_name`` is always present
    """
    parts = [file_name]

    is_textual = content_type.startswith("text/") or content_type in _TEXTUAL_CONTENT_TYPES
    if content and is_textual:
        # errors="replace" rather than strict: a file declared text/* whose bytes are not valid
        # UTF-8 should still contribute whatever decodes, not fail the whole index call.
        text = content.decode("utf-8", errors="replace")[: settings.max_embedded_chars]
        if text.strip():
            parts.append(text)

    return "\n".join(parts)


class EmbeddingModel:
    """Lazily-loaded ``sentence-transformers`` wrapper.

    Loaded on first use rather than at import, so the process starts (and answers ``/health``)
    immediately instead of blocking for however long the model takes to download and initialise.

    :ivar available: ``False`` if ``sentence-transformers`` is not installed or the model failed to
        load. The service stays up in that state and simply returns no results - the same fail-open
        posture the Java bridge already takes, rather than a hard dependency that turns a missing
        optional extra into an outage.
    """

    def __init__(self) -> None:
        self._model = None
        self._load_attempted = False
        self._load_failed = False

    @property
    def available(self) -> bool:
        """Whether embedding can currently be performed (loading it if not yet attempted)."""
        self._ensure_loaded()
        return self._model is not None

    def _ensure_loaded(self) -> None:
        if self._load_attempted:
            return
        self._load_attempted = True
        try:
            from sentence_transformers import SentenceTransformer  # noqa: PLC0415 - deliberately lazy
        except ImportError:
            _LOGGER.warning(
                "sentence-transformers is not installed - semantic search will return no results. "
                "Install this service with the 'embeddings' extra to enable it."
            )
            self._load_failed = True
            return
        try:
            self._model = SentenceTransformer(settings.embedding_model)
            _LOGGER.info("Loaded embedding model %s", settings.embedding_model)
        except Exception:  # noqa: BLE001 - any load failure must degrade, never crash the service
            _LOGGER.exception("Failed to load embedding model %s", settings.embedding_model)
            self._load_failed = True

    def encode(self, texts: Sequence[str]) -> list[list[float]] | None:
        """Embed ``texts``, or return ``None`` if no model is available.

        Vectors are L2-normalised, which makes a plain dot product equal to cosine similarity -
        letting :mod:`.store` rank with a single matrix multiply and no per-vector normalisation.
        """
        self._ensure_loaded()
        if self._model is None:
            return None
        vectors = self._model.encode(list(texts), normalize_embeddings=True)
        return [list(map(float, vector)) for vector in vectors]


#: Process-wide singleton - loading the model more than once would waste both time and memory.
embedding_model = EmbeddingModel()
