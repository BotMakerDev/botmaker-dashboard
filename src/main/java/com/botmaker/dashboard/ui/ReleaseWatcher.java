package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.umbrella.JobTail;
import com.botmaker.dashboard.umbrella.ProgressLine;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
import javafx.application.Platform;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Reads a release child once a second, off the FX thread, and hands what it read to a {@link View} on it.
 *
 * <p>A poll rather than a {@code WatchService}: two files in two directories, one of them rewritten whole
 * after every module, and the liveness of a process that no file event reports. One read a second is nothing
 * beside that, and it is the same code on every platform. It reads from where the last tick stopped
 * ({@link JobTail}): the whole file twice a second grew with the run.
 *
 * <p>Split out of the Release tab on 2026-09-29. It keeps no drawing: the tab draws.
 */
final class ReleaseWatcher {

    /** What the watcher reports, always on the FX thread and only for the job being watched. */
    interface View {
        /** Lines the child wrote since the last call. */
        void added(List<ProgressLine> lines);

        void progress(ReleaseProgress progress);

        /** The job is over; watching has stopped. */
        void ended(ReleaseProgress progress);

        /** One tick could not read the job; the watch goes on. */
        void unreadable(String sentence);
    }

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "release-watcher");
        thread.setDaemon(true);
        return thread;
    });

    private ReleaseLauncher.Job watched;
    private int shown;
    private ScheduledFuture<?> ticking;

    /** Starts watching {@code job}, dropping whatever was watched before. */
    void watch(ReleaseLauncher.Job job, View view) {
        stop();
        watched = job;
        shown = 0;
        JobTail tail = new JobTail(job.out());
        ticking = ticker.scheduleWithFixedDelay(() -> {
            // Caught here: a scheduled task that throws is cancelled silently, which left the tab thinking a
            // job was running and Preview dead until a restart (2026-09-29).
            try {
                List<ProgressLine> lines = tail.read();
                ReleaseProgress progress = job.progress(lines, Instant.now());
                Platform.runLater(() -> show(job, view, progress, lines));
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    if (job == watched) {
                        view.unreadable("Could not read the release's progress this second: " + e.getMessage());
                    }
                });
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void show(ReleaseLauncher.Job job, View view, ReleaseProgress progress, List<ProgressLine> lines) {
        if (job != watched) {
            return;
        }
        if (lines.size() > shown) {
            view.added(lines.subList(shown, lines.size()));
            shown = lines.size();
        }
        view.progress(progress);
        if (!progress.phase().running()) {
            stop();
            view.ended(progress);
        }
    }

    /** The job being watched, if any. */
    java.util.Optional<ReleaseLauncher.Job> job() {
        return java.util.Optional.ofNullable(watched);
    }

    /** Whether a job is being watched — a live one, until its last line. */
    boolean watching() {
        return watched != null;
    }

    void stop() {
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
        watched = null;
    }
}
