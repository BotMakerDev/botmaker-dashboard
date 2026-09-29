package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A timeout is a result — including for a process whose output never ends, which it was not until 2026-09-29. */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcTest {

    @TempDir
    Path dir;

    @Test
    void aHungProcessTimesOutOnTime() {
        long start = System.nanoTime();
        Proc proc = Proc.run(dir, Duration.ofSeconds(1), "sh", "-c", "echo started; sleep 30");
        long took = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(Proc.TIMED_OUT, proc.exit());
        assertTrue(took < 5_000, "took " + took + " ms");
        assertTrue(proc.out().startsWith("started"), proc.out());
        assertTrue(proc.out().contains("timed out after 1s"), proc.out());
    }

    @Test
    void stdinReachesTheProcessAndTheOutputComesBack() {
        Proc proc = Proc.run(dir, Duration.ofSeconds(10), List.of("cat"), "the prompt");

        assertTrue(proc.ok());
        assertEquals("the prompt", proc.out());
    }

    @Test
    void anInterruptKillsTheCommandAndTheNextOneStartsNothing() throws Exception {
        // A Cancel is an interrupt (Io.Task): the command it lands in dies, and whatever the work tries next
        // answers at once instead of running.
        java.util.concurrent.atomic.AtomicReference<Proc> first = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Proc> second = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            first.set(Proc.run(dir, Duration.ofSeconds(30), "sleep", "30"));
            second.set(Proc.run(dir, Duration.ofSeconds(30), "sh", "-c", "echo ran"));
        });
        Thread.sleep(300);
        worker.interrupt();
        worker.join(Duration.ofSeconds(5));

        assertEquals(Proc.TIMED_OUT, first.get().exit());
        assertEquals(Proc.TIMED_OUT, second.get().exit());
        assertTrue(!second.get().out().contains("ran"), second.get().out());
    }

    @Test
    void aCommitRunsToTheEndThroughAnInterrupt() throws Exception {
        // A git commit killed halfway leaves index.lock; runToTheEnd is how a commit outlives a Cancel.
        java.util.concurrent.atomic.AtomicReference<Proc> answer = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() ->
                answer.set(Proc.runToTheEnd(dir, Duration.ofSeconds(10), "sh", "-c", "sleep 1; echo done")));
        Thread.sleep(200);
        worker.interrupt();
        worker.join(Duration.ofSeconds(5));

        assertTrue(answer.get().ok(), answer.get().out());
        assertEquals("done\n", answer.get().out());
    }

    @Test
    void aProcessThatNeverReadsItsInputStillFinishes() {
        Proc proc = Proc.run(dir, Duration.ofSeconds(10), List.of("sh", "-c", "exit 3"), "x".repeat(1 << 20));

        assertEquals(3, proc.exit());
    }
}
