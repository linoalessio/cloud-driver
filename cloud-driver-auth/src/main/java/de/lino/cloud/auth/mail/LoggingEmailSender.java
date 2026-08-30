package de.lino.cloud.auth.mail;

import de.lino.cloud.api.mail.EmailSender;
import lombok.NonNull;

import java.util.logging.Logger;

/**
 * {@link EmailSender} that only logs the message instead of actually sending it - the same
 * "not for production" trade-off {@code InMemoryKeyEncryptionService} makes for key material.
 * Used whenever no SMTP server is configured (e.g. local development), so registration still
 * works end-to-end without a real mail server: the verification code shows up in the log/console
 * instead of an inbox.
 */
public final class LoggingEmailSender implements EmailSender {

    /** Where a "sent" e-mail is logged instead of actually being delivered. */
    private final Logger logger;

    /**
     * @param logger where a "sent" e-mail is logged instead, typically {@code
     *     CloudDriver#getLogger()}
     */
    public LoggingEmailSender(@NonNull final Logger logger) {
        this.logger = logger;
    }

    /** Never throws - there is nothing to fail, this only logs. */
    @Override
    public void send(@NonNull final String toAddress, @NonNull final String subject, @NonNull final String body) {
        this.logger.info(
                "[LoggingEmailSender] Would send email to " + toAddress + " - subject: '" + subject + "' - body: " + body);
    }

}
