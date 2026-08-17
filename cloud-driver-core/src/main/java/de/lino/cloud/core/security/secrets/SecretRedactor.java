package de.lino.cloud.core.security.secrets;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Best-effort redaction of secrets from human-readable text (log lines, error
 * messages) per section 7 (API SECURITY): "Secrets SHALL NOT appear in source
 * code, logs, URLs, or error messages." This is a defense-in-depth safety
 * net for text that is about to be logged or surfaced in an error - it is
 * not a substitute for not logging secrets in the first place.
 */
public final class SecretRedactor {

    private static final String REPLACEMENT = "[REDACTED]";

    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile("(?i)(authorization\\s*:\\s*)\\S+.*");
    private static final Pattern QUERY_STRING_SECRET =
            Pattern.compile("(?i)([?&](?:api[_-]?key|token|secret|password|client_secret)=)[^&\\s]+");

    private SecretRedactor() {
    }

    /**
     * Replaces recognizable secret material (bearer tokens, {@code
     * Authorization} headers, common secret query-string parameters) with a
     * fixed placeholder.
     */
    public static String redact(final String text) {
        Objects.requireNonNull(text, "@SecretRedactor.redact: text cannot be null");

        String redacted = BEARER_TOKEN.matcher(text).replaceAll("$1" + REPLACEMENT);
        redacted = AUTHORIZATION_HEADER.matcher(redacted).replaceAll("$1" + REPLACEMENT);
        redacted = QUERY_STRING_SECRET.matcher(redacted).replaceAll("$1" + REPLACEMENT);
        return redacted;
    }

    /**
     * Replaces every literal occurrence of {@code secretValue} in {@code text}
     * with a fixed placeholder. Use when the exact secret value is known at
     * the call site (e.g. before logging a caught exception's message).
     */
    public static String redactValue(final String text, final String secretValue) {
        Objects.requireNonNull(text, "@SecretRedactor.redactValue: text cannot be null");
        if (secretValue == null || secretValue.isEmpty()) {
            return text;
        }
        return text.replace(secretValue, REPLACEMENT);
    }
}
