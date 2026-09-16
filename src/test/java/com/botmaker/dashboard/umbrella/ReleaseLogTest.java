package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseLogTest {

    /** A real log, trimmed: one clean module, one whose JitPack build never produced a pom. */
    private static final String LOG = """
            # Release 2026-09-04 23:18

            | module | version | tag | changelog | jitpack | actions |
            |---|---|---|---|---|---|
            | botmaker-studio | 1.0.35 | v1.0.35 | stamped | n/a (not a Maven artifact) | success (1) |
            | botmaker-studio-api | 0.0.4 | v0.0.4 | stamped | ok (resolves clean) | success (1) |
            | botmaker-cli | 0.0.8 | v0.0.8 | stamped | NOT PUBLISHED | running (1 of 2) |

            ## Errors

            **botmaker-cli — jitpack**
            ```
            botmaker-cli:v0.0.8 — no .pom on JitPack. The tag is pushed, so this is a FAILED or
            never-triggered JitPack build: read it at https://jitpack.io/#LiQiyeDev/botmaker-cli
            ```
            """;

    private static ReleaseLog parsed() {
        return ReleaseLog.parse(Path.of("releases/2026-09-04-2318.md"), LOG);
    }

    @Test
    void readsTheStampAndTheTable() {
        ReleaseLog log = parsed();

        assertEquals("2026-09-04 23:18", log.stamp());
        assertEquals(3, log.rows().size());
        assertEquals("botmaker-studio-api", log.rows().get(1).module());
        assertEquals("v0.0.4", log.rows().get(1).tag());
        assertEquals("ok (resolves clean)", log.rows().get(1).jitpack());
    }

    @Test
    void aLogWithTheStageColumnReadsTheSameColumnsAndTheStage() {
        ReleaseLog log = ReleaseLog.parse(Path.of("releases/2026-09-16-1020.md"), """
                # Release 2026-09-16 10:20

                | module | version | tag | stage | changelog | jitpack | actions |
                |---|---|---|---|---|---|---|
                | botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |
                | botmaker-shared | 0.1.0 | v0.1.0 | FAILED | — | not tagged | not tagged |
                """);

        assertEquals(2, log.rows().size());
        assertEquals("built on jitpack", log.rows().get(0).stage());
        assertEquals("stamped", log.rows().get(0).changelog());
        assertEquals("pending", log.rows().get(0).jitpack());
        assertEquals("FAILED", log.rows().get(1).stage());
        assertEquals("", parsed().rows().get(0).stage());
    }

    @Test
    void neitherTheHeaderNorTheSeparatorIsARow() {
        assertTrue(parsed().rows().stream().noneMatch(r -> r.module().equals("module")));
        assertTrue(parsed().rows().stream().noneMatch(r -> r.module().startsWith("---")));
    }

    @Test
    void classifiesTheScriptsOwnWords() {
        ReleaseLog log = parsed();

        // "n/a" is not success: a module that is not a Maven artifact was never asked the question.
        assertEquals(ReleaseLog.Health.NA, log.rows().get(0).jitpackHealth());
        assertEquals(ReleaseLog.Health.OK, log.rows().get(1).jitpackHealth());
        assertEquals(ReleaseLog.Health.BROKEN, log.rows().get(2).jitpackHealth());
        assertEquals(ReleaseLog.Health.PENDING, log.rows().get(2).actionsHealth());
        assertTrue(log.broken());
    }

    @Test
    void aTagThatFiredNoWorkflowIsBrokenRatherThanPending() {
        // The exact failure this log was added to catch: the tag is pushed, JitPack is green, and the
        // GitHub Release does not exist because the workflow never ran.
        assertEquals(ReleaseLog.Health.BROKEN, ReleaseLog.Health.of("no run on v1.0.35"));
        assertEquals(ReleaseLog.Health.BROKEN, ReleaseLog.Health.of("FAILED — package"));
    }

    @Test
    void keepsTheErrorTextWhole() {
        ReleaseLog log = parsed();

        assertEquals(1, log.problems().size());
        assertEquals("jitpack", log.problems().get(0).kind());
        // Both lines, and the URL — the message is the thing a reader needs six weeks later.
        assertTrue(log.problems().get(0).text().contains("never-triggered JitPack build"));
        assertTrue(log.problems().get(0).text().contains("https://jitpack.io/#LiQiyeDev/botmaker-cli"));
        assertEquals(1, log.problemsFor("botmaker-cli").size());
        assertTrue(log.problemsFor("botmaker-studio").isEmpty());
    }

    @Test
    void aLogWithNothingBrokenSaysSo() {
        ReleaseLog log = ReleaseLog.parse(Path.of("x.md"), """
                # Release 2026-09-05 12:12

                | module | version | tag | changelog | jitpack | actions |
                |---|---|---|---|---|---|
                | botmaker-studio | 1.0.37 | v1.0.37 | stamped | n/a (not a Maven artifact) | success (1) |
                """);

        assertFalse(log.broken());
        assertTrue(log.problems().isEmpty());
    }

    /**
     * A re-poll of a log that is not there stops with a reason, and does not throw.
     *
     * <p>The real poll resolves ten artifacts and calls {@code gh} ten times, so it is a manual test. This
     * is the half that runs in the window's JVM and would otherwise reach the operator wrapped in a
     * {@code CompletionException} — the same promise {@link ReleaseRunTest} makes for a preview.
     */
    @Test
    void aRePollOfAMissingLogIsReportedRatherThanThrown(@TempDir Path umbrella) {
        List<String> streamed = new ArrayList<>();

        ReleaseLog.Repoll polled = ReleaseLog.repoll(umbrella, umbrella.resolve("releases/2026-01-01-0000.md"),
                streamed::add);

        assertFalse(polled.ok());
        assertTrue(polled.error().isPresent(), "the reason is the value, never an exception");
        assertTrue(streamed.stream().anyMatch(line -> line.startsWith("error: ")), streamed.toString());
    }
}
