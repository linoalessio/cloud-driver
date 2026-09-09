"""Shared-secret authentication for every endpoint except ``/health``.

This is a second line of defence, never the first. The service is expected to bind to loopback or
an internal container network only - exactly as ``clamd`` and Redis already do in this deployment -
and this header exists to stop another process on the same host from trivially reading or
poisoning the vector store. It does not make the service safe to expose publicly.
"""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from .config import settings

#: Header name, matching the Java bridge's ``IntelligenceHttpClient.SHARED_SECRET_HEADER``.
SHARED_SECRET_HEADER = "X-Internal-Secret"


async def require_shared_secret(
    x_internal_secret: str | None = Header(default=None, alias=SHARED_SECRET_HEADER),
) -> None:
    """FastAPI dependency rejecting any request without the configured secret.

    Fails **closed** when no secret is configured at all: a service that would otherwise accept
    every request is strictly more dangerous than one that accepts none, and the operator sees an
    immediate, unambiguous 503 rather than an unauthenticated service they never notice.

    Compared with :func:`hmac.compare_digest` rather than ``==`` - the timing difference is
    unlikely to be exploitable across a loopback interface, but constant-time comparison of a
    secret costs nothing and matches what ``ApiKey``/``Argon2idPasswordHasher`` already do on the
    Java side.
    """
    if not settings.shared_secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="CLOUD_DRIVER_INTELLIGENCE_SECRET is not configured - refusing every request",
        )
    if x_internal_secret is None or not hmac.compare_digest(x_internal_secret, settings.shared_secret):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid shared secret")
