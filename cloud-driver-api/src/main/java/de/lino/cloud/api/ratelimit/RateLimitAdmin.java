package de.lino.cloud.api.ratelimit;

import de.lino.cloud.api.factory.service.IServiceContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Operator-facing control over the running REST layer's rate limiters, reached via {@link
 * IServiceContainer#getRateLimitAdmin()} - {@code null} until {@code
 * cloud-driver-extensions-rest}'s {@code CloudRestExtension} has published one, the same "may not
 * exist yet" contract every other {@link IServiceContainer} facet carries.
 *
 * <h2>Why this exists as its own published facet</h2>
 *
 * The rate-limit state that matters lives on the <b>JWT-gated</b> {@code DefaultRestFactory}
 * instance {@code CloudRestExtension} builds - <b>not</b> the unauthenticated one reachable via
 * {@code IFactoryContainer#getRestFactory()}, which mounts no auth routes and therefore has no
 * meaningful limiter state at all. Publishing a narrow admin contract here is the same shape
 * {@code DefaultRestFactory} already uses to make itself reachable as a {@code
 * LiveUpdatePublisher}, and is deliberately preferred over exposing the whole factory or making
 * the bucket maps {@code static}.
 *
 * <h2>The problem it solves</h2>
 *
 * Before this facet existed there was no way whatsoever to clear an exhausted rate-limit window
 * short of waiting it out or restarting the process - which is exactly how a real user ended up
 * locked out of login after ordinary Dashboard use consumed the shared {@code /auth/*} budget.
 * Restarting a production server to unblock one account is not a proportionate remedy.
 *
 * <p><b>Resets are best-effort and cover both backing stores.</b> Counters live in Redis when this
 * deployment has configured it and in an in-process map otherwise (and in both, transiently, when
 * Redis fails mid-request) - an implementation must clear whichever it can reach and report how
 * many entries it actually removed, rather than claiming success it cannot verify.
 */
public interface RateLimitAdmin {

    /**
     * A point-in-time count of currently-tracked rate-limit windows.
     *
     * @param authWindows in-process {@code /auth/*} windows currently held
     * @param apiReadWindows in-process general {@code READ} windows currently held
     * @param redisBacked whether counting currently runs through Redis (so the in-process numbers
     * above are a fallback residue rather than the live counters)
     */
    record RateLimitStatus(int authWindows, int apiReadWindows, boolean redisBacked) {
    }

    /**
     * @return how many rate-limit windows are currently tracked, and which backing store is live
     */
    @NotNull
    RateLimitStatus status();

    /**
     * Clears rate-limit windows, so the affected callers start from a fresh budget immediately.
     *
     * @param identity the specific caller to clear - an account id, or an IP address - or {@code
     * null} to clear <b>every</b> tracked window. An identity that is not currently tracked is not
     * an error; it simply clears nothing.
     * @return the number of windows actually removed
     */
    int reset(@Nullable String identity);

}
