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


class Settings:
    """Immutable-by-convention settings snapshot."""

    def __init__(self) -> None:
        #: Shared secret every request must present as ``X-Internal-Secret``. There is deliberately
        #: no default: a service that authenticates with a well-known constant is worse than one
        #: that refuses to start, so an unset value makes every request fail closed (see
        #: ``security.require_shared_secret``).
        self.shared_secret: str | None = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_SECRET")

        #: Directory the Chroma store persists into. Holds embeddings derived from real file
        #: content - see this module's README on why that is a genuine at-rest consideration.
        self.store_path: str = os.environ.get("CLOUD_DRIVER_INTELLIGENCE_STORE_PATH", "./chroma")

        #: sentence-transformers model id. ``all-MiniLM-L6-v2`` is small (~90 MB), fast on CPU, and
        #: good enough for filename+text similarity - the v1 choice; swapping in a multilingual or
        #: multimodal (CLIP) model is a config change plus a full re-index, nothing structural.
        self.embedding_model: str = os.environ.get(
            "CLOUD_DRIVER_INTELLIGENCE_MODEL", "all-MiniLM-L6-v2"
        )

        #: Hard cap on how much decoded text is ever embedded, in characters. An embedding model
        #: truncates to its own context window anyway (384 tokens for MiniLM); this simply avoids
        #: spending time decoding and tokenizing megabytes that can never influence the vector.
        self.max_embedded_chars: int = _int_env("CLOUD_DRIVER_INTELLIGENCE_MAX_CHARS", 20_000)


settings = Settings()
