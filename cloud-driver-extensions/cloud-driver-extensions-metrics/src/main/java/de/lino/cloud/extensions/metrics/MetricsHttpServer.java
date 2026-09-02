package de.lino.cloud.extensions.metrics;

import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.jetbrains.annotations.NotNull;

/**
 * A small, standalone Javalin instance exposing {@code GET /metrics} - the Prometheus text-format
 * scrape of {@link CloudMetricsExtension}'s {@link PrometheusMeterRegistry}.
 *
 * <p><b>A dedicated instance, not a route mounted on the existing JWT-authenticated {@code
 * RestFactory}.</b> A Prometheus scraper is not a logged-in end user - reusing {@code
 * DefaultRestFactory}'s bearer-token-gated instance would mean either exempting {@code /metrics}
 * from every auth filter on that instance (fragile - one misordered filter away from either
 * leaking metrics or, worse, breaking real auth) or handing a scraper a real account/token for no
 * reason. A separate instance, on its own configurable port, keeps that boundary structural
 * instead of filter-order-dependent, at the cost of one extra listening port - the same trade-off
 * this codebase already makes for {@code DefaultRestFactory(DataFactory, ApiKey)} vs. {@code
 * DefaultRestFactory(DataFactory, AuthService)} being two separate instances rather than one
 * instance serving two auth schemes.
 *
 * <p><b>Deliberately unauthenticated.</b> Per {@code architecture/SERVICES.md} item 13's own
 * prompt, this endpoint should be reached by a scrape job, not a person - restrict who can reach it
 * via network placement instead (bind host, firewall, reverse-proxy IP allowlist - see {@link
 * CloudMetricsExtension}'s own Javadoc for the config keys controlling bind host/port). This is a
 * real decision worth revisiting with Lino if this deployment's threat model changes; not
 * re-litigated here beyond flagging it, matching {@code CLAUDE.md}'s existing "flag, don't decide
 * silently" convention for security-relevant trade-offs (e.g. the AWS KMS {@code
 * KeyEncryptionService} not being wired in as {@code CloudBootstrap}'s default).
 */
final class MetricsHttpServer {

    /** Prometheus' own conventional content type for the text exposition format this endpoint serves. */
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry registry;
    private Javalin app;

    /**
     * @param registry the registry {@code GET /metrics} scrapes on every request - not copied, so a
     *     meter registered against it after this constructor runs still shows up on the next scrape
     */
    MetricsHttpServer(@NotNull final PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Starts listening on {@code host}:{@code port}.
     *
     * @throws IllegalStateException if already started
     */
    void start(@NotNull final String host, final int port) {
        if (this.app != null) {
            throw new IllegalStateException("@MetricsHttpServer.start: already started");
        }

        this.app = Javalin.create(config ->
                config.routes.get("/metrics", ctx -> ctx.contentType(PROMETHEUS_CONTENT_TYPE).result(this.registry.scrape()))
        );
        this.app.start(host, port);
    }

    /** Stops listening, if started - a no-op otherwise. */
    void stop() {
        if (this.app != null) this.app.stop();
    }

}
