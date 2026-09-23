package de.lino.cloud.api.utility.task;

import de.lino.cloud.api.CloudDriver;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps a periodic task body in a failure boundary. A {@link
 * java.util.concurrent.ScheduledExecutorService} cancels a repeating task for good the first time
 * its body throws, so an unguarded tick means one failure silently ends that job for the rest of
 * the process's life, with no log and no way to restart it (a scheduler's own {@code
 * scheduledFuture} field stays set, making a later {@code start} a no-op). Every repeating task
 * this codebase schedules therefore runs through {@link #guard(String, Runnable)}.
 *
 * <p>Scoped to repeating scheduled tasks deliberately - it is not a general-purpose try/catch
 * utility, and a one-shot task has nothing to keep alive.
 */
public final class SchedulerTickGuard {

    /** Used when no {@link CloudDriver} is installed yet - a standalone sample, or a tick that fires during early boot. */
    private static final Logger FALLBACK_LOGGER = Logger.getLogger(SchedulerTickGuard.class.getName());

    /** Not instantiable - a pure namespace for {@link #guard(String, Runnable)}. */
    private SchedulerTickGuard() {
    }

    /**
     * Wraps {@code tick} so that nothing it throws can reach the scheduling executor.
     *
     * @param taskName the scheduler's own name, used to identify the failing job in the log
     * @param tick the task body to run on every tick
     * @return a {@link Runnable} that runs {@code tick} and logs, rather than propagates, any failure
     * @throws NullPointerException if {@code taskName} or {@code tick} is {@code null}
     */
    @NotNull
    public static Runnable guard(@NonNull final String taskName, @NonNull final Runnable tick) {
        return () -> {
            try {
                tick.run();
            } catch (final Throwable tickFailed) {
                // Throwable, not RuntimeException: keeping the schedule alive is the entire
                // purpose of this boundary, and an Error escaping here would cancel it just as
                // permanently as an exception. An Error is logged at SEVERE, because the process
                // may be poisoned even though this job carries on.
                logger().log(tickFailed instanceof Error ? Level.SEVERE : Level.WARNING,
                        "Scheduled task '" + taskName + "' failed this tick - the schedule stays active and will run again",
                        tickFailed);
            }
        };
    }

    /**
     * @return the process-wide logger, or {@link #FALLBACK_LOGGER} if no {@link CloudDriver} is installed
     */
    @NotNull
    private static Logger logger() {
        try {
            return CloudDriver.getInstance().getLogger();
        } catch (final Throwable cloudDriverUnavailable) {
            return FALLBACK_LOGGER;
        }
    }

}
