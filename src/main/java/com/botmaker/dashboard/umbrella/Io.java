package com.botmaker.dashboard.umbrella;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * limit, not speed) and the Changelog tab's drafts and saves (a save commits).
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
