package de.lino.cloud.plugin.security.secrets;

import de.lino.cloud.api.utility.Asserts;
import java.util.regex.Pattern;

/**
 * Best-effort redaction of secrets from human-readable text (log lines,
 * error messages). A defense-in-depth safety net, not a substitute for not
 * logging secrets in the first place.
 */
public final class SecretRedactor {

    /** The fixed placeholder every match is replaced with. */
    private static final String REPLACEMENT = "[REDACTED]";

    /** Matches an {@code Authorization: Bearer <token>}-style bearer token value (case-insensitive), keeping the {@code "bearer "} prefix. */
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9\\-._~+/]+=*");

    /** Matches a whole {@code authorization: ...} header line (case-insensitive), keeping the {@code "authorization:"} prefix. */
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile("(?i)(authorization\\s*:\\s*)\\S+.*");

    /** Matches a common secret query-string parameter's value (e.g. {@code ?api_key=...}, {@code &token=...}), keeping the {@code name=} prefix. */
    private static final Pattern QUERY_STRING_SECRET =
            Pattern.compile("(?i)([?&](?:api[_-]?key|token|secret|password|client_secret)=)[^&\\s]+");

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private SecretRedactor() {
    }

    /**
     * Replaces recognizable secret material (bearer tokens, {@code
     * Authorization} headers, common secret query-string parameters) with a
     * fixed placeholder.
     *
     * @param text the text to redact
     * @return the redacted text
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String redact(final String text) {
        Asserts.requireNonNull(text, "@SecretRedactor.redact: text cannot be null");

        String redacted = BEARER_TOKEN.matcher(text).replaceAll("$1" + REPLACEMENT);
        redacted = AUTHORIZATION_HEADER.matcher(redacted).replaceAll("$1" + REPLACEMENT);
        redacted = QUERY_STRING_SECRET.matcher(redacted).replaceAll("$1" + REPLACEMENT);
        return redacted;
    }

    /**
     * Replaces every literal occurrence of {@code secretValue} in {@code text}
     * with a fixed placeholder. Use when the exact secret value is known at
     * the call site (e.g. before logging a caught exception's message).
     *
     * @param text the text to redact
     * @param secretValue the known secret to replace; a no-op if {@code null} or empty
     * @return the redacted text
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static String redactValue(final String text, final String secretValue) {
        Asserts.requireNonNull(text, "@SecretRedactor.redactValue: text cannot be null");
        if (secretValue == null || secretValue.isEmpty()) {
            return text;
        }
        return text.replace(secretValue, REPLACEMENT);
    }
}
