package de.lino.cloud.auth.mail;

/**
 * Builds the HTML/plain-text pair for every verification e-mail {@code AuthService} sends
 * (registration, password reset, e-mail change) - one shared shape (logo header, headline,
 * greeting, intro paragraph, a highlighted verification-code callout, a notice paragraph,
 * footer) parameterized per call site, rather than three near-duplicate templates. Styled after
 * a typical transactional-e-mail layout (a white card centered on a light gray page, a bordered
 * callout for the one piece of information the recipient actually needs to act on, a muted
 * footer) - table-based markup with inline styles throughout, since that is what actually
 * renders consistently across e-mail clients, unlike a plain CSS stylesheet.
 *
 * <p>The header logo is referenced as {@code cid:} + {@link #LOGO_CONTENT_ID} - {@link
 * SmtpEmailSender} is the one place that actually attaches the underlying image bytes (loaded
 * from {@link #LOGO_RESOURCE_PATH}) to the outgoing message as an inline part with that same
 * Content-ID.
 */
public final class EmailTemplates {

    /**
     * The Content-ID {@link SmtpEmailSender} attaches the logo image under - referenced from the
     * generated HTML as {@code cid:cloud-driver-icon}. Must match on both sides; nothing enforces
     * that at compile time, since the HTML is a plain string and the MIME header is a plain
     * string.
     */
    public static final String LOGO_CONTENT_ID = "cloud-driver-icon";

    /** Classpath location of the logo image {@link SmtpEmailSender} attaches inline. */
    public static final String LOGO_RESOURCE_PATH = "/mail/cloud-driver-icon.png";

    private static final String PAGE_BACKGROUND = "#eef1f6";
    private static final String CARD_BACKGROUND = "#ffffff";
    private static final String BORDER_COLOR = "#e4e8ee";
    private static final String HEADING_COLOR = "#1c2733";
    private static final String BODY_TEXT_COLOR = "#4c5566";
    private static final String MUTED_TEXT_COLOR = "#8a94a6";
    private static final String ACCENT_COLOR = "#0a84ff";
    private static final String ACCENT_BACKGROUND = "#eaf4ff";
    private static final String FONT_STACK =
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif";

    private EmailTemplates() {
    }

    /**
     * Builds the HTML body for a verification e-mail.
     *
     * @param headline the large heading at the top of the card, e.g. {@code "Confirm your registration."}
     * @param greetingAddress the e-mail address the "Hello ..." greeting line addresses
     * @param introText the paragraph explaining why this e-mail was sent, shown above the code
     * @param code the verification code, shown large and letter-spaced in its own callout box
     * @param noticeText the closing paragraph (expiry/"ignore this if it wasn't you") shown below the code
     * @return a complete, self-contained HTML document
     */
    public static String buildVerificationHtml(final String headline, final String greetingAddress, final String introText,
                                         final String code, final String noticeText) {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Cloud Driver</title>
                </head>
                <body style="margin:0; padding:0; background-color:%s;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;">
                <tr>
                <td align="center" style="padding:32px 16px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px; background-color:%s; border:1px solid %s; border-radius:16px; overflow:hidden;">
                <tr>
                <td style="padding:32px 40px 0 40px;">
                <table role="presentation" cellpadding="0" cellspacing="0">
                <tr>
                <td style="vertical-align:middle; padding-right:10px;">
                <img src="cid:%s" width="32" height="32" alt="Cloud Driver" style="display:block; border:0; border-radius:8px;">
                </td>
                <td style="vertical-align:middle;">
                <span style="font-family:%s; font-size:16px; font-weight:700; color:%s;">Cloud Driver</span>
                </td>
                </tr>
                </table>
                </td>
                </tr>
                <tr>
                <td style="padding:28px 40px 0 40px; font-family:%s;">
                <h1 style="margin:0; font-size:23px; line-height:30px; font-weight:700; color:%s;">%s</h1>
                </td>
                </tr>
                <tr>
                <td style="padding:16px 40px 0 40px; font-family:%s; font-size:14px; line-height:22px; color:%s;">
                <p style="margin:0 0 12px 0;">Hello %s,</p>
                <p style="margin:0;">%s</p>
                </td>
                </tr>
                <tr>
                <td style="padding:24px 40px 0 40px;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s; border:1px solid %s; border-radius:12px;">
                <tr>
                <td align="center" style="padding:20px 16px;">
                <span style="font-family:'SFMono-Regular', Consolas, Menlo, monospace; font-size:32px; font-weight:700; letter-spacing:8px; color:%s;">%s</span>
                </td>
                </tr>
                </table>
                </td>
                </tr>
                <tr>
                <td style="padding:20px 40px 32px 40px; font-family:%s; font-size:13px; line-height:20px; color:%s;">
                <p style="margin:0;">%s</p>
                </td>
                </tr>
                <tr>
                <td style="border-top:1px solid %s;"></td>
                </tr>
                <tr>
                <td style="padding:20px 40px 28px 40px; font-family:%s; font-size:12px; line-height:18px; color:%s;">
                <p style="margin:0 0 4px 0;">This e-mail was sent automatically - please do not reply.</p>
                <p style="margin:0;">Cloud Driver</p>
                </td>
                </tr>
                </table>
                </td>
                </tr>
                </table>
                </body>
                </html>
                """.formatted(
                PAGE_BACKGROUND, PAGE_BACKGROUND, CARD_BACKGROUND, BORDER_COLOR,
                LOGO_CONTENT_ID,
                FONT_STACK, HEADING_COLOR,
                FONT_STACK, HEADING_COLOR, escapeHtml(headline),
                FONT_STACK, BODY_TEXT_COLOR, escapeHtml(greetingAddress), escapeHtml(introText),
                ACCENT_BACKGROUND, BORDER_COLOR,
                ACCENT_COLOR, escapeHtml(code),
                FONT_STACK, MUTED_TEXT_COLOR, escapeHtml(noticeText),
                BORDER_COLOR,
                FONT_STACK, MUTED_TEXT_COLOR
        );
    }

    /**
     * Builds the plain-text fallback body for the same e-mail {@link #buildVerificationHtml}
     * renders - shown by any client that can't (or won't) render HTML.
     *
     * @param greetingAddress the e-mail address the "Hello ..." greeting line addresses
     * @param introText the paragraph explaining why this e-mail was sent
     * @param code the verification code
     * @param noticeText the closing paragraph (expiry/"ignore this if it wasn't you")
     * @return the plain-text body
     */
    public static String buildVerificationPlainText(final String greetingAddress, final String introText, final String code,
                                              final String noticeText) {
        return String.join("\n",
                "Hello " + greetingAddress + ",",
                "",
                introText,
                "",
                "Your verification code: " + code,
                "",
                noticeText,
                "",
                "This e-mail was sent automatically - please do not reply.",
                "Cloud Driver"
        );
    }

    /**
     * Escapes the handful of characters that would otherwise let a value break out of the HTML
     * this class generates - deliberately narrow (this codebase never depends on a full HTML
     * escaping library here), since the only dynamic values ever passed through are an
     * already-validated e-mail address and a 6-digit numeric code.
     *
     * @param value the raw value to escape
     * @return {@code value} with {@code & < > " '} replaced by their HTML entities
     */
    private static String escapeHtml(final String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

}
