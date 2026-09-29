package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The child's contract with the window, run as the child: its own pid in the pid file, every line stamped, and
 * an ending line the window reads as the outcome. Nothing is released — the flags are refused before the
 * library is asked.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ReleaseJobTest {

    @TempDir
    Path umbrella;

    @Test
    void aRefusedCommandLineEndsStoppedWithItsPidWrittenAndEveryLineStamped() throws Exception {
        String stamp = "2026-09-29-120000";
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Proc child = Proc.run(umbrella, Duration.ofSeconds(60), List.of(java.toString(), "-cp",
                System.getProperty("java.class.path"), ReleaseLauncher.JOB_CLASS, umbrella.toString(), stamp,
                "--no-such-flag"));

        assertEquals(2, child.exit(), child.out());
        List<ProgressLine> lines = ProgressLine.parseAll(child.out());
        assertTrue(lines.stream().allMatch(line -> line.at().isPresent()), child.out());
        assertTrue(lines.getLast().text().startsWith(ProgressLine.Ending.STOPPED.line("")), child.out());

        Path pid = ReleaseLauncher.pidFile(umbrella, stamp);
        assertTrue(Files.isRegularFile(pid));
        assertTrue(Long.parseLong(Files.readString(pid).strip()) > 0);
    }
}
