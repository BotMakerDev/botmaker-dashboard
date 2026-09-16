package com.botmaker.dashboard.umbrella;

import com.botmaker.dashboard.umbrella.ReleaseProgress.Filter;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Lane;
import com.botmaker.dashboard.umbrella.ReleaseProgress.NodeState;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Phase;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Step;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.botmaker.dashboard.umbrella.ReleaseFixtures.apiTaggedAndBuilt;
import static com.botmaker.dashboard.umbrella.ReleaseFixtures.at;
import static com.botmaker.dashboard.umbrella.ReleaseFixtures.head;
import static com.botmaker.dashboard.umbrella.ReleaseFixtures.join;
import static com.botmaker.dashboard.umbrella.ReleaseFixtures.log;
import static com.botmaker.dashboard.umbrella.ReleaseFixtures.out;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a release is, read from the child's output and the log — every state the board draws.
 *
 * <p>The fixtures are the library's own lines, so a line reworded in {@code com.botmaker.cli.release} that this
 * class reads is a fixture to update here as well; the tests that fail are the ones naming the step it drove.
 */
class ReleaseProgressTest {

    private static List<NodeState> states(Lane lane) {
        return List.of(lane.state(Step.COMMIT), lane.state(Step.TAG), lane.state(Step.JITPACK),
                lane.state(Step.ACTIONS));
    }

    @Test
    void aFreshRunIsDecidingAndHasNoLanesUntilTheLogExists() {
        ReleaseProgress progress = ReleaseProgress.of(out(0, "Cutting botmaker release --all --execute",
                1, "Release plan:", 40, "Gates:"), Optional.empty(), true, at(50));

        assertEquals(Phase.DECIDING, progress.phase());
        assertTrue(progress.lanes().isEmpty());
        assertEquals(Duration.ofSeconds(50), progress.elapsed());
        assertTrue(progress.phase().running());
    }

    @Test
    void midChainTheLogWinsForAFinishedModuleAndTheOutputFillsInTheOneStillGoing() {
        ReleaseProgress progress = ReleaseFixtures.midChain();

        assertEquals(Phase.TAGGING, progress.phase());
        List<Lane> lanes = progress.lanes();
        assertEquals(List.of(NodeState.OK, NodeState.OK, NodeState.OK, NodeState.PENDING), states(lanes.get(0)));
        // The host's row still says pending in the log; its output says it is pushed and waiting on JitPack.
        assertEquals(List.of(NodeState.OK, NodeState.OK, NodeState.RUNNING, NodeState.PENDING),
                states(lanes.get(1)));
        // Studio has not started, and JitPack never builds it.
        assertEquals(List.of(NodeState.PENDING, NodeState.PENDING, NodeState.SKIPPED, NodeState.PENDING),
                states(lanes.get(2)));

        assertEquals(Optional.of(Duration.ofSeconds(110)), lanes.get(0).elapsed());
        assertEquals(Optional.of(Duration.ofSeconds(300)), lanes.get(1).elapsed(), "still going: up to now");
        assertEquals(Optional.empty(), lanes.get(2).elapsed());
        assertTrue(lanes.get(1).running());

        ReleaseProgress.Tiles tiles = progress.tiles();
        assertEquals(3, tiles.total());
        assertEquals(2, tiles.tagged());
        assertEquals(2, tiles.jitpackTotal());
        assertEquals(1, tiles.jitpackOk());
        assertEquals(1, tiles.jitpackWaiting());
        assertEquals(3, tiles.actionsPending());
        assertEquals(Duration.ofSeconds(500), tiles.elapsed());
    }

    @Test
    void aModuleThatHasOnlyStartedIsRunningItsCommit() {
        ReleaseProgress progress = ReleaseProgress.of(
                join(head(), List.of(90, "Releasing botmaker-studio-api v0.1.0",
                        91, "    $ cat > /u/botmaker-studio-api/CHANGELOG.md <<'EOF' … EOF")),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | pending | — | pending | pending |"),
                true, at(95));

        assertEquals(List.of(NodeState.RUNNING, NodeState.PENDING, NodeState.PENDING, NodeState.PENDING),
                states(progress.lanes().getFirst()));
    }

