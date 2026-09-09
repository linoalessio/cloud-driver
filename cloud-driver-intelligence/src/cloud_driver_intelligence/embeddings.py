"""Turning a file into one or more vectors.

This module owns every decision the Java side deliberately refuses to make: what part of a file is
embeddable at all, how to decode it, and what to fall back to when nothing useful can be
extracted. Keeping it here is what lets the Java bridge stay a dumb pipe (see
``IntelligenceDocument``'s own Javadoc) - adding OCR, PDF text extraction or a multimodal CLIP
model is a change to this file alone, with no Java rebuild and no wire-format change. That claim
was made when this service shipped; this module is where it was cashed in.

**Every extractor here is optional and degrades to the file name alone.** A missing dependency, an
encrypted PDF, an image OCR cannot read - all of them produce a name-only embedding rather than a
failure, because a file that is indexed weakly is still findable while a file that failed to index
is invisible.
"""

from __future__ import annotations

import io
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

#: Image types OCR and CLIP will attempt. Deliberately an explicit allow-list rather than an
#: ``image/`` prefix match: the client apps already take exactly this approach for their own image
#: previews, and an exotic format that Pillow cannot open should fall back to a name-only
#: embedding rather than raise on every upload.
_IMAGE_CONTENT_TYPES = frozenset({"image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/tiff"})

#: Beyond this, PDF text extraction stops. A long document's opening pages carry its identity
#: ("Rechnung", a letterhead, a subject line), and the embedding model truncates to its own short
#: context window regardless - so parsing hundreds of pages spends real time on text that provably
#: cannot influence the resulting vector.
_MAX_PDF_PAGES = 20


def is_image(content_type: str) -> bool:
    """Whether ``content_type`` is one this module will attempt to open as an image."""
    return content_type in _IMAGE_CONTENT_TYPES


def extract_embeddable_text(file_name: str, content_type: str, content: bytes | None) -> str:
    """Build the string actually handed to the text embedding model.

    The file name is *always* included, even when full text is available: a name is frequently the
    most human-meaningful signal a file has ("Rechnung Werkstatt 2026.pdf"), and for formats
    nothing can be extracted from it is the only signal at all. That name-only fallback is what
    makes this service useful without any optional extractor installed, and is why an
    unextractable file is still indexed rather than skipped.

    :param file_name: the file's display name
    :param content_type: its MIME type, deciding which extractor (if any) runs
    :param content: raw bytes, or ``None`` if the server never held them
    :return: text to embed - never empty, since ``file_name`` is always present
    """
    parts = [file_name]
    extracted = _extract_body_text(content_type, content) if content else None
    if extracted and extracted.strip():
        parts.append(extracted[: settings.max_embedded_chars])
    return "\n".join(parts)


def _extract_body_text(content_type: str, content: bytes) -> str | None:
    """Dispatch to whichever extractor suits ``content_type``, or ``None`` if none does."""
    if content_type.startswith("text/") or content_type in _TEXTUAL_CONTENT_TYPES:
        # errors="replace" rather than strict: a file declared text/* whose bytes are not valid
        # UTF-8 should still contribute whatever decodes, not fail the whole index call.
        return content.decode("utf-8", errors="replace")[: settings.max_embedded_chars]

    if content_type == "application/pdf" and settings.pdf_extraction_enabled:
        text = _extract_pdf_text(content)
        # A PDF that is a scan carries no text layer at all - exactly the case OCR exists for, and
        # the single most common "why can't I find my invoice" complaint this feature addresses.
        if (not text or not text.strip()) and settings.ocr_enabled:
            return _ocr_pdf(content)
        return text

    if is_image(content_type) and settings.ocr_enabled:
        return _ocr_image(content)

    return None


def _extract_pdf_text(content: bytes) -> str | None:
    """Extract a PDF's embedded text layer via ``pypdf``, or ``None`` if that is not possible."""
    try:
        from pypdf import PdfReader  # noqa: PLC0415 - deliberately lazy, this is an optional extra
    except ImportError:
        _LOGGER.debug("pypdf is not installed - PDFs are embedded by file name only")
        return None
    try:
        reader = PdfReader(io.BytesIO(content))
        if reader.is_encrypted:
            # Some PDFs open with an empty user password; if that does not work, there is nothing
            # to extract and the name-only fallback applies.
            try:
                reader.decrypt("")
            except Exception:  # noqa: BLE001
                return None
        pages = reader.pages[:_MAX_PDF_PAGES]
        return "\n".join(page.extract_text() or "" for page in pages)
    except Exception:  # noqa: BLE001 - a malformed PDF must never fail an upload's indexing
        _LOGGER.warning("PDF text extraction failed - falling back to the file name", exc_info=True)
        return None


def _ocr_image(content: bytes) -> str | None:
    """Run OCR over an image via ``pytesseract``, or ``None`` if that is not possible."""
    try:
        import pytesseract  # noqa: PLC0415 - optional extra
        from PIL import Image  # noqa: PLC0415 - optional extra
    except ImportError:
        _LOGGER.debug("pytesseract/Pillow are not installed - images are embedded by file name only")
        return None
    try:
        with Image.open(io.BytesIO(content)) as image:
            return pytesseract.image_to_string(image, lang=settings.ocr_languages)
    except Exception:  # noqa: BLE001 - a missing tesseract binary lands here too, by design
        _LOGGER.warning(
            "OCR failed - falling back to the file name. Note pytesseract needs a system "
            "'tesseract' binary (plus a data pack per configured language) that pip cannot install.",
            exc_info=True,
        )
        return None


