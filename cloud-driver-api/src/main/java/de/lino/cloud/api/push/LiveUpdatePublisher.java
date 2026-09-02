package de.lino.cloud.api.push;

import de.lino.cloud.api.event.database.DatabaseWatchEvent;
import de.lino.cloud.api.factory.service.IServiceContainer;
import lombok.NonNull;

/**
 * Item 10 (Live push via WebSocket/SSE for change notifications, {@code architecture/SERVICES.md}) -
 * the vendor-agnostic contract {@link DatabaseWatchEvent#handle} pushes a change notification
 * through, once one has actually been published into {@link IServiceContainer}. Lives in
 * {@code cloud-driver-api} (not {@code cloud-driver-plugin}) for the same reason {@link
 * de.lino.cloud.api.security.keys.KeyEncryptionService}/{@link de.lino.cloud.api.security.password.PasswordHasher}
 * do: {@code DatabaseWatchEvent} runs inside {@code cloud-driver-api}'s own {@code event} package
 * and cannot depend on {@code cloud-driver-plugin} (the actual WebSocket transport, backed by
 * Javalin, lives in that module's {@code DefaultRestFactory} - see {@code CLAUDE.md}'s "Postgres
 * change notifications" section) - or Javalin at all.
 *
 * <p>{@code CloudRestExtension} (mirroring how it already publishes {@code AuthService}/{@code
 * CloudUserService}) publishes the real, Javalin-backed implementation into {@link
 * IServiceContainer#setLiveUpdatePublisher} once the JWT-authenticated REST API - and therefore
 * its WebSocket route - is actually running; until then {@link IServiceContainer#getLiveUpdatePublisher()}
 * returns {@code null} and {@link DatabaseWatchEvent#handle} simply skips the push, exactly the
 * same "may not exist yet, null-check rather than assume" contract {@code getCloudUserService()}/
 * {@code getAuthService()} already carry.
 */
public interface LiveUpdatePublisher {

    /**
     * Pushes one small change notification to every session currently connected under {@code
     * authUserId}, if any - a no-op if nobody belonging to that account is currently connected.
     * Mirrors {@link DatabaseWatchEvent}'s own notification payload shape ({@code
     * "table"}/{@code "operation"}/{@code "id"}) rather than the changed entity's own (still
     * encrypted) data, matching that class's own "never the row's own data" contract.
     *
     * <p><b>Must never throw.</b> A broken/closed client connection or any other transport
     * failure is this method's own concern to catch and swallow (or log) internally - {@link
     * DatabaseWatchEvent#handle} calls this from inside a Postgres {@code LISTEN}/{@code NOTIFY}-driven
     * listener thread that has no tolerance for an uncaught exception (see {@code
     * CloudWatcherExtension}'s own {@code dispatch}-callback try/catch for why), and defends
     * against this contract being violated anyway - but an implementation should not rely on that
     * outer defense as its only safety net.
     *
     * @param authUserId the account whose connected session(s), if any, should receive this push
     * @param table the changed entity's table name
     * @param operation the database operation that triggered this notification (e.g. {@code "INSERT"}/{@code "UPDATE"})
     * @param id the changed entity's own id
     */
    void publish(@NonNull String authUserId, @NonNull String table, @NonNull String operation, @NonNull String id);

}
