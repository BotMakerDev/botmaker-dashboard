package com.botmaker.dashboard.umbrella;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One external command, run to completion, with its output captured.
 *
 * <p>This is the whole of this module's execution model, and since 2026-09-16 it runs <b>one program</b>:
 * {@code git}. The release used to be the other one — every plan and every re-poll went through
 * {@code ./release.sh} and came back as text — and it is called as a library now, so what is left here is
 * the tree's own state, which git is the owner of. See {@code CLAUDE.md} — <i>never reimplement a decision
 * the release owns</i>; a subprocess was one way of keeping that rule and a direct call is the stricter one.
 *
 * <p>Two properties matter and both are about failing usefully. <b>stderr is merged into stdout</b>, because
 * the interesting half of a failed run is on stderr and a window that showed only stdout would report a
 * refusal as an empty answer. And <b>a timeout is a result, not an exception</b>: the
 * process is destroyed and the exit code is {@link #TIMED_OUT}, so a hung {@code git} on a network remote
 * degrades to one row saying so rather than to a frozen window.
 *
 * <p>Never call this on the FX thread.
 */
public record Proc(int exit, String out) {

    /** The exit code reported when the command outlived its timeout. Not a code any command can return. */
    public static final int TIMED_OUT = -1;

    /** Whether the command exited 0. A non-zero exit is ordinary here — {@code git tag} in a fresh repo. */
    public boolean ok() {
        return exit == 0;
    }

    /** The first line of the output, or {@code ""} — what most {@code git} queries actually return. */
    public String firstLine() {
        int nl = out.indexOf('\n');
        return (nl < 0 ? out : out.substring(0, nl)).trim();
    }

    public static Proc run(Path dir, Duration timeout, String... command) {
        return run(dir, timeout, List.of(command));
    }

    public static Proc run(Path dir, Duration timeout, List<String> command) {
        return run(dir, timeout, command, null);
    }

    /**
     * Runs {@code command} with {@code stdin} written to it and closed ({@code null}: stdin closed at once).
     *
     * <p><b>The output is drained on a thread of its own, and the timeout is counted beside it.</b> Reading to
     * the end first and then waiting — how this was written until 2026-09-29 — never reached the wait for a
     * hung {@code git fetch}: the pipe stays open for as long as the process does, so the timeout could not
     * fire and the calling thread was held for good. stdin is written on its own thread for the same reason:
     * a process that never reads it would otherwise block the write, and the wait behind it.
     */
    public static Proc run(Path dir, Duration timeout, List<String> command, String stdin) {
        try {
            RUNNING.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Proc(TIMED_OUT, "interrupted");
        }
        try {
            return runNow(dir, timeout, command, stdin);
        } finally {
            RUNNING.release();
        }
    }

    /**
     * How many commands may run at once.
     *
     * <p>Callers run in parallel since 2026-09-29 ({@link Io}), on virtual threads that cost nothing to hold;
     * processes are the resource that runs out, so they are what is bounded. Eight is enough to read every
     * module's git state together without starting forty {@code git} processes on a laptop.
     */
    private static final Semaphore RUNNING = new Semaphore(8);

    private static Proc runNow(Path dir, Duration timeout, List<String> command, String stdin) {
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            Process process = p;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                    // The process was destroyed under the read; what was read so far is the output.
                }
            });
            Thread.ofVirtual().start(() -> {
                try (OutputStream in = process.getOutputStream()) {
                    if (stdin != null) {
                        in.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // The process exited without reading its input; its output says why.
                }
            });
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(p);
                reader.join(DRAIN_AFTER_KILL.toMillis());
                return new Proc(TIMED_OUT, text(out) + "\n(timed out after " + timeout.toSeconds() + "s)");
            }
            // A grandchild (git's ssh) can hold the pipe after the process itself exits; bound the wait for it.
            reader.join(DRAIN_AFTER_KILL.toMillis());
            return new Proc(p.exitValue(), text(out));
        } catch (IOException e) {
            return new Proc(TIMED_OUT, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                destroy(p);
            }
            return new Proc(TIMED_OUT, "interrupted");
        }
    }

    /** How long the output may keep arriving once the process is gone. */
    private static final Duration DRAIN_AFTER_KILL = Duration.ofSeconds(2);

    /** The process and everything it started: a killed {@code git} would otherwise leave its {@code ssh}. */
    private static void destroy(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static String text(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8);
    }
}
