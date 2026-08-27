package de.lino.cloud.extensions.web;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;

import java.util.logging.Level;

/**
 * Hosts {@link AuthPanelServer} - the static self-registration/login browser panel, meant to be
 * reverse-proxied under its own subdomain (e.g. {@code auth.cloud-driver.de}, separate from
 * {@code cloud-driver-extensions-rest}'s {@code cloud-driver.de}). See {@code CLAUDE.md}'s
 * "cloud-driver-extensions-web" section for the full picture.
 */
public class CloudWebExtension extends Extension {

    /**
     * Default bind interface if {@code "web-panel-bind-host"} isn't set in
     * {@code configuration.json} - every interface, matching {@code cloud-driver-extensions-rest}'s
     * own default. A production deployment fronted by a TLS-terminating reverse proxy (see
     * {@code shell/Caddyfile}) should set that config key to {@code "127.0.0.1"} instead, so
     * Javalin's plain-HTTP listener is only reachable from the proxy running on the same
     * machine, never directly from the internet.
     */
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";

    private volatile AuthPanelServer authPanelServer;
    private volatile int boundPort = -1;

    @Override
    public void onLoading() {
        this.startAuthPanel();
    }

    /**
     * @param args unused
     */
    @Override
    public void onRunning(final String[] args) {

        if (this.authPanelServer != null) {
            this.cloudDriver().getTerminal().displayApproved("Auth panel connection &bopened &7and listening on port &b&l%s", this.boundPort);
        }

    }

    /**
     * @param reason unused
     */
    @Override
    public void onException(final RuntimeException reason) {

        this.stopAuthPanel();
        this.cloudDriver().getLogger().severe("An error occurred while trying to start the cloud web extension.");
        this.cloudDriver().getLogger().log(Level.SEVERE, reason.getMessage(), reason);

    }

    @Override
    public void onEnding() {
        this.stopAuthPanel();
    }

    private void startAuthPanel() {

        final String signingKey = this.cloudDriver().getConfiguration().getString("jwt-signing-key");

        if (signingKey.isBlank()) {
            this.getLogger().warning(
                    "@CloudWebExtension.startAuthPanel: 'jwt-signing-key' is not set in configuration.json - "
                            + "the auth panel will not be started. Generate one via: openssl rand -base64 32");
            return;
        }

        final String configuredBindHost = this.cloudDriver().getConfiguration().getString("web-panel-bind-host");
        final String bindHost = configuredBindHost.isBlank() ? DEFAULT_BIND_HOST : configuredBindHost;

        final int port = this.cloudDriver().getConfiguration().getInteger("web-panel-port");

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final JwtSigner jwtSigner = new JjwtSigner(signingKey);
        final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner);

        this.authPanelServer = new AuthPanelServer(dataFactory, authService);
        this.authPanelServer.start(bindHost, port);
        this.boundPort = port;

    }

    private void stopAuthPanel() {

        final AuthPanelServer runningServer = this.authPanelServer;

        if (runningServer == null) {
            return;
        }

        runningServer.stop();
        this.cloudDriver().getLogger().info("Auth panel connection successfully &cclosed&7.");

    }

}
