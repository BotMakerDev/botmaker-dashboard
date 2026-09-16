package com.botmaker.dashboard.umbrella;

import com.botmaker.dashboard.umbrella.ReleaseHistory.Release;
import com.botmaker.dashboard.umbrella.ReleaseHistory.TagRow;
import com.botmaker.dashboard.umbrella.ReleaseProgress.NodeState;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Releases from tags alone — including the one of 2026-09-16, which wrote no log. The fixtures are this
 * project's real tag dates, because the grouping rule was chosen against them.
 */
class ReleaseHistoryTest {

    private static TagRow tag(String module, String tag, String date) {
        return new TagRow(module, tag, OffsetDateTime.parse(date).toInstant());
    }

    /** 2026-09-04 23:05 to 2026-09-16, as `git for-each-ref` reports it across the modules. */
    static List<TagRow> history() {
        return List.of(
                tag("botmaker-studio", "v1.0.34", "2026-09-04T23:05:45+02:00"),
                tag("botmaker-studio-api", "v0.0.3", "2026-09-04T23:05:50+02:00"),
                tag("botmaker-plugin-host", "v0.0.3", "2026-09-04T23:05:58+02:00"),
                tag("botmaker-sdk", "v1.1.3", "2026-09-04T23:06:15+02:00"),
                tag("botmaker-studio", "v1.0.35", "2026-09-04T23:18:24+02:00"),
                tag("botmaker-studio-api", "v0.0.4", "2026-09-04T23:18:29+02:00"),
                tag("botmaker-sdk", "v1.1.4", "2026-09-04T23:18:53+02:00"),
                tag("botmaker-studio", "v1.0.36", "2026-09-05T00:17:37+02:00"),
                tag("botmaker-plugin-host", "v0.0.5", "2026-09-05T00:17:48+02:00"),
                tag("botmaker-cli", "v0.0.9", "2026-09-05T00:17:53+02:00"),
                tag("botmaker-sdk", "v1.1.5", "2026-09-05T00:18:03+02:00"),
                tag("botmaker-cli", "v0.0.10", "2026-09-05T00:30:04+02:00"),
                tag("botmaker-cli", "v0.0.12", "2026-09-05T00:48:52+02:00"),
                tag("botmaker-sdk", "v1.1.6", "2026-09-05T01:27:34+02:00"),
                tag("botmaker-studio", "v1.0.37", "2026-09-05T12:12:33+02:00"),
                tag("botmaker-studio", "v1.1.0", "2026-09-16T12:23:18+02:00"),
                tag("botmaker-studio-api", "v0.1.0", "2026-09-16T12:23:24+02:00"),
                tag("botmaker-plugin-toolkit", "v0.1.0", "2026-09-16T12:24:33+02:00"),
                tag("botmaker-plugin-host", "v0.1.0", "2026-09-16T12:25:23+02:00"));
    }

    private static List<Integer> sizes(List<List<TagRow>> groups) {
        return groups.stream().map(List::size).toList();
    }

    @Test
    void theRealHistoryGroupsIntoTheReleasesItWas() {
        List<List<TagRow>> groups = ReleaseHistory.cluster(history(), ReleaseHistory.GAP);

        // 23:05 and 23:18 are twelve minutes apart and tag the same modules: two releases, not one.
        // 00:30 and 00:48 are cli alone, 18 minutes apart: two. 01:27 is sdk alone, 39 minutes after: one.
        assertEquals(List.of(4, 3, 4, 1, 1, 1, 1, 4), sizes(groups));
    }

    @Test
    void theHalfCutReleaseIsFourTagsWithNoPlanToCompareTo() {
        List<Release> releases = ReleaseHistory.releases(history(), List.of());

        Release newest = releases.getFirst();
        assertEquals(List.of("botmaker-studio", "botmaker-studio-api", "botmaker-plugin-toolkit",
                "botmaker-plugin-host"), newest.tags().stream().map(TagRow::module).toList());
        assertEquals(Optional.empty(), newest.log());
        assertEquals(Duration.ofSeconds(125), newest.span());
        assertEquals(8, releases.size());
    }

    @Test
    void aModuleTaggedTwiceWithinTheGapStartsASecondRelease() {
        List<List<TagRow>> groups = ReleaseHistory.cluster(List.of(
                tag("botmaker-sdk", "v1.0.0", "2026-09-01T10:00:00Z"),
                tag("botmaker-cli", "v0.1.0", "2026-09-01T10:02:00Z"),
                tag("botmaker-sdk", "v1.0.1", "2026-09-01T10:05:00Z")), ReleaseHistory.GAP);

        assertEquals(List.of(2, 1), sizes(groups));
    }

    @Test
    void threeHoursApartIsTwoReleasesAndOneTagIsOne() {
        assertEquals(List.of(2, 1), sizes(ReleaseHistory.cluster(List.of(
                tag("botmaker-sdk", "v1.0.0", "2026-09-01T10:00:00Z"),
                tag("botmaker-cli", "v0.1.0", "2026-09-01T10:09:00Z"),
                tag("botmaker-shared", "v0.1.0", "2026-09-01T13:09:00Z")), ReleaseHistory.GAP)));
        assertEquals(List.of(), ReleaseHistory.cluster(List.of(), ReleaseHistory.GAP));
    }

