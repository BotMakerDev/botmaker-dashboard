package com.botmaker.dashboard.umbrella;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Where this window's blocking work runs: git, Maven, the release library, the platform's URL opener.
 *
 * <p>Until 2026-09-29 each of those was a {@code supplyAsync} with no executor, which is the common
 * fork-join pool: a few threads sized for computation, so one 30-second {@code git fetch} held a slot a link
 * click needed, and eleven of them in a row held the Release tab's arrows for minutes. This is one executor of
 * virtual threads — a thread blocked on a process costs nothing — and what bounds the real resource is
 * {@link Proc}'s cap on processes running at once.
 *
 * <p>Tabs that serialise on purpose keep their own single thread: the Releases tab's verdict polls (a rate
 * limit, not speed) and the Changelog tab's reads and saves (a save commits).
 */
public final class Io {

    /** Every background task that blocks. Virtual threads, so a task waiting on a process holds nothing. */
    public static final ExecutorService EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dashboard-io-", 0).factory());

    private static final Map<Path, ReentrantLock> REPOSITORIES = new ConcurrentHashMap<>();

    private Io() {
    }

    /** {@code supplyAsync} on {@link #EXECUTOR}. */
    public static <T> CompletableFuture<T> async(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, EXECUTOR);
    }

    /**
     * Work a Cancel button can stop.
     *
     * <p><b>A cancel is an interrupt of the thread doing the work</b>, because what these tasks wait on is a
     * process: this module's {@link Proc} and the release library's {@code Proc} both destroy the command an
     * interrupt lands in, and answer at once for every command after it, so a preview or a clean-room resolve
     * unwinds in a second instead of running on for minutes. {@code CompletableFuture.cancel} interrupts
     * nothing, which is why this is not one.
     *
     * <p>{@link #future()} fails with a {@link CancellationException} the moment {@link #cancel()} is called, so
     * the window gets its buttons back at once; what the work was doing when the interrupt landed finishes in
     * the background and its answer is dropped.
     */
    public static final class Task<T> {

        private final CompletableFuture<T> future = new CompletableFuture<>();
        private volatile Thread thread;
        private volatile boolean cancelled;

        private Task() {
        }

        public CompletableFuture<T> future() {
            return future;
        }

        public boolean cancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
            // Failed before the interrupt: work that catches the interrupt and returns at once would otherwise
            // complete the future first, and a cancelled preview would arm.
            future.completeExceptionally(new CancellationException("cancelled"));
            Thread running = thread;
            if (running != null) {
                running.interrupt();
            }
        }

        private void run(Supplier<T> work) {
            // Set before cancelled is read, and cancel sets cancelled before it reads this: one of the two
            // always sees the other, so a cancel that lands as the task starts is never lost.
            thread = Thread.currentThread();
            try {
                if (!cancelled) {
                    future.complete(work.get());
                }
            } catch (Throwable e) {
                future.completeExceptionally(e);
            } finally {
                thread = null;
                Thread.interrupted();
            }
        }
    }

    /** {@code work} on {@link #EXECUTOR}, as a {@link Task} a Cancel button can stop. */
    public static <T> Task<T> cancellable(Supplier<T> work) {
        Task<T> task = new Task<>();
        EXECUTOR.execute(() -> task.run(work));
        return task;
    }

    /** Whether a future failed because it was cancelled, however deeply the exception was wrapped. */
    public static boolean wasCancelled(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code each} over every item at once, answered in the items' order.
     *
     * <p>For per-module git reads: eleven repositories asked one after another was the Modules tab's whole
     * start-up time.
     */
    public static <T, R> List<R> parallel(List<T> items, Function<T, R> each) {
        List<CompletableFuture<R>> running = new ArrayList<>(items.size());
        for (T item : items) {
            running.add(CompletableFuture.supplyAsync(() -> each.apply(item), EXECUTOR));
        }
        List<R> answers = new ArrayList<>(items.size());
        for (CompletableFuture<R> future : running) {
            answers.add(future.join());
        }
        return answers;
    }

    /**
     * Runs {@code work} holding this repository's lock.
     *
     * <p>For a {@code git fetch}: two fetches into one repository at once can fail on a ref lock, and now that
     * tabs read in parallel, the Release tab's arrows and the Releases tab's history could both be fetching the
     * SDK. Different repositories do not wait for each other.
     */
    public static <T> T inRepository(Path dir, Supplier<T> work) {
        ReentrantLock lock = REPOSITORIES.computeIfAbsent(dir.toAbsolutePath().normalize(), key -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** Stops what is running; called once, when the window closes. */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }
}
