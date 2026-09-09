package de.lino.cloud.extensions.rest;

import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.s3storage.PresignedTransferService;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.audit.AuditLogServiceImpl;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.auth.mail.LoggingEmailSender;
import de.lino.cloud.auth.mail.SesEmailSender;
import de.lino.cloud.auth.mail.SmtpEmailSender;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.cloud.plugin.s3storage.S3PresignedTransferService;
import de.lino.database.json.JsonDocument;
import software.amazon.awssdk.regions.Region;

import java.util.logging.Level;

/**
 * Hosts the JWT-authenticated {@code RestFactory} - the actual place {@code RestFactory#start}
 * is called from in this repo (not {@code CloudBootstrap}, despite what older comments elsewhere
 * may still say).
 */
public class CloudRestExtension extends Extension {

    /** The configured listen port, read from {@code configuration.json}'s {@code "rest-server-port"} during {@link #onLoading()}. */
    private static int REST_SERVER_PORT;

    /**
     * Default bind interface if {@code "rest-api-bind-host"} isn't set in {@code
     * configuration.json} - every interface, matching this extension's original (pre-reverse-
     * proxy) behavior. A production deployment fronted by a TLS-terminating reverse proxy (see
     * {@code shell/Caddyfile}) should set that config key to {@code "127.0.0.1"} instead, so
     * Javalin's plain-HTTP listener is only reachable from the proxy running on the same
     * machine, never directly from the internet.
     */
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";

    /** The JWT-authenticated {@link RestFactory} this extension owns, once {@link #startRestApi()} has run. */
    private static volatile RestFactory REST_FACTORY;

    /**
     * Reads the configured listen port and delegates to {@link #startRestApi()}, which builds
     * the JWT-authenticated {@link RestFactory} (mounting {@code /auth/login} and the
     * {@code /cloudUsers}/{@code /files}/{@code /folders} routes) and starts it listening.
     *
     * @throws NullPointerException if {@code "rest-server-port"} is missing from {@code configuration.json}
     */
    @Override
    public void onLoading() {

        REST_SERVER_PORT = this.cloudDriver().getConfiguration().getInteger("rest-server-port");
        this.startRestApi();

    }

    /**
     * Prints a confirmation once the REST server started by {@link #onLoading()} is listening.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

        this.cloudDriver().getTerminal().displayApproved("&3Rest endpoint &bopened &7and listening on port &b&l%s", REST_SERVER_PORT);

    }

    /**
     * Stops the REST server and logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(RuntimeException reason) {

        if (REST_FACTORY != null) REST_FACTORY.stop();
        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud rest extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

    /** Stops the REST server. */
    @Override
    public void onEnding() {

        if (REST_FACTORY != null) {
            this.cloudDriver().getTerminal().displayApproved("&3Rest endpoint &7successfully &cclosed&7.");
            REST_FACTORY.stop();
        }

    }

    /**
     * Builds the JWT-authenticated {@link RestFactory} and starts it. A blank {@code
     * "jwt-signing-key"} in {@code configuration.json} logs a warning and returns without
     * starting the server, rather than throwing - a deployment that hasn't configured JWT auth
     * yet should still be able to boot every other subsystem normally.
     */
    private void startRestApi() {

        final String signingKey = this.cloudDriver().getConfiguration().getString("jwt-signing-key");

        if (signingKey.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.startRestApi: 'jwt-signing-key' is not set in configuration.json - "
                            + "the JWT-authenticated REST API will not be started. Generate one via: openssl rand -base64 32");
            return;
        }

        final String configuredBindHost = this.cloudDriver().getConfiguration().getString("rest-server-bind-host");
        final String bindHost = configuredBindHost.isBlank() ? DEFAULT_BIND_HOST : configuredBindHost;

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        final FileFactory fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();
        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final JwtSigner jwtSigner = new JjwtSigner(signingKey);

