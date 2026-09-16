package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The child's command line, and finding a job again from its two files. */
class ReleaseLauncherTest {

    private static final ReleaseSpec SPEC = new ReleaseSpec(Optional.of("minor"),
            Map.of(Module.SDK, "1.2.0"), true, false);

    @Test
    void theChildRunsReleaseJobInItsOwnSessionWithTheSameFlags() {
        List<String> argv = ReleaseLauncher.argv(true, Path.of("/jdk"), "a.jar:b", Path.of("/u"),
                "2026-09-16-102000", SPEC);

        assertEquals(List.of("setsid", Path.of("/jdk", "bin", "java").toString(), "-cp", "a.jar:b",
                "com.botmaker.dashboard.umbrella.ReleaseJob", "/u", "2026-09-16-102000",
                "--all", "minor", "--sdk", "1.2.0", "--force"), argv);
        // No --execute: the child executes by being started, and ReleaseSpec.parse takes the rest back.
        assertEquals(SPEC, ReleaseSpec.parse(argv.subList(7, argv.size())));
    }

    @Test
    void withoutSetsidTheJavaCommandLeads() {
        List<String> argv = ReleaseLauncher.argv(false, Path.of("/jdk"), "cp", Path.of("/u"), "s", SPEC);

        assertEquals(Path.of("/jdk", "bin", "java").toString(), argv.getFirst());
    }

    @Test
    void theModulePathIsFoldedIntoTheClassPath() {
        assertEquals("a" + File.pathSeparator + "fx", ReleaseLauncher.classPath("a", "fx"));
        assertEquals("a", ReleaseLauncher.classPath("a", null));
        assertEquals("fx", ReleaseLauncher.classPath("", "fx"));
    }

    @Test
    void theNewestJobIsFoundAndADeadPidIsNotAlive(@TempDir Path umbrella) throws Exception {
        assertEquals(Optional.empty(), ReleaseLauncher.latest(umbrella));

        Path running = Files.createDirectories(umbrella.resolve("releases/.running"));
        Files.writeString(running.resolve("2026-09-05-120000.pid"), "1");
        Files.writeString(running.resolve("2026-09-16-102000.pid"), "999999999");
        Files.writeString(running.resolve("2026-09-16-102000.out"), ReleaseProgress.Line.format(
                ReleaseFixtures.T0, ReleaseProgress.Ending.DONE.line("")) + "\n");
        Files.writeString(running.resolve("notes.pid"), "3");

        ReleaseLauncher.Job job = ReleaseLauncher.latest(umbrella).orElseThrow();
        assertEquals("2026-09-16-102000", job.stamp());
        assertFalse(job.alive());
        assertEquals(ReleaseProgress.Phase.DONE, job.progress(ReleaseFixtures.at(10)).phase());
    }

    @Test
    void aLivePidRunningSomethingElseIsNotAJob(@TempDir Path umbrella) throws Exception {
        Path running = Files.createDirectories(umbrella.resolve("releases/.running"));
        // This test's own JVM: alive, and not a ReleaseJob.
        Files.writeString(running.resolve("2026-09-16-102000.pid"), Long.toString(ProcessHandle.current().pid()));

        ReleaseLauncher.Job job = ReleaseLauncher.latest(umbrella).orElseThrow();
        boolean commandLineKnown = ProcessHandle.current().info().commandLine().isPresent();
        assertEquals(!commandLineKnown, job.alive());
    }

    @Test
    void theJobReadsTheLogItsOutputNames(@TempDir Path umbrella) throws Exception {
        Path running = Files.createDirectories(umbrella.resolve("releases/.running"));
        Files.writeString(running.resolve("2026-09-16-102000.pid"), "999999999");
        Files.writeString(running.resolve("2026-09-16-102000.out"), String.join("\n",
                ReleaseProgress.Line.format(ReleaseFixtures.at(0), "Release log: releases/2026-09-16-1020.md"),
                ReleaseProgress.Line.format(ReleaseFixtures.at(1), "Releasing botmaker-sdk v1.2.0")) + "\n");
        Files.writeString(umbrella.resolve("releases/2026-09-16-1020.md"), "# Release 2026-09-16 10:20\n\n"
                + "| module | version | tag | stage | changelog | jitpack | actions |\n|---|---|---|---|---|---|---|\n"
                + "| botmaker-sdk | 1.2.0 | v1.2.0 | pending | — | pending | pending |\n");

        ReleaseProgress progress = ReleaseLauncher.latest(umbrella).orElseThrow().progress(ReleaseFixtures.at(5));
        assertEquals(1, progress.lanes().size());
        assertEquals("v1.2.0", progress.lanes().getFirst().tag());
        assertEquals(ReleaseProgress.Phase.DIED, progress.phase());
        assertTrue(progress.failed());
    }
}
