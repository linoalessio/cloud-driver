package de.lino.cloud.api.factory.service;

import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.push.LiveUpdatePublisher;
import de.lino.cloud.api.user.ICloudUserService;
import lombok.NonNull;

/**
 * Bundles higher-level, cross-cutting services built on top of the raw
 * persistence facets in {@link de.lino.cloud.api.factory.container.IFactoryContainer} -
 * currently just the {@link ICloudUserService} used to scope end-user file
 * ownership over the REST API, and the {@link IAuthService} that verifies
 * logins/issues JWTs.
 *
 * <p>Unlike every other facet on {@link de.lino.cloud.api.CloudDriver}, these two
 * are not necessarily available the moment {@code CloudDriver} itself is
 * constructed: both are only ever built once the JWT-authenticated {@code RestFactory}
 * is - which happens later, inside {@code cloud-driver-extensions-rest}'s
 * {@code CloudRestExtension}, and only if a {@code "jwt-signing-key"} is configured
 * at all (see {@code CloudRestExtension#startRestApi}). {@link #setCloudUserService}/
 * {@link #setAuthService} are how that extension publishes its real instances back
 * here once they exist; {@link #getCloudUserService()}/{@link #getAuthService()}
 * return {@code null} until then - a caller reached before/without that extension
 * (e.g. a {@code Command} that can run concurrently with, or without ever depending
 * on, {@code cloud-driver-rest}) must handle that case rather than assume non-null.
 */
public interface IServiceContainer {

    /**
     * Returns the end-user file-ownership service, or {@code null} if {@code
     * CloudRestExtension} hasn't published one yet (not started, or the REST API
     * is disabled for this deployment).
     *
     * @return the {@link ICloudUserService}, or {@code null}
     */
    ICloudUserService getCloudUserService();

    /**
     * Publishes the real {@link ICloudUserService}, once built.
     *
     * @param cloudUserService the instance backing the JWT-authenticated REST API's {@code /files} routes
     */
    void setCloudUserService(@NonNull ICloudUserService cloudUserService);

    /**
     * Returns the login/JWT service, or {@code null} if {@code CloudRestExtension}
     * hasn't published one yet (not started, or the REST API is disabled for this
     * deployment).
     *
     * @return the {@link IAuthService}, or {@code null}
     */
    IAuthService getAuthService();

    /**
     * Publishes the real {@link IAuthService}, once built.
     *
     * @param authService the instance backing the JWT-authenticated REST API's login/registration routes
     */
    void setAuthService(@NonNull IAuthService authService);

    /**
     * Returns the live-update push transport (item 10, live push via WebSocket - see {@code
     * architecture/SERVICES.md}), or {@code null} if {@code CloudRestExtension} hasn't published
     * one yet (not started, the REST API is disabled for this deployment, or this deployment's
     * {@code cloud-driver-plugin} version predates this feature). {@link
     * de.lino.cloud.api.event.database.DatabaseWatchEvent#handle} must null-check this the same
     * way it already does for {@link #getCloudUserService()}.
     *
     * @return the {@link LiveUpdatePublisher}, or {@code null}
     */
    LiveUpdatePublisher getLiveUpdatePublisher();

    /**
     * Publishes the real {@link LiveUpdatePublisher}, once the WebSocket-backed {@code
     * RestFactory} it forwards to is actually running.
     *
     * @param liveUpdatePublisher the instance backing the JWT-authenticated REST API's WebSocket push route
     */
    void setLiveUpdatePublisher(@NonNull LiveUpdatePublisher liveUpdatePublisher);

}
