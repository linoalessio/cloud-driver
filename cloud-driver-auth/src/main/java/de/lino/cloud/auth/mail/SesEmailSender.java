package de.lino.cloud.auth.mail;

import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.NonNull;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.RawMessage;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * {@link EmailSender} backed by AWS SES (Simple Email Service) v2, delivered via {@code
 * SendEmail}'s raw-message content type rather than SES's own simple/templated content - the
 * message itself (a {@code multipart/related} plain text + HTML body with the Cloud Driver logo
 * attached inline) is built by {@link MimeMessageFactory}, the exact same code {@link
 * SmtpEmailSender} uses, then serialized to raw MIME bytes ({@link MimeMessage#writeTo}) and
 * handed to SES instead of an SMTP {@code Transport} - so both implementations produce
 * byte-for-byte identical messages and only differ in how the message actually leaves this
 * process. One of two {@link EmailSender} implementations meant for production use - see {@link
 * SmtpEmailSender}'s own Javadoc for the trade-off between the two, and {@link LoggingEmailSender}
 * for the local-development fallback used when neither is configured.
 *
 * <p>This class lives in {@code cloud-driver-auth}, not alongside {@code
 * AwsKmsKeyEncryptionService}/{@code S3ObjectStorageService} in {@code cloud-driver-plugin} - {@code
 * cloud-driver-auth} must never depend on {@code cloud-driver-plugin} (see the module-layout
 * rules), and {@link EmailSender}'s other implementations already live in this module, so this
 * module gained its own direct {@code software.amazon.awssdk:sesv2} dependency instead.
 *
 * <p>Credentials are resolved via the AWS SDK's own default credential provider chain - never read
 * from {@code configuration.json} - the same convention every other AWS-backed service in this
 * codebase already follows (see {@code AwsKmsKeyEncryptionService}/{@code S3ObjectStorageService}).
 * A caller wiring this in resolves the region/from-address from {@code configuration.json}'s
 * {@code "aws-ses-region"}/{@code "aws-ses-from-address"} keys and lets the SDK resolve the actual
 * AWS credentials itself - see {@code CloudRestExtension#buildEmailSender}.
 *
 * <p>Requires {@code fromAddress} (or its domain) to already be a verified sending identity in the
 * target AWS account/region, and - unless that account has been moved out of the SES sandbox -
 * every recipient address verified too; an unverified sender/recipient causes {@link
 * SesV2Exception} on every {@link #send} call, surfaced here as {@link EmailDeliveryException}.
 */
public final class SesEmailSender implements EmailSender {

    /** A plain, unauthenticated Jakarta Mail session - only used to build a {@link MimeMessage} in memory, never to actually connect anywhere. */
    private static final Session MESSAGE_SESSION = Session.getInstance(new Properties());

    /** The SES v2 client this instance sends every message through. */
    private final SesV2Client client;

    /** The address every sent e-mail is shown as coming from - must be a verified SES sending identity. */
    private final String fromAddress;

    /**
     * Convenience constructor: builds a {@link SesV2Client} against {@code region}, resolving AWS
     * credentials via the SDK's own default credential provider chain.
     *
     * @param region the AWS region SES is reached in (must be a region where SES is available and
     *     {@code fromAddress} is a verified identity)
     * @param fromAddress the address every sent e-mail is shown as coming from
     */
    public SesEmailSender(@NonNull final Region region, @NonNull final String fromAddress) {
        this(SesV2Client.builder().region(region).build(), fromAddress);
    }

    /**
     * @param client an already-configured {@link SesV2Client} (e.g. for tests, or a caller that
     *     needs non-default client configuration)
     * @param fromAddress the address every sent e-mail is shown as coming from
     */
    public SesEmailSender(@NonNull final SesV2Client client, @NonNull final String fromAddress) {
        this.client = client;
        this.fromAddress = fromAddress;
    }

    /**
     * Sends {@code htmlBody}/{@code plainTextBody} to {@code toAddress} with subject {@code
     * subject}, from {@link #fromAddress}, via SES's {@code SendEmail} raw-message API, with the
     * Cloud Driver logo attached inline (see {@link MimeMessageFactory}).
     *
     * @param toAddress the recipient address
     * @param subject the e-mail subject
     * @param htmlBody the HTML e-mail body
     * @param plainTextBody the plain-text fallback e-mail body
     * @throws EmailDeliveryException if the message cannot be assembled, or SES rejects sending it
     *     (unverified sender/recipient, throttling, a suppressed/bounced address, ...)
     */
    @Override
    public void send(@NonNull final String toAddress, @NonNull final String subject, @NonNull final String htmlBody,
                      @NonNull final String plainTextBody) throws EmailDeliveryException {
        final byte[] rawMessage;
        try {
            final MimeMessage message = MimeMessageFactory.buildMessage(
                    MESSAGE_SESSION, this.fromAddress, toAddress, subject, htmlBody, plainTextBody);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            message.writeTo(out);
            rawMessage = out.toByteArray();
        } catch (final MessagingException | IOException e) {
            throw new EmailDeliveryException("@SesEmailSender.send: failed to build email for " + toAddress, e);
        }

        try {
            this.client.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(this.fromAddress)
                    .destination(Destination.builder().toAddresses(toAddress).build())
                    .content(EmailContent.builder()
                            .raw(RawMessage.builder().data(SdkBytes.fromByteArray(rawMessage)).build())
                            .build())
                    .build());
        } catch (final SesV2Exception e) {
            throw new EmailDeliveryException("@SesEmailSender.send: SES rejected sending email to " + toAddress, e);
        }
    }

}