    @Test
    void aTagPushThatIsTheLastLineIsStillRunning() {
        ReleaseProgress progress = ReleaseProgress.of(
                join(head(), List.of(90, "Releasing botmaker-studio v1.2.0",
                        91, "    $ git -C /u/botmaker-studio commit -am 'release: studio v1.2.0'",
                        92, "    $ git -C /u/botmaker-studio tag v1.2.0",
                        92, "    $ git -C /u/botmaker-studio push origin HEAD",
                        93, "    $ git -C /u/botmaker-studio push origin v1.2.0")),
                log("| botmaker-studio | 1.2.0 | v1.2.0 | pending | — | n/a (not a Maven artifact) | pending |"),
                true, at(95));

        assertEquals(List.of(NodeState.OK, NodeState.RUNNING, NodeState.SKIPPED, NodeState.PENDING),
                states(progress.lanes().getFirst()));
    }

    @Test
    void theTagFilterShowsOnlyLanesWhoseTagIsUnsettled() {
        ReleaseProgress progress = ReleaseFixtures.midChain();

        assertEquals(3, progress.lanes(Filter.ALL).size());
        assertEquals(List.of("botmaker-studio"), progress.lanes(Filter.TAG).stream().map(Lane::module).toList());
        assertEquals(List.of("botmaker-plugin-host"),
                progress.lanes(Filter.JITPACK).stream().map(Lane::module).toList());
    }

    @Test
    void aJitpackTimeoutIsWaitingNotFailedUntilTheVerifyPassAnswers() {
        ReleaseProgress progress = ReleaseProgress.of(join(head(), apiTaggedAndBuilt()),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | jitpack timeout | stamped | pending | pending |",
                        "| botmaker-plugin-host | 0.1.0 | v0.1.0 | pending | — | pending | pending |"),
                true, at(700));

