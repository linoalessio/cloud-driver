package de.lino.cloud.auth.mail;

import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.NonNull;

import java.util.Properties;

/**
 * {@link EmailSender} backed by SMTP with STARTTLS (Jakarta Mail API, Angus Mail as the runtime
 * implementation), authenticating with a username/password against a submission server. The
 * only {@link EmailSender} implementation meant for production use - see {@link
 * LoggingEmailSender} for the local-development fallback used when no SMTP server is
 * configured.
 */
public final class SmtpEmailSender implements EmailSender {

    private final String username;
    private final String password;
    private final String fromAddress;
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
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SmtpEmailSender.this.username, SmtpEmailSender.this.password);
            }
        });
    }

    /**
     * @throws EmailDeliveryException if the underlying {@link Transport#send} call fails for any
     *     reason (unreachable host, rejected credentials, rejected recipient, ...)
     */
    @Override
    public void send(@NonNull final String toAddress, @NonNull final String subject, @NonNull final String body)
            throws EmailDeliveryException {
        try {
            final MimeMessage message = new MimeMessage(this.session);
            message.setFrom(new InternetAddress(this.fromAddress));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toAddress));
            message.setSubject(subject);
            message.setText(body);
            Transport.send(message);
        } catch (final MessagingException e) {
            throw new EmailDeliveryException("@SmtpEmailSender.send: failed to send email to " + toAddress, e);
        }
    }

}
