package de.lino.cloud.api.utility;

import de.lino.cloud.api.CloudAPI;
import lombok.NonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;

/**
 * Shared null-validation helpers for parameters and shared state across
 * {@code cloud-driver} - the codebase's alternative to scattering {@code
 * Objects.requireNonNull} calls throughout. Each {@code assertNotNull}
 * overload returns {@code object} unchanged so a check can be inlined at the
 * point of use (e.g. {@code this.field = Asserts.assertNotNull(param, "...")}).
 */
public final class Asserts {

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private Asserts() {}

    /**
     * Returns {@code object} if it is not {@code null}.
     *
     * @param object the value to check
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object) {
        if (object == null) throw new NullPointerException();
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}.
     *
     * @param object the value to check
     * @param message the message the thrown exception carries if {@code object} is {@code null}
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object, final String message) {
        if (object == null) throw new NullPointerException(message);
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. {@code message} is
     * only evaluated when {@code object} is {@code null}, so use this
     * overload over {@link #assertNotNull(Object, String)} when building the
     * message is expensive.
     *
     * @param object the value to check
     * @param message supplies the message the thrown exception carries if {@code object} is {@code null}
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object, final Supplier<String> message) {
        if (object == null) throw new NullPointerException(message.get());
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. A dedicated overload
     * for {@link CloudAPI} - typically wrapping {@link CloudAPI#getInstance()}
     * - so a missing installation fails with a message pointing at {@code
     * DefaultCloudAPI.setInstance} instead of a bare {@link
     * NullPointerException}.
     *
     * @param object the {@link CloudAPI} instance to check, e.g. {@code CloudAPI.getInstance()}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}, i.e. no {@link CloudAPI} implementation has been installed yet
     */
    public static CloudAPI assertNotNull(final CloudAPI object) {

        if (object == null) {
            throw new NullPointerException(
                    "@Asserts.assertNotNull: no CloudAPI implementation is installed yet - install one "
                            + "(e.g. via DefaultCloudAPI.setInstance) before using it"
            );
        }

        return object;
    }

    /**
     * Runs {@code action} once and prints the CPU time, memory used, and
     * wall-clock ("system") time it took, to standard output - a quick,
     * dependency-free way to spot-check where a {@link Runnable}'s cost
     * actually goes without pulling in a benchmarking harness.
     *
     * <p>CPU time is measured via {@link
     * ThreadMXBean#getCurrentThreadCpuTime()} for the calling thread only -
     * if {@code action} hands work off to other threads, their CPU time is
     * not included, and is reported as unsupported on a JVM that does not
     * expose per-thread CPU time at all. Memory is the JVM heap's
     * used-memory delta ({@code totalMemory() - freeMemory()}); no GC is
     * forced before measuring, so a collection running during {@code
     * action} can make the reported delta an over- or under-estimate, same
     * as with any measurement that does not force one. Wall-clock time is
     * measured via {@link System#currentTimeMillis()}, i.e. real elapsed
     * time regardless of how many threads {@code action} used.
     *
     * @param action the action to time
     * @throws NullPointerException if {@code action} is {@code null}
     */
    public static void runWallTimeTest(@NonNull final Runnable action) {

        final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        if (threadMXBean.isThreadCpuTimeSupported() && !threadMXBean.isThreadCpuTimeEnabled()) threadMXBean.setThreadCpuTimeEnabled(true);
        final boolean cpuTimeSupported = threadMXBean.isThreadCpuTimeSupported();

        final long cpuProcessStartTime = cpuTimeSupported ? threadMXBean.getCurrentThreadCpuTime() : -1;
        final long memProcessStartTime = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final long systemStartTime = System.currentTimeMillis();

        action.run();

        final long cpuProcessEndTime = cpuTimeSupported ? threadMXBean.getCurrentThreadCpuTime() : -1;
        final long memProcessEndTime = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final long systemEndTime = System.currentTimeMillis();

        final String[] results = {
                "CPU-process-time: " + (cpuTimeSupported ? Constraints.resolveMilliSecondsToUnit((cpuProcessEndTime - cpuProcessStartTime) / 1_000_000) : "unsupported on this JVM")
                , "Mem-process-time: " + Constraints.resolveBytesToUnit(memProcessEndTime - memProcessStartTime)
                , "System-process-time: " + Constraints.resolveMilliSecondsToUnit(systemEndTime - systemStartTime)
        };

        System.out.println(" ");
        for (final String result : results) System.out.println("@Asserts.runWallTimeTest: " + result);
        System.out.println(" ");
    }

}