        final EmailSender emailSender = this.buildEmailSender();
        // The audit log service is built here, not in cloud-driver-auth itself, since redacting
        // AuditEvent#getMetadata() needs SecretRedactor (cloud-driver-plugin) - a dependency
        // cloud-driver-auth must never take on directly. This extension already depends on
        // both modules, so it's the natural place to close that gap via constructor injection.
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
        final PresignedTransferService presignedTransferService = this.resolvePresignedTransferService(this.cloudDriver().getConfiguration());
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService, presignedTransferService);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner, emailSender, cloudUserService, auditLogService);

        // Published back onto the shared IServiceContainer so any other caller (e.g. a terminal
        // Command, or CloudUser#getStoredFiles()) reaches these exact instances via
        // CloudDriver.getInstance().getServiceContainer() - that container starts out empty
        // (see ServiceContainer's own Javadoc) since the CloudDriver-level RestFactory built in
        // FactoryContainer is deliberately unauthenticated and never carries real AuthService/
        // CloudUserService instances of its own.
        this.cloudDriver().getServiceContainer().setAuthService(authService);
        this.cloudDriver().getServiceContainer().setCloudUserService(cloudUserService);
        this.cloudDriver().getServiceContainer().setAuditLogService(auditLogService);

        // Lets GET /files/{id}/content stream a direct-transfer file's content straight from S3
        // instead of resolving it as a byte[] first - see DefaultRestFactory#resolveDownloadableContent.
        // null on a deployment that hasn't opted into S3-backed s3storage, in which case that route
        // simply keeps its prior, fully-materializing behavior.
        final ObjectStorageService objectStorageService = this.cloudDriver().getFactoryContainer().getObjectStorageService();
        final DefaultRestFactory restFactory = new DefaultRestFactory(dataFactory, authService, cloudUserService, objectStorageService);
        REST_FACTORY = restFactory;

        // Live push via WebSocket: DefaultRestFactory
        // itself implements LiveUpdatePublisher (it owns the WebSocket route's connected-session
        // registry) - published here the same way authService/cloudUserService are, so
        // DatabaseWatchEvent#handle (cloud-driver-api, no dependency on this module) can reach it
        // purely through IServiceContainer without cloud-driver-api ever depending on Javalin.
        this.cloudDriver().getServiceContainer().setLiveUpdatePublisher(restFactory);

        // AuthUser and StoredFile are deliberately NOT mounted here at all - both are entities
        // with no Owned scoping (AuthUser has no ownership concept, it IS the account; StoredFile's
        // ownership lives entirely outside itself, in per-file StoredFileOwnership rows), so a generic
        // register()/update() would let any authenticated caller overwrite an arbitrary existing
        // record by id (EntityDatabaseClient#store falls back to update-on-collision) - an account
        // takeover vector for AuthUser (spoofed passwordHash under a victim's id) and a way to
        // silently overwrite another user's file content for StoredFile, bypassing the ownership
        // tracking CloudUserService/the /files routes above provide. AuthUser accounts are only
        // ever created through the dedicated registration flow, never a generic write route;
        // StoredFile uploads/reads/deletes go exclusively
        // through the /files routes above, which enforce per-user ownership via CloudUserService.

        // register()/update() are deliberately NOT mounted for CloudUser either: its primary key
        // (authUserId) already doubles as its Owned#ownerId(), but Gson serializes that field
        // as "authUserId", not "ownerId" - so DefaultRestFactory#parseOwnedBody's ownerId-spoof
        // protection (which only overwrites a JSON "ownerId" property) would not stop a caller
        // from sending an arbitrary "authUserId" in the request body and overwriting another
        // user's CloudUser record. CloudUser is created/mutated exclusively through
        // CloudUserService (see the /files routes above), never through generic REST writes.
        REST_FACTORY.fetch("/cloudUsers", CloudUser.class);

        REST_FACTORY.start(bindHost, REST_SERVER_PORT);
    }

    /**
     * Builds the {@link EmailSender} {@link AuthService#register} sends its verification codes
     * through. Tries AWS SES first (via {@link #resolveSesEmailSender(JsonDocument)}, {@code
     * "aws-ses-region"}/{@code "aws-ses-from-address"}), then falls back to SMTP (via {@link
     * #resolveSmtpEmailSender(JsonDocument)}, the original {@code "smtp-*"} keys), then falls back
     * to a {@link LoggingEmailSender} if neither is configured - never fails to start the REST API
     * entirely just because no real mail transport is configured: every other subsystem, and
     * every route except registration, works the same either way, and a fallback that only logs
     * the code is enough to keep local development/testing working without a real mail server.
     * SES is checked first (not SMTP) since it needs no long-lived credential stored in {@code
     * configuration.json} at all - only a region and a verified sending address, with the actual
     * AWS credentials resolved via the SDK's own default credential provider chain, the same
     * convention every other AWS-backed service in this codebase already follows.
     *
     * @return a {@link SesEmailSender} if SES is configured, else a {@link SmtpEmailSender} if
     *     SMTP is fully configured, else a {@link LoggingEmailSender}
     */
    private EmailSender buildEmailSender() {

        final JsonDocument configuration = this.cloudDriver().getConfiguration();

        final EmailSender sesEmailSender = this.resolveSesEmailSender(configuration);
        if (sesEmailSender != null) {
            return sesEmailSender;
        }

        final EmailSender smtpEmailSender = this.resolveSmtpEmailSender(configuration);
        if (smtpEmailSender != null) {
            return smtpEmailSender;
        }

        this.getLogger().warning(
                "@CloudRestExtension.buildEmailSender: neither 'aws-ses-region'/'aws-ses-from-address' nor "
                        + "'smtp-host' is set in configuration.json - verification codes will only be logged, "
                        + "not actually e-mailed. Not suitable for production.");
        return new LoggingEmailSender(this.getLogger());
    }

    /**
     * Resolves a {@link SesEmailSender} from {@code configuration.json}'s {@code
     * "aws-ses-region"}/{@code "aws-ses-from-address"} keys - {@code null} (SES not configured,
     * including on a {@code configuration.json} that predates this feature) if {@code
     * "aws-ses-region"} is missing/blank. If {@code "aws-ses-region"} is set but {@code
     * "aws-ses-from-address"} is missing/blank, logs a warning and returns {@code null} too,
     * rather than constructing a {@link SesEmailSender} that could never actually send anything.
     *
     * <p>Also reads the optional {@code "aws-ses-configuration-set"} key - the name of an SES
     * configuration set every message is then sent through, which is how bounce/complaint/delivery
     * events reach an SNS topic for monitoring. Absent/blank (the default) sends without naming one,
     * exactly as this method behaved before that key existed, in which case SES still applies
     * whatever default configuration set is set on the sending identity itself. <strong>Only set
     * this key once the named set actually exists in the target account/region</strong> - SES
     * rejects every send naming a nonexistent set, which would take down account registration,
     * password reset and e-mail change all at once.
     * AWS credentials themselves are never read from here - the SDK's own default credential
     * provider chain resolves them, the same convention {@code AwsKmsKeyEncryptionService}/{@code
     * S3ObjectStorageService} already established for every AWS-backed service in this codebase.
     *
     * @param configuration this deployment's loaded {@code configuration.json}
     * @return a configured {@link SesEmailSender}, or {@code null} if SES isn't configured
     */
    private EmailSender resolveSesEmailSender(final JsonDocument configuration) {
        final String regionName = this.configString(configuration, "aws-ses-region");
        if (regionName.isBlank()) {
            return null;
        }

        final String fromAddress = this.configString(configuration, "aws-ses-from-address");
        if (fromAddress.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.resolveSesEmailSender: 'aws-ses-region' is set but "
                            + "'aws-ses-from-address' is missing/blank in configuration.json - falling back to "
                            + "SMTP/logging instead of AWS SES.");
            return null;
        }

        // Optional: absent/blank leaves the configuration set unnamed, which is the pre-existing
        // behaviour and the only safe default (a name SES does not know rejects every send).
        final String configurationSetName = this.configString(configuration, "aws-ses-configuration-set");

        return new SesEmailSender(Region.of(regionName), fromAddress, configurationSetName);
    }

    /**
     * Resolves a {@link SmtpEmailSender} from {@code configuration.json}'s {@code
     * "smtp-host"}/{@code "smtp-port"}/{@code "smtp-username"}/{@code "smtp-password"}/{@code
     * "smtp-from-address"} keys - {@code null} (SMTP not configured, including on a {@code
     * configuration.json} that predates this feature) if {@code "smtp-host"} is missing/blank or
     * any of the other four keys is missing. Every key is read via {@link
     * #configString(JsonDocument, String)}/checked via {@code contains} first, rather than calling
     * {@code JsonDocument#getString}/{@code #getInteger} directly - both throw a bare {@link
     * NullPointerException} on a missing key, which previously crashed this whole extension (not
     * just registration) the moment {@code smtp-host} was configured without also setting {@code
     * smtp-port} or one of the other three keys.
     *
     * @param configuration this deployment's loaded {@code configuration.json}
     * @return a configured {@link SmtpEmailSender}, or {@code null} if SMTP isn't fully configured
     */
    private EmailSender resolveSmtpEmailSender(final JsonDocument configuration) {
        final String host = this.configString(configuration, "smtp-host");

        if (host.isBlank()) {
            return null;
        }

        if (!configuration.contains("smtp-port")) {
            this.getLogger().warning(
                    "@CloudRestExtension.resolveSmtpEmailSender: 'smtp-host' is set but 'smtp-port' is missing "
                            + "from configuration.json - falling back to logging instead of SMTP.");
            return null;
        }

        final String username = this.configString(configuration, "smtp-username");
        final String password = this.configString(configuration, "smtp-password");
        final String fromAddress = this.configString(configuration, "smtp-from-address");

        if (username.isBlank() || password.isBlank() || fromAddress.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.resolveSmtpEmailSender: 'smtp-host' is set but 'smtp-username'/"
                            + "'smtp-password'/'smtp-from-address' is missing or blank in configuration.json - "
                            + "falling back to logging instead of SMTP.");
            return null;
        }

        return new SmtpEmailSender(host, configuration.getInteger("smtp-port"), username, password, fromAddress);
    }

    /**
     * Resolves an optional {@link S3PresignedTransferService} from {@code configuration.json}'s
     * {@code "aws-s3-region"}/{@code "aws-s3-bucket"}/{@code "aws-s3-key-prefix"} keys - the exact
     * same keys {@code CloudBootstrap.resolveObjectStorageService} reads for {@code
     * ObjectStorageService} itself. Necessarily a separate, duplicated read: this extension and
     * {@code cloud-driver-bootstrap} share no common ancestor either could read the value from
     * once and hand down, the same "duplicated optional-key read" precedent {@code
     * CloudUserService}/{@code TrashPurgeScheduler}'s own {@code "trash-retention-days"} reads
     * already established. A missing/blank {@code "aws-s3-bucket"} (including on a {@code
     * configuration.json} that predates this feature) returns {@code null} - presigned
     * direct-to-client transfer stays disabled, {@code CloudUserService}'s three
     * {@code beginPresignedUpload}/{@code completePresignedUpload}/{@code beginPresignedDownload}
     * methods all throw {@code PresignedTransferUnavailableException}, and every client falls back
     * to the ordinary server-mediated upload/download routes.
     *
     * @param configuration this deployment's loaded {@code configuration.json}
     * @return a configured {@link S3PresignedTransferService}, or {@code null} if not configured
     */
    private PresignedTransferService resolvePresignedTransferService(final JsonDocument configuration) {
        final String bucket = this.configString(configuration, "aws-s3-bucket");
        if (bucket.isBlank()) {
            return null;
        }
        final String regionName = this.configString(configuration, "aws-s3-region");
        if (regionName.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.resolvePresignedTransferService: 'aws-s3-bucket' is set but 'aws-s3-region' is "
                            + "missing/blank in configuration.json - presigned direct-to-client transfer stays disabled.");
            return null;
        }
        final String keyPrefix = this.configString(configuration, "aws-s3-key-prefix");
        return new S3PresignedTransferService(Region.of(regionName), bucket, keyPrefix);
    }

    /**
     * Reads {@code key} from {@code configuration} as a {@code String}, or {@code ""} if the key
     * is absent - {@link JsonDocument#getString} throws a bare {@link NullPointerException} on a
     * missing key instead, so every caller that treats a key as optional must guard it with
     * {@link JsonDocument#contains} first; this centralizes that guard.
     *
     * @param configuration the document to read from
     * @param key the key to look up
     * @return the key's value, or {@code ""} if absent
     */
    private String configString(final JsonDocument configuration, final String key) {
        return configuration.contains(key) ? configuration.getString(key) : "";
    }

}
