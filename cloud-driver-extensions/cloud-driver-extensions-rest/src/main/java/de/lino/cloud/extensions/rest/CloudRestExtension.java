package de.lino.cloud.extensions.rest;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.factory.RestFactory;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.CloudUser;
import de.lino.cloud.auth.CloudUserService;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.plugin.factory.DefaultRestFactory;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;

/**
 * Hosts the JWT-authenticated {@code RestFactory} - the actual place {@code RestFactory#start}
 * is called from in this repo (not {@code CloudBootstrap}, despite what older comments/docs
 * elsewhere may still say). See {@code CLAUDE.md}'s "RestFactory"/"JWT authentication for
 * end-user clients" sections for the full picture.
 */
public class CloudRestExtension extends Extension {

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

    private static volatile RestFactory REST_FACTORY;

    /** Prints a diagnostic message; no real loading behavior yet. */
    @Override
    public void onLoading() {

        REST_SERVER_PORT = this.cloudDriver().getConfiguration().getInteger("rest-server-port");
        this.startRestApi();

    }

    /**
     * Prints a diagnostic message; no real running behavior yet.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

        this.cloudDriver().getTerminal().displayApproved("Rest server connection &bopened &7and listening on port &b&l%s", REST_SERVER_PORT);

    }

    /**
     * No-op.
     *
     * @param reason unused
     */
    @Override
    public void onException(RuntimeException reason) {
        REST_FACTORY.stop();
    }

        /** No-op. */
    @Override
    public void onEnding() {

        REST_FACTORY.stop();
        this.cloudDriver().getTerminal().displayApproved("Rest server connection successfully &cclosed&7.");

    }

    private void startRestApi() {

        final String signingKey = this.cloudDriver().getConfiguration().getString("jwt-signing-key");

        if (signingKey.isBlank()) {
            this.getLogger().warning(
                    "@CloudRestExtension.startRestApi: 'jwt-signing-key' is not set in configuration.json - "
                            + "the JWT-authenticated REST API will not be started. Generate one via: openssl rand -base64 32");
            return;
        }

        final String configuredBindHost = this.cloudDriver().getConfiguration().getString("rest-api-bind-host");
        final String bindHost = configuredBindHost.isBlank() ? DEFAULT_BIND_HOST : configuredBindHost;

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        final FileFactory fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();
        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final JwtSigner jwtSigner = new JjwtSigner(signingKey);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner);
        final CloudUserService cloudUserService = new CloudUserService(dataFactory, fileFactory);

        REST_FACTORY = new DefaultRestFactory(dataFactory, authService, cloudUserService);

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

}
