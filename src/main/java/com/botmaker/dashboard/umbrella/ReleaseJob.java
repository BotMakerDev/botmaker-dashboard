package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * A release, cut in its own process — what the Release tab's Execute starts, through {@link ReleaseLauncher}.
 *
 * <p><b>Why a process of its own.</b> The 2026-09-16 release ran inside the window's JVM, the window died
 * during an unrelated click, and the chain died with it four tags in. A tag chain takes minutes per module and
 * a window is closed, crashes, or is stopped from an IDE on a much shorter clock. So the release now outlives
 * the window: the window starts this, reads the two files it leaves, and can be closed and reopened while it
 * runs.
 *
 * <p><b>It is {@link ReleaseRun#go} and nothing else.</b> Same library call, same {@code why}, same way of
 * turning a refusal into a value — the only differences are where the lines go (stdout, stamped, which the
 * launcher sends to a file) and that the outcome is an {@link ReleaseProgress.Ending} line and an exit code
 * rather than a record handed back to a caller. The preview stays in the window's own process, since it writes
 * nothing and a crash there costs nothing.
 *
 * <p>Arguments: {@code <umbrella> <job stamp> [--dry-run] <botmaker release flags…>}. {@code --dry-run} is for
 * rehearsing the process machinery by hand; the window never passes it, because a preview in a child process
 * would be a preview nobody can see arm the button.
 */
public final class ReleaseJob {

    private ReleaseJob() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: ReleaseJob <umbrella> <job stamp> [--dry-run] <botmaker release flags…>");
            System.exit(2);
        }
        Path umbrella = Path.of(args[0]).toAbsolutePath();
        String stamp = args[1];
        List<String> rest = Arrays.asList(args).subList(2, args.length);
        boolean dryRun = !rest.isEmpty() && rest.getFirst().equals("--dry-run");
        List<String> flags = dryRun ? rest.subList(1, rest.size()) : rest;

        PrintStream out = System.out;
        writePid(ReleaseLauncher.pidFile(umbrella, stamp));

        ReleaseSpec spec;
        try {
            spec = ReleaseSpec.parse(flags);
        } catch (IllegalArgumentException e) {
            say(out, ReleaseProgress.Ending.STOPPED.line(e.getMessage()));
            System.exit(2);
            return;
        }
        say(out, (dryRun ? "Rehearsing " + spec.commandLine() : "Cutting " + spec.executeCommandLine())
                + " — process " + ProcessHandle.current().pid());

        // A rehearsal is ReleaseRun.go with execute=false — the dry Runner, exactly what Preview uses.
        ReleaseRun run = ReleaseRun.go(umbrella, spec, !dryRun, line -> say(out, line));

        ReleaseProgress.Ending ending;
        String detail = "";
        if (run.error().isPresent()) {
            ending = ReleaseProgress.Ending.STOPPED;
            detail = run.error().get();
        } else if (run.refused()) {
            ending = ReleaseProgress.Ending.REFUSED;
            detail = run.refusals().size() + " gate(s)";
        } else {
            ending = run.pushesOk() ? ReleaseProgress.Ending.DONE : ReleaseProgress.Ending.UNPUSHED;
        }
        say(out, ending.line(detail));
        System.exit(ending == ReleaseProgress.Ending.DONE ? 0 : 1);
    }

    /**
     * One stamped line, flushed. The flush is the point: the window reads the file while this runs, and a line
     * sitting in a buffer is a module the board says has not started.
     */
    private static void say(PrintStream out, String line) {
        for (String part : line.split("\n", -1)) {
            out.println(ReleaseProgress.Line.format(Instant.now(), part));
        }
        out.flush();
    }

    /**
     * The launcher already wrote the pid it started; this writes the pid that is actually running, which is
     * the same one unless {@code setsid} had to fork. Best effort — a job whose pid cannot be written still
     * releases, and the window then says it cannot tell whether it is alive.
     */
    private static void writePid(Path pidFile) {
        try {
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
        } catch (IOException e) {
            System.err.println("could not write " + pidFile + ": " + e.getMessage());
        }
    }
}