    @Test
    void aLogIsLaidOverTheGroupItNamesAndAnUnmatchedLogIsStillARelease() {
        ReleaseLog matching = ReleaseLog.parse(Path.of("releases/2026-09-05-0018.md"), "# Release 2026-09-05 00:18\n\n"
                + "| module | version | tag | changelog | jitpack | actions |\n|---|---|---|---|---|---|\n"
                + "| botmaker-cli | 0.0.9 | v0.0.9 | stamped | BROKEN | success (1) |\n"
                + "| botmaker-sdk | 1.1.5 | v1.1.5 | stamped | ok (resolves clean) | success (1) |\n");
        ReleaseLog orphan = ReleaseLog.parse(Path.of("releases/2026-08-01-0900.md"), "# Release 2026-08-01 09:00\n\n"
                + "| module | version | tag | changelog | jitpack | actions |\n|---|---|---|---|---|---|\n"
                + "| botmaker-shared | 0.0.1 | v0.0.1 | stamped | ok (resolves clean) | success (1) |\n");

        List<Release> releases = ReleaseHistory.releases(history(), List.of(matching, orphan));

        Release laid = releases.stream().filter(r -> r.log().isPresent() && !r.tags().isEmpty()).findFirst().orElseThrow();
        assertEquals(4, laid.tags().size(), "the log names two; the group's other two tags stay");
        assertEquals("2026-09-05 00:18", laid.log().get().stamp());
        assertEquals(orphan, releases.getLast().log().orElseThrow());
        assertTrue(releases.getLast().tags().isEmpty());
    }

    @Test
    void forEachRefLinesParseAndNonVersionTagsAreSkipped() {
        assertEquals(List.of(tag("botmaker-sdk", "v1.2.0", "2026-09-16T12:23:18+02:00")),
                ReleaseHistory.parse("botmaker-sdk", "v1.2.0 2026-09-16T12:23:18+02:00\nnightly 2026-09-16T12:00:00+02:00\n"
                        + "v1.2.1 not-a-date\n"));
    }

    @Test
    void aCheckoutWithNoModulesHasNoTags(@TempDir Path umbrella) {
        assertEquals(List.of(), ReleaseHistory.tags(umbrella, false));
    }

    @Test
    void aPastReleaseIsDrawnFromTheCacheOverTheLog(@TempDir Path dir) {
        ReleaseLog log = ReleaseLog.parse(Path.of("releases/2026-09-16-1223.md"), "# Release 2026-09-16 12:23\n\n"
                + "| module | version | tag | stage | changelog | jitpack | actions |\n|---|---|---|---|---|---|---|\n"
                + "| botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |\n"
                + "| botmaker-plugin-host | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |\n"
                + "| botmaker-shared | 0.1.0 | v0.1.0 | FAILED | — | not tagged | not tagged |\n"
                + "\n## Errors\n\n**botmaker-shared — release**\n```\ncommit, tag and push: pushing failed\n```\n");
        Release release = new Release(Instant.parse("2026-09-16T10:23:24Z"), List.of(
                tag("botmaker-studio-api", "v0.1.0", "2026-09-16T12:23:24+02:00"),
                tag("botmaker-plugin-host", "v0.1.0", "2026-09-16T12:25:23+02:00")), Optional.of(log));
        VerdictCache cache = VerdictCache.load(dir.resolve("cache.json"));
        Instant now = Instant.parse("2026-09-16T12:00:00Z");
        cache.put("botmaker-plugin-host", "v0.1.0", VerdictCache.Entry.EMPTY
                .withJitpack("published (pom HEAD)", "", now.minusSeconds(7200))
                .withActions("FAILED — CI", "CI: failure — https://example/run\nPluginLoaderTest:171", now.minusSeconds(300)));

        ReleaseProgress progress = ReleaseProgress.past(release, cache, now);

        assertEquals(ReleaseProgress.Phase.PAST, progress.phase());
        List<ReleaseProgress.Lane> lanes = progress.lanes();
        assertEquals(3, lanes.size());
        assertEquals(NodeState.OK, lanes.get(0).state(Step.JITPACK), "the log's built stands in for a poll");
        assertEquals(NodeState.PENDING, lanes.get(0).state(Step.ACTIONS));
        assertEquals(NodeState.OK, lanes.get(1).state(Step.JITPACK));
        assertEquals(NodeState.FAILED, lanes.get(1).state(Step.ACTIONS));
        assertEquals("jitpack: published (pom HEAD), 2h ago · actions: FAILED — CI, 5m ago", lanes.get(1).stage());
        assertEquals(List.of("actions: CI: failure — https://example/run\nPluginLoaderTest:171"), lanes.get(1).errors());
        assertEquals(Optional.of(Duration.ofSeconds(119)), lanes.get(1).elapsed());
        // The module the log names and no tag carries is a lane too, failed where it failed.
        assertEquals(NodeState.FAILED, lanes.get(2).state(Step.TAG));
        assertEquals("pushing failed", lanes.get(2).errors().getFirst().replace("release: commit, tag and push: ", ""));
        assertEquals("broken", progress.health());
    }
}
