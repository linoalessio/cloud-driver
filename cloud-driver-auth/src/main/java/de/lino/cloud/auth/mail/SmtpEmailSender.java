package de.lino.cloud.auth.mail;

import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import jakarta.mail.Authenticator;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.NonNull;

import java.util.Properties;

/**
 * {@link EmailSender} backed by SMTP with STARTTLS (Jakarta Mail API, Angus Mail as the runtime
 * implementation), authenticating with a username/password against a submission server. One of
 * two {@link EmailSender} implementations meant for production use - see {@link SesEmailSender}
 * for the AWS SES-backed alternative (no SMTP credentials to manage, at the cost of a verified
 * sending identity in AWS) and {@link LoggingEmailSender} for the local-development fallback used
 * when neither is configured.
 *
 * <p>Sends the same {@code multipart/related} message (plain text + HTML, plus the inline Cloud
 * Driver logo) {@link SesEmailSender} does - both are built by {@link MimeMessageFactory}, only
 * delivered differently.
 */
public final class SmtpEmailSender implements EmailSender {

    /** The SMTP account username, also used to authenticate outgoing sessions. */
    private final String username;

    /** The SMTP account password, also used to authenticate outgoing sessions. */
    private final String password;

    /** The address every sent e-mail is shown as coming from. */
    private final String fromAddress;

    /** The Jakarta Mail session configured with STARTTLS and this instance's credentials. */
    private final Session session;

    /**
     * @param host the SMTP server host
     * @param port the SMTP server port (587, the common STARTTLS submission port, unless the
     *     provider says otherwise)
     * @param username the SMTP account username
     * @param password the SMTP account password
     * @param fromAddress the address every sent e-mail is shown as coming from
     */
    public SmtpEmailSender(@NonNull final String host, final int port, @NonNull final String username,
                            @NonNull final String password, @NonNull final String fromAddress) {
        this.username = username;
        this.password = password;
        this.fromAddress = fromAddress;

        final Properties properties = new Properties();
        properties.put("mail.smtp.host", host);
        properties.put("mail.smtp.port", String.valueOf(port));
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.starttls.enable", "true");

        this.session = Session.getInstance(properties, new Authenticator() {

            /**
             * Supplies this instance's SMTP {@link SmtpEmailSender#username}/{@link
             * SmtpEmailSender#password} to the Jakarta Mail session on demand.
             *
             * @return credentials for the configured SMTP account
             */
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SmtpEmailSender.this.username, SmtpEmailSender.this.password);
            }

        });
    }

    /**
     * Sends {@code htmlBody}/{@code plainTextBody} to {@code toAddress} with subject {@code
     * subject}, from {@link #fromAddress}, over this instance's SMTP session, with the Cloud
     * Driver logo attached inline (see {@link MimeMessageFactory}).
     *
     * @param toAddress the recipient address
     * @param subject the e-mail subject
     * @param htmlBody the HTML e-mail body
     * @param plainTextBody the plain-text fallback e-mail body
     * @throws EmailDeliveryException if the underlying {@link Transport#send} call fails for any
     *     reason (unreachable host, rejected credentials, rejected recipient, ...)
     */
    @Override
    public void send(@NonNull final String toAddress, @NonNull final String subject, @NonNull final String htmlBody,
                      @NonNull final String plainTextBody) throws EmailDeliveryException {
        try {
            final MimeMessage message = MimeMessageFactory.buildMessage(
                    this.session, this.fromAddress, toAddress, subject, htmlBody, plainTextBody);
            Transport.send(message);
        } catch (final MessagingException e) {
            throw new EmailDeliveryException("@SmtpEmailSender.send: failed to send email to " + toAddress, e);
        }
    }

}
