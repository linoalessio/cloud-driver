package de.lino.cloud.api.utility.task;

import java.util.Collection;
import java.util.List;
import de.lino.cloud.api.utility.Asserts;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Singleton multitasking facility built around one process-wide {@link ExecutorService} that
 * runs each submitted task on its own virtual thread ({@link
 * Executors#newVirtualThreadPerTaskExecutor()}). Obtain the shared instance via
 * {@link #getInstance()}.
 */
public final class MultiTaskingFactory {

    /** Shared {@link ExecutorService} backing every task submitted through this class. */
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newVirtualThreadPerTaskExecutor();

    /** Private constructor; instances are only ever created by {@link InstanceHolder}. */
    private MultiTaskingFactory() {
    }

    /** Lazily-initialized singleton holder (initialization-on-demand holder idiom). */
    private static final class InstanceHolder {
        private static final MultiTaskingFactory INSTANCE = new MultiTaskingFactory();
    }

    /**
     * Submits a {@link Callable} for asynchronous execution.
     *
     * @param callable the task to run
     * @param <T> the type of the task's result
     * @return a {@link Future} representing pending completion of {@code callable}
     * @throws NullPointerException if {@code callable} is {@code null}
     */
    public <T> Future<T> submitTaskAsync(final Callable<T> callable) {
        return EXECUTOR_SERVICE.submit(
                Asserts.requireNonNull(callable, "@MultiTaskingFactory.submitTaskAsync: Callable cannot be null")
        );
    }

    /**
     * Submits a {@link Runnable} for asynchronous execution, completing the returned
     * {@link Future} with the given fixed result once the task finishes.
     *
     * @param task the task to run
     * @param result the value to complete the returned future with
     * @param <T> the type of {@code result}
     * @return a {@link Future} that yields {@code result} once {@code task} completes
     * @throws NullPointerException if {@code task} or {@code result} is {@code null}
     */
    public <T> Future<T> submitTaskAsync(final Runnable task, final T result) {
        return EXECUTOR_SERVICE.submit(
                Asserts.requireNonNull(task, "@MultiTaskingFactory.submitTaskAsync: Runnable cannot be null"),
                Asserts.requireNonNull(result, "@MultiTaskingFactory.submitTaskAsync: Result cannot be null")
        );
    }

    /**
     * Submits a {@link Runnable} for asynchronous execution.
     *
     * @param runnable the task to run
     * @return a {@link Future} representing pending completion of {@code runnable}
     * @throws NullPointerException if {@code runnable} is {@code null}
     */
    public Future<?> submitTaskAsync(final Runnable runnable) {
        return EXECUTOR_SERVICE.submit(
                Asserts.requireNonNull(runnable, "@MultiTaskingFactory.submitTaskAsync: Runnable cannot be null")
        );
    }

    /**
     * Submits a batch of {@link Callable} tasks at once, scheduled concurrently, and waits
     * for all of them to finish, successfully or not.
     *
     * @param tasks the tasks to run
     * @param <T> the type of each task's result
     * @return the completed {@link Future futures}, in the same iteration order as {@code tasks}
     * @throws NullPointerException if {@code tasks} is {@code null}
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public <T> List<Future<T>> submitTasksAsync(final Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return EXECUTOR_SERVICE.invokeAll(
                Asserts.requireNonNull(tasks, "@MultiTaskingFactory.submitTasksAsync: Tasks cannot be null")
        );
    }

    /**
     * Runs a {@link Runnable} asynchronously and returns a {@link CompletableFuture}
     * that completes once it finishes, allowing further stages to be composed on it.
     *
     * @param task the task to run
     * @return a {@link CompletableFuture} completing when {@code task} finishes
     * @throws NullPointerException if {@code task} is {@code null}
     */
    public CompletableFuture<Void> runAsync(final Runnable task) {
        return CompletableFuture.runAsync(
                Asserts.requireNonNull(task, "@MultiTaskingFactory.runAsync: Runnable cannot be null"),
                EXECUTOR_SERVICE
        );
    }

    /**
     * Runs a {@link Supplier} asynchronously and returns a {@link CompletableFuture}
     * completed with its result, allowing further stages to be composed on it.
     *
     * @param supplier the task to run
     * @param <T> the type of the task's result
     * @return a {@link CompletableFuture} completed with {@code supplier}'s result
     * @throws NullPointerException if {@code supplier} is {@code null}
     */
    public <T> CompletableFuture<T> supplyAsync(final Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(
                Asserts.requireNonNull(supplier, "@MultiTaskingFactory.supplyAsync: Supplier cannot be null"),
                EXECUTOR_SERVICE
        );
    }

    /**
     * Runs {@code task} on the calling thread, then shuts the shared executor down and
     * blocks until every submitted task has finished. Only call this from {@code
     * main(String[])}, as its final action - no further submissions are accepted after.
     *
     * @param task the task to run
     * @throws NullPointerException if {@code task} is {@code null}
     */
    public void runTaskInMainSafety(final Runnable task) {
        try {

            Asserts.requireNonNull(
                    task, "@MultiTaskingFactory.runTaskInMainSafety: Runnable cannot be null"
            ).run();

        } finally {

            EXECUTOR_SERVICE.shutdown();
            try {
                this.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        }

    }

    /**
     * Blocks until every task submitted through this class has finished, a shutdown
     * was requested and all tasks finished, or the timeout elapses, whichever happens
     * first.
     *
     * @param timeout the maximum time to wait
     * @param unit the unit of {@code timeout}
     * @return {@code true} if the executor terminated, {@code false} if the timeout elapsed first
     * @throws NullPointerException if {@code unit} is {@code null}
     * @throws InterruptedException if the current thread is interrupted while waiting
     */
    public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
        return EXECUTOR_SERVICE.awaitTermination(
                timeout, Asserts.requireNonNull(unit, "@MultiTaskingFactory.awaitTermination: TimeUnit cannot be null")
        );
    }

    /**
     * Gets the shared instance of {@link MultiTaskingFactory}.
     *
     * @return the shared {@link MultiTaskingFactory} instance
     */
    public static MultiTaskingFactory getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Gets the shared {@link ExecutorService} backing this factory.
     *
     * @return the {@code ExecutorService} used to run every task submitted through this class
     */
    public static ExecutorService getExecutorService() {
        return EXECUTOR_SERVICE;
    }

}
