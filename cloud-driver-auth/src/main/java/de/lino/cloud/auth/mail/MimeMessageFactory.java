package de.lino.cloud.auth.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Builds the {@code multipart/related} {@link MimeMessage} (plain text + HTML alternative, plus
 * the Cloud Driver logo attached inline via {@code cid:} + {@link EmailTemplates#LOGO_CONTENT_ID})
 * both {@link SmtpEmailSender} and {@link SesEmailSender} send - extracted here, package-private,
 * so that MIME-assembly/logo-loading logic exists exactly once regardless of which transport
 * ultimately delivers the resulting message. {@link SmtpEmailSender} hands the built {@link
 * MimeMessage} straight to a {@code jakarta.mail.Transport}; {@link SesEmailSender} instead
 * serializes it to raw bytes (via {@link MimeMessage#writeTo}) and hands those to SES's own {@code
 * SendEmail} raw-message API - the message itself is built identically either way.
 */
final class MimeMessageFactory {

    /**
     * The logo image bytes attached inline to every outgoing message, read once from {@link
     * EmailTemplates#LOGO_RESOURCE_PATH} at class-init time since the same bytes back every send.
     */
    private static final byte[] LOGO_BYTES = loadLogoBytes();

    private MimeMessageFactory() {
    }

    /**
     * Builds a complete {@link MimeMessage} ready to send/serialize - {@code multipart/related}
     * containing a {@code multipart/alternative} (plain text, then HTML) plus the inline logo.
     *
     * @param session the Jakarta Mail session the message is constructed against
     * @param fromAddress the address the message is shown as coming from
     * @param toAddress the recipient address
     * @param subject the e-mail subject
     * @param htmlBody the HTML e-mail body
     * @param plainTextBody the plain-text fallback e-mail body
     * @return the assembled message
     * @throws MessagingException if any part of the message cannot be assembled
     */
    static MimeMessage buildMessage(final Session session, final String fromAddress, final String toAddress,
                                     final String subject, final String htmlBody, final String plainTextBody) throws MessagingException {
        final MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
        message.setSubject(subject, "UTF-8");
        message.setContent(buildContent(htmlBody, plainTextBody));
        return message;
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
        try (final InputStream in = MimeMessageFactory.class.getResourceAsStream(EmailTemplates.LOGO_RESOURCE_PATH)) {
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