def _ocr_pdf(content: bytes) -> str | None:
    """OCR a text-layer-less PDF by rasterising it first, or ``None`` if that is not possible.

    Needs ``pdf2image`` *and* a system ``poppler`` install on top of tesseract's own. That is a
    deep enough dependency chain that its absence is logged at debug and shrugged off - the caller
    already has a working name-only fallback.
    """
    try:
        import pytesseract  # noqa: PLC0415 - optional extra
        from pdf2image import convert_from_bytes  # noqa: PLC0415 - optional extra
    except ImportError:
        _LOGGER.debug("pdf2image/pytesseract are not installed - scanned PDFs stay name-only")
        return None
    try:
        images = convert_from_bytes(content, last_page=_MAX_PDF_PAGES)
        return "\n".join(pytesseract.image_to_string(image, lang=settings.ocr_languages) for image in images)
    except Exception:  # noqa: BLE001
        _LOGGER.warning("PDF OCR failed - falling back to the file name", exc_info=True)
        return None


class EmbeddingModel:
    """Lazily-loaded ``sentence-transformers`` wrapper.

    Loaded on first use rather than at import, so the process starts (and answers ``/health``)
    immediately instead of blocking for however long the model takes to download and initialise.

    :ivar available: ``False`` if ``sentence-transformers`` is not installed or the model failed to
        load. The service stays up in that state and simply returns no results - the same fail-open
        posture the Java bridge already takes, rather than a hard dependency that turns a missing
        optional extra into an outage.
    """

    def __init__(self, model_id: str) -> None:
        self._model_id = model_id
        self._model = None
        self._load_attempted = False

    @property
    def available(self) -> bool:
        """Whether embedding can currently be performed (loading the model if not yet attempted)."""
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
            return
        try:
            self._model = SentenceTransformer(self._model_id)
            _LOGGER.info("Loaded embedding model %s", self._model_id)
        except Exception:  # noqa: BLE001 - any load failure must degrade, never crash the service
            _LOGGER.exception("Failed to load embedding model %s", self._model_id)

    def encode(self, texts: Sequence[str]) -> list[list[float]] | None:
        """Embed ``texts``, or return ``None`` if no model is available.

        Vectors are L2-normalised, which makes a plain dot product equal to cosine similarity -
        letting every ranking path here use a single multiply-and-sum with no per-vector
        normalisation.
        """
        self._ensure_loaded()
        if self._model is None:
            return None
        vectors = self._model.encode(list(texts), normalize_embeddings=True)
        return [list(map(float, vector)) for vector in vectors]

    def encode_images(self, images: Sequence[object]) -> list[list[float]] | None:
        """Embed PIL images, or ``None`` if no model is available.

        Only meaningful for a multimodal (CLIP) model, where images and text land in *one* shared
        vector space - that shared space is the whole mechanism by which a typed query can match a
        photo's content.
        """
        self._ensure_loaded()
        if self._model is None:
            return None
        try:
            vectors = self._model.encode(list(images), normalize_embeddings=True)
            return [list(map(float, vector)) for vector in vectors]
        except Exception:  # noqa: BLE001 - a non-multimodal model lands here; degrade, never crash
            _LOGGER.exception("Image embedding failed for model %s", self._model_id)
            return None


#: Process-wide singletons - loading a model more than once would waste both time and memory.
embedding_model = EmbeddingModel(settings.embedding_model)

#: The multimodal model, constructed only when CLIP is enabled. ``None`` otherwise, so a
#: deployment that has not opted in never pays its (roughly 600 MB) memory cost.
clip_model = EmbeddingModel(settings.clip_model) if settings.clip_enabled else None


def encode_image_vector(content_type: str, content: bytes | None) -> list[float] | None:
    """The CLIP vector for an image file, or ``None`` when one cannot (or should not) be produced."""
    if clip_model is None or content is None or not is_image(content_type):
        return None
    try:
        from PIL import Image  # noqa: PLC0415 - optional extra, ships with the clip extra
    except ImportError:
        _LOGGER.debug("Pillow is not installed - images are not CLIP-embedded")
        return None
    try:
        with Image.open(io.BytesIO(content)) as image:
            vectors = clip_model.encode_images([image.convert("RGB")])
            return vectors[0] if vectors else None
    except Exception:  # noqa: BLE001 - an unopenable image must never fail an upload's indexing
        _LOGGER.warning("CLIP image embedding failed - the file stays text-only indexed", exc_info=True)
        return None


class TagVocabulary:
    """The fixed zero-shot label set behind ``POST /tags``, embedded once and cached.

    Zero-shot because the alternative - a trained classifier - would need labelled data this
    project does not have, per-deployment retraining, and a model artefact to ship and version.
    Scoring a file's existing vector against a handful of label vectors reuses the model already
    loaded and costs one embedding pass, once, for the whole vocabulary.
    """

    def __init__(self) -> None:
        self._vectors: list[list[float]] | None = None
        self._labels: tuple[str, ...] = settings.tag_vocabulary
        self._load_attempted = False

    def _ensure_loaded(self) -> None:
        if self._load_attempted:
            return
        self._load_attempted = True
        if not self._labels:
            return
        self._vectors = embedding_model.encode(list(self._labels))

    def suggest(self, file_vector: list[float], limit: int) -> list[tuple[str, float]]:
        """Score ``file_vector`` against every label, best first.

        Both sides are L2-normalised, so a dot product *is* the cosine similarity.
        """
        self._ensure_loaded()
        if self._vectors is None:
            return []
        scored = [
            (label, sum(a * b for a, b in zip(file_vector, label_vector)))
            for label, label_vector in zip(self._labels, self._vectors)
        ]
        scored.sort(key=lambda pair: pair[1], reverse=True)
        return scored[:limit]


#: Process-wide singleton - the vocabulary is embedded once, on first use.
tag_vocabulary = TagVocabulary()
