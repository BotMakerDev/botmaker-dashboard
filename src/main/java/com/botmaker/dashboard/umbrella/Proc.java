package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One external command, run to completion, with its output captured.
 *
 * <p>This is the whole of this module's execution model, and it is deliberately small: every question about
 * the release constellation is answered by {@code git} or by {@code release.sh}, never by code here. See
 * {@code CLAUDE.md} — <i>never reimplement a decision {@code release.sh} owns</i>.
 *
 * <p>Two properties matter and both are about failing usefully. <b>stderr is merged into stdout</b>, because
 * the interesting half of a failed {@code release.sh} run is on stderr and a window that showed only stdout
 * would report a gate refusal as an empty plan. And <b>a timeout is a result, not an exception</b>: the
 * process is destroyed and the exit code is {@link #TIMED_OUT}, so a hung {@code git} on a network remote
 * degrades to one row saying so rather than to a frozen window.
 *
 * <p>Never call this on the FX thread.
 */
public record Proc(int exit, String out) {

    /** The exit code reported when the command outlived its timeout. Not a code any command can return. */
    public static final int TIMED_OUT = -1;

    /** Whether the command exited 0. A non-zero exit is ordinary here — {@code release.sh} exits 1 on a gate. */
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
        ProcessBuilder pb = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return new Proc(TIMED_OUT, out + "\n(timed out after " + timeout.toSeconds() + "s)");
            }
            return new Proc(p.exitValue(), out);
        } catch (IOException e) {
            return new Proc(TIMED_OUT, String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (p != null) {
                p.destroyForcibly();
            }
            return new Proc(TIMED_OUT, "interrupted");
        }
    }
}