        Lane api = progress.lanes().getFirst();
        assertEquals(NodeState.PENDING, api.state(Step.JITPACK));
        assertEquals("jitpack timeout", api.stage());
        assertEquals(2, progress.tiles().jitpackWaiting(), "the timed-out one and the host, not started yet");
        assertFalse(api.failed());
    }

    @Test
    void everyModuleAnsweredAndNothingRecordedYetIsVerifying() {
        ReleaseProgress progress = ReleaseProgress.of(join(head(), apiTaggedAndBuilt()),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |"),
                true, at(300));

        assertEquals(Phase.VERIFYING, progress.phase());
    }

    @Test
    void aCrashMidChainFailsTheTagAndSkipsEverythingAfterIt() {
        ReleaseProgress progress = ReleaseFixtures.crashed();

        assertEquals(Phase.STOPPED, progress.phase());
        assertFalse(progress.phase().running());
        List<Lane> lanes = progress.lanes();
        assertEquals(List.of(NodeState.OK, NodeState.FAILED, NodeState.SKIPPED, NodeState.SKIPPED),
                states(lanes.get(1)));
        assertEquals(List.of(NodeState.SKIPPED, NodeState.SKIPPED, NodeState.SKIPPED, NodeState.SKIPPED),
                states(lanes.get(2)));
        assertEquals(List.of("release: commit, tag and push: botmaker-plugin-host: pushing v0.1.0 failed."),
                lanes.get(1).errors());
        // The host's segment closes at the error line, not at the moment the window read the file.
        assertEquals(Optional.of(Duration.ofSeconds(10)), lanes.get(1).elapsed());
        assertEquals(Duration.ofSeconds(211), progress.elapsed());
        assertTrue(progress.tiles().tagFailed());
        assertTrue(progress.failed());
    }

    @Test
    void aFailureBeforeTheCommitFailsTheCommitNode() {
        ReleaseProgress progress = ReleaseProgress.of(
                join(head(), List.of(90, "Releasing botmaker-studio v1.2.0",
                        95, "error: botmaker-studio failed at fallback versions — the release stopped here.",
                        96, "release-job: stopped — nothing matches")),
                log("| botmaker-studio | 1.2.0 | v1.2.0 | FAILED | — | n/a (not a Maven artifact) | not tagged |",
                        "", "## Errors", "", "**botmaker-studio — release**", "```",
                        "fallback versions: MavenService.java: nothing matches", "```"),
                false, at(100));

        assertEquals(List.of(NodeState.FAILED, NodeState.SKIPPED, NodeState.SKIPPED, NodeState.SKIPPED),
                states(progress.lanes().getFirst()));
    }

    @Test
    void aCompleteRunCarriesThePolledVerdicts() {
        ReleaseProgress progress = ReleaseFixtures.completeWithFailures();

        assertEquals(Phase.DONE, progress.phase());
        List<Lane> lanes = progress.lanes();
        assertEquals(List.of(NodeState.OK, NodeState.OK, NodeState.OK, NodeState.OK), states(lanes.get(0)));
        assertEquals(List.of(NodeState.OK, NodeState.OK, NodeState.FAILED, NodeState.FAILED), states(lanes.get(1)));
        assertEquals(List.of(NodeState.OK, NodeState.OK, NodeState.SKIPPED, NodeState.OK), states(lanes.get(2)));
        assertTrue(lanes.get(1).errors().getFirst().startsWith("actions: CI: failure — https://"));

        ReleaseProgress.Tiles tiles = progress.tiles();
        assertEquals(3, tiles.tagged());
        assertEquals(1, tiles.jitpackFailed());
        assertEquals(2, tiles.actionsOk());
        assertEquals(1, tiles.actionsFailed());
        assertEquals(Duration.ofSeconds(420), tiles.elapsed(), "the clock stops at the ending line");
        assertEquals(Optional.of(Duration.ofSeconds(80)), lanes.get(2).elapsed(), "Studio ends at Recording");
    }

    @Test
    void aProcessThatVanishedWithoutAnEndingDied() {
        ReleaseProgress progress = ReleaseProgress.of(join(head(), apiTaggedAndBuilt()),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | pending | — | pending | pending |"),
                false, at(9000));

        assertEquals(Phase.DIED, progress.phase());
        assertEquals(Duration.ofSeconds(200), progress.elapsed(), "stops at the last line, not at now");
        assertTrue(progress.failed());
    }

    @Test
    void theEndingsReadBack() {
        assertEquals(Phase.REFUSED, ReleaseProgress.of(out(0, "Gates:", 5,
                ReleaseProgress.Ending.REFUSED.line("2 gate(s)")), Optional.empty(), false, at(9)).phase());
        assertEquals(Phase.UNPUSHED, ReleaseProgress.of(out(0,
                ReleaseProgress.Ending.UNPUSHED.line("")), Optional.empty(), false, at(9)).phase());
        assertEquals(Phase.DONE, ReleaseProgress.of(out(0,
                ReleaseProgress.Ending.DONE.line("")), Optional.empty(), false, at(9)).phase());
    }

    @Test
    void anUnstampedLineIsKeptAsText() {
        ReleaseProgress.Line line = ReleaseProgress.Line.parse("WARNING: A restricted method was called");
        assertEquals(Optional.empty(), line.at());
        assertEquals("WARNING: A restricted method was called", line.text());

        List<ReleaseProgress.Line> lines = ReleaseProgress.Line.parseAll(
                ReleaseProgress.Line.format(at(0), "Release log: releases/2026-09-16-1020.md") + "\n");
        assertEquals(1, lines.size());
        assertEquals(Optional.of("2026-09-16-1020.md"), ReleaseProgress.logName(lines));
    }

    @Test
    void theClockReadsMinutesThenHours() {
        assertEquals("0:05", ReleaseProgress.clock(Duration.ofSeconds(5)));
        assertEquals("12:41", ReleaseProgress.clock(Duration.ofSeconds(761)));
        assertEquals("1:02:05", ReleaseProgress.clock(Duration.ofSeconds(3725)));
    }
}
