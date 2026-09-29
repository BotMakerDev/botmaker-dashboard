package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The watcher reads only what was appended, and never half a line; old job files are pruned. */
class JobTailTest {

    private static void append(Path file, String text) throws Exception {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static List<String> texts(List<ProgressLine> lines) {
        return lines.stream().map(ProgressLine::text).toList();
    }

    @Test
    void readsWhatWasAppendedAndHoldsBackAnUnfinishedLine(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("job.out");
        JobTail tail = new JobTail(out);
        assertEquals(List.of(), tail.read(), "no file yet");

        append(out, "first\nsecond half");
        assertEquals(List.of("first"), texts(tail.read()));

        // The rest of the line, and a character split across two writes: é is two bytes in UTF-8.
        byte[] e = "é".getBytes(StandardCharsets.UTF_8);
        append(out, " done\ncaf");
        Files.write(out, new byte[] {e[0]}, StandardOpenOption.APPEND);
        assertEquals(List.of("first", "second half done"), texts(tail.read()));
        Files.write(out, new byte[] {e[1], '\n'}, StandardOpenOption.APPEND);
        assertEquals(List.of("first", "second half done", "café"), texts(tail.read()));
    }

    @Test
    void aReplacedFileIsReadFromTheStart(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("job.out");
        JobTail tail = new JobTail(out);
        append(out, "one\ntwo\n");
        tail.read();

        Files.writeString(out, "new\n");
        assertEquals(List.of("new"), texts(tail.read()));
    }

    @Test
    void finishedJobsOlderThanAWeekArePrunedAndTheNewestIsKept(@TempDir Path umbrella) throws Exception {
        Path running = Files.createDirectories(umbrella.resolve("releases/.running"));
        for (String stamp : List.of("2026-09-01-100000", "2026-09-02-100000", "2026-09-28-100000")) {
            Files.writeString(running.resolve(stamp + ".pid"), "999999999");
            Files.writeString(running.resolve(stamp + ".out"), "x\n");
        }
        ReleaseLauncher.prune(umbrella, LocalDateTime.parse("2026-09-29T12:00:00"));

        assertFalse(Files.exists(running.resolve("2026-09-01-100000.out")));
        assertFalse(Files.exists(running.resolve("2026-09-02-100000.pid")));
        assertTrue(Files.exists(running.resolve("2026-09-28-100000.out")), "a day old: kept");

        // Alone and old, the newest job is still kept.
        Files.delete(running.resolve("2026-09-28-100000.out"));
        Files.delete(running.resolve("2026-09-28-100000.pid"));
        Files.writeString(running.resolve("2026-08-01-100000.pid"), "999999999");
        ReleaseLauncher.prune(umbrella, LocalDateTime.parse("2026-09-29T12:00:00"));
        assertTrue(Files.exists(running.resolve("2026-08-01-100000.pid")));
    }
}
