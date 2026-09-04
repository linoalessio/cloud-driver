package de.lino.cloud.extensions.rest;

import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.audit.AuditLogServiceImpl;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.auth.mail.LoggingEmailSender;
import de.lino.cloud.auth.mail.SmtpEmailSender;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.database.json.JsonDocument;

import java.util.logging.Level;

/**
 * Hosts the JWT-authenticated {@code RestFactory} - the actual place {@code RestFactory#start}
 * is called from in this repo (not {@code CloudBootstrap}, despite what older comments/docs
 * elsewhere may still say). See {@code CLAUDE.md}'s "RestFactory"/"JWT authentication for
 * end-user clients" sections for the full picture.
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

        this.cloudDriver().getTerminal().displayApproved("&dRest endpoint &bopened &7and listening on port &b&l%s", REST_SERVER_PORT);

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

        if (REST_FACTORY != null) REST_FACTORY.stop();
        this.cloudDriver().getTerminal().displayApproved("&dRest server &7connection successfully &cclosed&7.");

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
        // Item 11 (audit log, see architecture/SERVICES.md): built here, not in cloud-driver-auth
        // itself, since redacting AuditEvent#getMetadata() needs SecretRedactor
        // (cloud-driver-plugin) - a dependency cloud-driver-auth must never take on directly (see
        // CLAUDE.md's "Module layout and dependency direction"). This extension already depends on
        // both modules, so it's the natural place to close that gap via constructor injection.
        final AuditLogService auditLogService = new AuditLogServiceImpl(dataFactory, SecretRedactor::redact);
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory, auditLogService);
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

        final DefaultRestFactory restFactory = new DefaultRestFactory(dataFactory, authService, cloudUserService);
        REST_FACTORY = restFactory;

        // Item 10 (live push via WebSocket, see architecture/SERVICES.md): DefaultRestFactory
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
        // ever created via CreateUserCli (see CLAUDE.md's "JWT authentication" section - deliberately
        // not a public self-registration endpoint); StoredFile uploads/reads/deletes go exclusively
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
     * through, from {@code configuration.json}'s {@code "smtp-host"}/{@code "smtp-port"}/{@code
     * "smtp-username"}/{@code "smtp-password"}/{@code "smtp-from-address"} keys. If {@code
     * "smtp-host"} is blank or absent - including on a deployment whose {@code configuration.json}
     * predates this feature and simply doesn't have the key yet - or if any of the other four
     * keys is missing, logs a warning and falls back to a {@link LoggingEmailSender} instead of
     * failing to start the REST API entirely: every other subsystem, and every route except
     * registration, works the same either way, and a fallback that only logs the code is enough
     * to keep local development/testing working without a real mail server. Every key is read via
     * {@link #configString(JsonDocument, String)}/checked via {@code contains} first, rather than
     * calling {@code JsonDocument#getString}/{@code #getInteger} directly - both throw a bare
     * {@link NullPointerException} on a missing key, which previously crashed this whole extension
     * (not just registration) the moment {@code smtp-host} was configured without also setting
     * {@code smtp-port} or one of the other three keys.
     *
     * @return a {@link SmtpEmailSender} if SMTP is fully configured, a {@link LoggingEmailSender} otherwise
     */
    private EmailSender buildEmailSender() {

        final JsonDocument configuration = this.cloudDriver().getConfiguration();
        final String host = this.configString(configuration, "smtp-host");

        if (host.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.buildEmailSender: 'smtp-host' is not set in configuration.json - "
                            + "verification codes will only be logged, not actually e-mailed. Not suitable for production.");
            return new LoggingEmailSender(this.getLogger());
        }

        if (!configuration.contains("smtp-port")) {
            this.getLogger().warning(
                    "@CloudRestExtension.buildEmailSender: 'smtp-host' is set but 'smtp-port' is missing from "
                            + "configuration.json - verification codes will only be logged, not actually e-mailed.");
            return new LoggingEmailSender(this.getLogger());
        }

        final String username = this.configString(configuration, "smtp-username");
        final String password = this.configString(configuration, "smtp-password");
        final String fromAddress = this.configString(configuration, "smtp-from-address");

        if (username.isBlank() || password.isBlank() || fromAddress.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.buildEmailSender: 'smtp-host' is set but 'smtp-username'/'smtp-password'/"
                            + "'smtp-from-address' is missing or blank in configuration.json - verification codes "
                            + "will only be logged, not actually e-mailed.");
            return new LoggingEmailSender(this.getLogger());
        }

        return new SmtpEmailSender(host, configuration.getInteger("smtp-port"), username, password, fromAddress);
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
