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
 *
 * <p>Optionally sends every message through a named SES <em>configuration set</em> (see {@link
 * #configurationSetName}), which is how bounce/complaint/delivery events are routed to an SNS
 * topic for monitoring. Naming it here rather than relying on a default configuration set
 * configured on the sending identity itself makes the binding explicit and survives someone later
 * clearing that identity-level setting in the AWS console. <strong>The named set must already
 * exist in the target account/region</strong> - SES rejects every {@code SendEmail} call naming a
 * set that does not exist ({@code ConfigurationSetDoesNotExist}), so leave this unset until the
 * set has actually been created.
 */
public final class SesEmailSender implements EmailSender {

    /** A plain, unauthenticated Jakarta Mail session - only used to build a {@link MimeMessage} in memory, never to actually connect anywhere. */
    private static final Session MESSAGE_SESSION = Session.getInstance(new Properties());

    /** The SES v2 client this instance sends every message through. */
    private final SesV2Client client;

    /** The address every sent e-mail is shown as coming from - must be a verified SES sending identity. */
    private final String fromAddress;

    /**
     * The SES configuration set every message is sent through, or {@code null} to send without
     * naming one at all (in which case SES applies whatever default configuration set is
     * configured on the sending identity, if any). Must name a set that already exists in the
     * target account/region - see this class's own Javadoc.
     */
    private final String configurationSetName;

    /**
     * Convenience constructor: builds a {@link SesV2Client} against {@code region}, resolving AWS
     * credentials via the SDK's own default credential provider chain.
     *
     * @param region the AWS region SES is reached in (must be a region where SES is available and
     *     {@code fromAddress} is a verified identity)
     * @param fromAddress the address every sent e-mail is shown as coming from
     */
    public SesEmailSender(@NonNull final Region region, @NonNull final String fromAddress) {
        this(region, fromAddress, null);
    }

    /**
     * Convenience constructor: builds a {@link SesV2Client} against {@code region}, resolving AWS
     * credentials via the SDK's own default credential provider chain, and sends every message
     * through {@code configurationSetName}.
     *
     * @param region the AWS region SES is reached in (must be a region where SES is available and
     *     {@code fromAddress} is a verified identity)
     * @param fromAddress the address every sent e-mail is shown as coming from
     * @param configurationSetName the SES configuration set to send through, or {@code null}/blank
     *     to name none - see {@link #configurationSetName}. Must already exist in the target
     *     account/region if given.
     */
    public SesEmailSender(@NonNull final Region region, @NonNull final String fromAddress,
                          final String configurationSetName) {
        this(SesV2Client.builder().region(region).build(), fromAddress, configurationSetName);
    }

    /**
     * @param client an already-configured {@link SesV2Client} (e.g. for tests, or a caller that
     *     needs non-default client configuration)
     * @param fromAddress the address every sent e-mail is shown as coming from
     */
    public SesEmailSender(@NonNull final SesV2Client client, @NonNull final String fromAddress) {
        this(client, fromAddress, null);
    }

    /**
     * @param client an already-configured {@link SesV2Client} (e.g. for tests, or a caller that
     *     needs non-default client configuration)
     * @param fromAddress the address every sent e-mail is shown as coming from
     * @param configurationSetName the SES configuration set to send through, or {@code null}/blank
     *     to name none - see {@link #configurationSetName}. Must already exist in the target
     *     account/region if given. Normalized to {@code null} if blank, so an unset/empty
     *     {@code configuration.json} value behaves identically to omitting it.
     */
    public SesEmailSender(@NonNull final SesV2Client client, @NonNull final String fromAddress,
                          final String configurationSetName) {
        this.client = client;
        this.fromAddress = fromAddress;
        this.configurationSetName = configurationSetName == null || configurationSetName.isBlank()
                ? null
                : configurationSetName;
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
     *     (unverified sender/recipient, throttling, a suppressed/bounced address, a {@link
     *     #configurationSetName} naming a set that does not exist, ...)
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

        final SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(this.fromAddress)
                .destination(Destination.builder().toAddresses(toAddress).build())
                .content(EmailContent.builder()
                        .raw(RawMessage.builder().data(SdkBytes.fromByteArray(rawMessage)).build())
                        .build());

        // Only ever named when actually configured - passing a null/blank name would be sent as a
        // literal (nonexistent) set name, which SES rejects outright.
        if (this.configurationSetName != null) {
            request.configurationSetName(this.configurationSetName);
        }

        try {
            this.client.sendEmail(request.build());
        } catch (final SesV2Exception e) {
            throw new EmailDeliveryException("@SesEmailSender.send: SES rejected sending email to " + toAddress, e);
        }
    }

}
