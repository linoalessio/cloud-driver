package de.lino.cloud.api.utility;

import de.lino.cloud.api.CloudDriver;
import lombok.NonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.Supplier;

/**
 * Shared null-validation helpers for parameters and shared state across {@code cloud-driver}.
 * Each overload returns {@code object} unchanged, so a check can be inlined at the point of use.
 */
public final class Asserts {

    /** Not instantiable; all functionality is exposed through static methods. */
    private Asserts() {}

    /**
     * Returns {@code object} if it is not {@code null}.
     *
     * @param object the value to check
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T requireNonNull(final T object) {
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
    public static <T> T requireNonNull(final T object, final String message) {
        if (object == null) throw new NullPointerException(message);
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. {@code message} is
     * only evaluated when {@code object} is {@code null}, so use this
     * overload over {@link #requireNonNull(Object, String)} when building the
     * message is expensive.
     *
     * @param object the value to check
     * @param message supplies the message the thrown exception carries if {@code object} is {@code null}
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T requireNonNull(final T object, final Supplier<String> message) {
        if (object == null) throw new NullPointerException(message.get());
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. Dedicated overload for {@link
     * CloudDriver}, so a missing installation fails with a message pointing at {@code
     * DefaultCloudDriver.setInstance}.
     *
     * @param object the {@link CloudDriver} instance to check, e.g. {@code CloudDriver.getInstance()}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static CloudDriver requireNonNull(final CloudDriver object) {

        if (object == null) {
            throw new NullPointerException(
                    "@Asserts.assertNotNull: no CloudDriver implementation is installed yet - install one "
                            + "(e.g. via DefaultCloudDriver.setInstance) before using it"
            );
        }

        return object;
    }

    /**
     * Runs {@code action} once and prints its CPU time (calling thread only), heap memory
     * delta, and wall-clock time to standard output - a quick spot-check, not a substitute
     * for a real benchmarking harness (no warm-up, no forced GC).
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
