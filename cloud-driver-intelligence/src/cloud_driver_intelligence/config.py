"""Runtime configuration, read once from the environment at import time.

Deliberately environment-driven rather than reading ``cloud-driver``'s own
``configuration.json``: this service is a separate process with a separate lifecycle (and
possibly a separate host or container), and reaching into the Java side's config file would
couple the two deployments together for no benefit. The Java bridge reads its half of the
settings - host, port, shared secret - from ``configuration.json``; this side reads its own from
the environment, and the shared secret is simply the one value that must match on both.
"""

from __future__ import annotations

import os


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _float_env(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _bool_env(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


#: Default zero-shot tag vocabulary (see ``embeddings.suggest_tags``).
#:
#: Deliberately short, concrete and domain-general. A long vocabulary is actively worse: every
#: label competes for the same similarity budget, so adding near-synonyms ("bill" alongside
#: "invoice") mostly redistributes confidence between them rather than improving the top result.
#: Both English and German terms are present because this deployment's users file documents in
#: both, and the embedding model maps them into one shared space.
_DEFAULT_TAG_VOCABULARY = (
    "invoice,receipt,contract,tax document,bank statement,insurance,medical record,prescription,"
    "payslip,identity document,certificate,report,presentation,spreadsheet,source code,"
    "photo,screenshot,scanned document,letter,email,manual,notes,music,video,archive,"
    "Rechnung,Vertrag,Steuerunterlagen,Kontoauszug,Versicherung,Arztbrief,Gehaltsabrechnung,Zeugnis"
)


class Settings:
    """Immutable-by-convention settings snapshot."""

    def __init__(self) -> None:
        #: Shared secret every request must present as ``X-Internal-Secret``. There is deliberately
        #: no default: a service that authenticates with a well-known constant is worse than one
        #: that refuses to start, so an unset value makes every request fail closed (see
        #: ``security.require_shared_secret``).
        self.shared_secret: str | None = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_SECRET")

        #: Directory the persistent store lives in. Holds vectors derived from real file content -
        #: see ``encryption_key`` below and this module's README for that trade-off in full.
        self.store_path: str = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_STORE_PATH", "./chroma")

        #: sentence-transformers model id for **text**. ``all-MiniLM-L6-v2`` is small (~90 MB),
        #: fast on CPU, and good enough for filename+text similarity.
        self.embedding_model: str = os.environ.get(
            "CLOUD_DRIVER_INTELLIGENCE_MODEL", "all-MiniLM-L6-v2"
        )

        #: Hard cap on how much decoded text is ever embedded, in characters. An embedding model
        #: truncates to its own context window anyway (384 tokens for MiniLM); this simply avoids
        #: spending time decoding and tokenizing megabytes that can never influence the vector.
        self.max_embedded_chars: int = _int_env("CLOUD_DRIVER_INTELLIGENCE_MAX_CHARS", 20_000)

        #: Base64-encoded 32-byte AES-256-GCM key. **When set, every stored vector is encrypted at
        #: rest** and the encrypted SQLite store is used instead of Chroma (see ``store.py``).
        #:
        #: This closes the one at-rest gap this service has always carried: embeddings are derived
        #: from real file content and are partially invertible, so a store file readable by anyone
        #: with filesystem access is a genuine exposure - and the only such path in this codebase
        #: that was not already envelope-encrypted. Unset leaves the previous, unencrypted
        #: behaviour untouched rather than silently changing an existing deployment's storage
        #: format; the vector store is fully derived, so opting in is a key plus a re-index.
        self.encryption_key: str | None = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_ENCRYPTION_KEY")

        #: Extract text from PDFs with ``pypdf`` (the ``pdf`` extra) before falling back to the
        #: file name alone.
        self.pdf_extraction_enabled: bool = _bool_env("CLOUD_DRIVER_INTELLIGENCE_PDF", True)

        #: Run OCR over images (and over PDF pages that yielded no extractable text) via
        #: ``pytesseract`` (the ``ocr`` extra). Off by default: OCR needs a *system* ``tesseract``
        #: binary that no ``pip install`` provides, and is an order of magnitude slower per file
        #: than every other extractor here - an opt-in, not a default.
        self.ocr_enabled: bool = _bool_env("CLOUD_DRIVER_INTELLIGENCE_OCR", False)

        #: Language hint passed to tesseract. ``deu+eng`` matches this deployment's actual users;
        #: each language needs its own installed tesseract data pack.
        self.ocr_languages: str = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_OCR_LANGUAGES", "deu+eng")

        #: Embed images with a CLIP model so a text query can match a photo's *content* rather than
        #: only its file name (the ``clip`` extra). Off by default - it is a second model in
        #: memory, roughly 600 MB with its weights.
        self.clip_enabled: bool = _bool_env("CLOUD_DRIVER_INTELLIGENCE_CLIP", False)

        #: The CLIP model id. Must be one that encodes **both** images and text into a single
        #: shared space - that shared space is the entire mechanism, since a text query is matched
        #: against image vectors directly.
        self.clip_model: str = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_CLIP_MODEL", "clip-ViT-B-32")

        #: Comma-separated zero-shot label vocabulary for ``POST /tags``.
        self.tag_vocabulary: tuple[str, ...] = tuple(
            label.strip()
            for label in os.environ.get(
                "CLOUD_DRIVER_INTELLIGENCE_TAGS", _DEFAULT_TAG_VOCABULARY
            ).split(",")
            if label.strip()
        )

        #: Default cosine-similarity floor for ``POST /duplicates`` when a caller does not specify
        #: one. High on purpose: similarity is not linear in perceived sameness, and anything much
        #: below this groups files that merely share a topic - which, for a feature whose output a
        #: human reads as "these are the same document", is worse than returning nothing.
        self.duplicate_threshold: float = _float_env(
            "CLOUD_DRIVER_INTELLIGENCE_DUPLICATE_THRESHOLD", 0.95
        )


settings = Settings()
