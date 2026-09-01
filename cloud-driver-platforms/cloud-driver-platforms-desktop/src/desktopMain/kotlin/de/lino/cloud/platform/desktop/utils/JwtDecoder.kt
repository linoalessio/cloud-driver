package de.lino.cloud.platform.desktop.utils

import com.google.gson.JsonParser
import java.util.Base64

/**
 * Decodes the `sub` (subject) claim out of a JWT's payload segment - `AuthService#login`/
 * `#confirmRegistration`/`#confirmPasswordReset` all `sign(user.getId(), ...)`, so `sub` is the
 * account's `AuthUser#getId()`, used here purely to display it on [DashboardScreen]. Deliberately
 * does **not** verify the signature - there's no need to: this only ever decodes a token this
 * same client just received from the server over TLS, to display a value locally, never to
 * authorize anything. Every real request still carries the raw token to the server, which is the
 * only party that actually verifies it. Returns `null` if `jwt` isn't a well-formed three-part
 * token or carries no `sub` claim.
 */
fun decodeJwtSubject(jwt: String): String? {
    val parts = jwt.split(".")
    if (parts.size != 3) return null
    return try {
        val payload = Base64.getUrlDecoder().decode(padBase64Url(parts[1]))
        JsonParser.parseString(String(payload)).asJsonObject.get("sub")?.asString
    } catch (e: Exception) {
        null
    }
}

private fun padBase64Url(segment: String): String {
    val remainder = segment.length % 4
    return if (remainder == 0) segment else segment + "=".repeat(4 - remainder)
}
