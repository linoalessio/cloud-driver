package de.lino.cloud.auth.mail;

import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * {@link EmailSender} backed by SMTP with STARTTLS (Jakarta Mail API, Angus Mail as the runtime
 * implementation), authenticating with a username/password against a submission server. The
 * only {@link EmailSender} implementation meant for production use - see {@link
 * LoggingEmailSender} for the local-development fallback used when no SMTP server is
 * configured.
 *
 * <p>Sends a {@code multipart/related} message: a {@code multipart/alternative} part (plain
 * text + HTML) plus one inline image part carrying the Cloud Driver logo, referenced from the
 * HTML body via {@code cid:} + {@link EmailTemplates#LOGO_CONTENT_ID} - see {@link
 * EmailTemplates} for how that HTML/plain-text pair is actually built.
 */
public final class SmtpEmailSender implements EmailSender {

    /**
     * The logo image bytes attached inline to every outgoing message, read once from {@link
     * EmailTemplates#LOGO_RESOURCE_PATH} at class-init time since the same bytes back every send.
     */
    private static final byte[] LOGO_BYTES = loadLogoBytes();

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
     * Driver logo attached inline (see {@link EmailTemplates}).
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
            final MimeMessage message = new MimeMessage(this.session);
            message.setFrom(new InternetAddress(this.fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
            message.setSubject(subject, "UTF-8");
            message.setContent(buildContent(htmlBody, plainTextBody));
            Transport.send(message);
        } catch (final MessagingException e) {
            throw new EmailDeliveryException("@SmtpEmailSender.send: failed to send email to " + toAddress, e);
        }
    }

    /**
     * Assembles the {@code multipart/related} content: a {@code multipart/alternative} part
     * (plain text, then HTML - clients pick the last part they understand) plus the inline logo
     * image, addressable from the HTML part via {@code cid:} + {@link
     * EmailTemplates#LOGO_CONTENT_ID}.
     *
     * @param htmlBody the HTML e-mail body
     * @param plainTextBody the plain-text fallback e-mail body
     * @return the assembled {@code multipart/related} content
     * @throws MessagingException if any part cannot be assembled
     */
    private static MimeMultipart buildContent(final String htmlBody, final String plainTextBody) throws MessagingException {
        final MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(plainTextBody, "UTF-8");

        final MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");

        final MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textPart);
        alternative.addBodyPart(htmlPart);

        final MimeBodyPart alternativeWrapper = new MimeBodyPart();
        alternativeWrapper.setContent(alternative);

        final MimeBodyPart logoPart = new MimeBodyPart();
        logoPart.setDataHandler(new DataHandler(new ByteArrayDataSource(LOGO_BYTES, "image/png")));
        logoPart.setHeader("Content-ID", "<" + EmailTemplates.LOGO_CONTENT_ID + ">");
        logoPart.setDisposition(MimeBodyPart.INLINE);
        logoPart.setFileName("cloud-driver-icon.png");

        final MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(alternativeWrapper);
        related.addBodyPart(logoPart);
        return related;
    }

    /**
     * Reads {@link EmailTemplates#LOGO_RESOURCE_PATH} off this class's own classpath, once, at
     * class-init time.
     *
     * @return the logo image bytes
     * @throws UncheckedIOException if the resource is missing or cannot be read - a packaging
     *     defect, not a runtime condition any caller could recover from
     */
    private static byte[] loadLogoBytes() {
        try (final InputStream in = SmtpEmailSender.class.getResourceAsStream(EmailTemplates.LOGO_RESOURCE_PATH)) {
            if (in == null) {
                throw new UncheckedIOException(
                        new IOException("missing classpath resource: " + EmailTemplates.LOGO_RESOURCE_PATH));
            }
            return in.readAllBytes();
        } catch (final IOException e) {
            throw new UncheckedIOException("failed to read " + EmailTemplates.LOGO_RESOURCE_PATH, e);
        }
    }

}
