package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A past release, drawn with the same lanes as a running one — so the two look alike and are read alike.
 *
 * <p>The Releases tab's half of {@link ReleaseProgress}, in its own file since 2026-09-29: a running release
 * is read from what its child writes, a finished one from its tags, its log and the verdict cache, and the
 * two readings share nothing but the lane they end in.
 */
public final class PastProgress {

    private PastProgress() {
    }

    /**
     * Every tag in the group is a lane whose commit and tag are done, since the tag exists. Its JitPack and
     * Actions nodes come from the newest answer there is: the cache's polled verdict, else the log's cell, else
     * {@code pending}. A module the log names with no tag — {@code FAILED}, {@code not reached} — is a lane too,
     * read the way a live one is. The lane's stage line carries the words behind each node and how old they are,
     * because {@code published (pom HEAD)} and {@code ok (resolves clean)} are both green and are not the same
     * answer.
     *
     * <p>A lane's elapsed time is the gap since the previous tag: what the timeline is for is where the minutes
     * went, and for a finished release that is the time between tags.
     */
    /**
     * Whether a polled verdict outranks the log's cell. It is newer, so it does — except where it is no answer
     * (still pending, unknown, or {@code no run on …}) and the log recorded a green one. A finished tag's run
     * does not disappear, so that pair is a poll that failed, and a cache that kept it showed a release that
     * succeeded as Failed until the cache aged out (2026-10-01).
     */
    static boolean outranks(String cached, String logged) {
        if (cached.isBlank()) {
            return false;
        }
        if (logged.isBlank() || ReleaseLog.Health.of(logged) != ReleaseLog.Health.OK) {
            return true;
        }
        return ReleaseLog.Health.of(cached) != ReleaseLog.Health.PENDING
                && !cached.toLowerCase().startsWith("no run");
    }

    public static ReleaseProgress of(ReleaseHistory.Release release, VerdictCache cache, Instant now) {
        Optional<ReleaseLog> log = release.log();
        List<ReleaseLog.Row> rows = new ArrayList<>();
        List<ReleaseLog.Problem> problems = new ArrayList<>();
        Map<String, Duration> gaps = new HashMap<>();
        Map<String, String> ages = new HashMap<>();
        Set<String> seen = new HashSet<>();

        Instant previous = null;
        for (ReleaseHistory.TagRow tag : release.tags()) {
            String key = tag.module() + "@" + tag.tag();
            seen.add(key);
            gaps.put(key, previous == null ? Duration.ZERO : Duration.between(previous, tag.date()));
            previous = tag.date();

            Optional<ReleaseLog.Row> logged = log.flatMap(l -> l.rows().stream()
                    .filter(r -> r.module().equals(tag.module()) && r.tag().equals(tag.tag())).findFirst());
            VerdictCache.Entry entry = cache.get(tag.module(), tag.tag());
            boolean onJitpack = Module.byDirectory(tag.module())
                    .map(com.botmaker.cli.release.ReleaseLog::onJitpack).orElse(true);

            String loggedJitpack = logged.map(ReleaseLog.Row::jitpack).orElse("");
            String loggedActions = logged.map(ReleaseLog.Row::actions).orElse("");
            boolean cachedJitpack = outranks(entry.jitpack(), loggedJitpack);
            boolean cachedActions = outranks(entry.actions(), loggedActions);
            String jitpack = !onJitpack ? "n/a (not a Maven artifact)"
                    : cachedJitpack ? entry.jitpack()
                    : !loggedJitpack.isBlank() ? loggedJitpack : "pending";
            String actions = cachedActions ? entry.actions() : !loggedActions.isBlank() ? loggedActions : "pending";
            // The cached poll's run outranks the log's, for the same reason its verdict does: it is newer.
            String actionsUrl = cachedActions && !entry.actionsUrl().isBlank() ? entry.actionsUrl()
                    : logged.map(ReleaseLog.Row::actionsUrl).orElse("");
            String stage = logged.map(ReleaseLog.Row::stage).filter(s -> !s.isBlank()).orElse("tagged");
            // The tag exists, so whatever the log last said about how far it got, it got at least this far.
            if (stage.equals("pending") || stage.equals("FAILED") || stage.equals("not reached")) {
                stage = "tagged";
            }
            rows.add(new ReleaseLog.Row(tag.module(), tag.tag().replaceFirst("^v", ""), tag.tag(),
                    logged.map(ReleaseLog.Row::changelog).orElse(""), jitpack, actions, stage,
                    logged.map(ReleaseLog.Row::elapsed).orElse(""), actionsUrl));

            for (String kind : List.of("jitpack", "actions")) {
                String cached = kind.equals("jitpack") ? entry.jitpackError() : entry.actionsError();
                boolean polled = kind.equals("jitpack") ? cachedJitpack : cachedActions;
                if (polled) {
                    if (!cached.isBlank()) {
                        problems.add(new ReleaseLog.Problem(tag.module(), kind, cached));
                    }
                } else {
                    log.ifPresent(l -> l.problemsFor(tag.module()).stream()
                            .filter(p -> p.kind().equals(kind)).forEach(problems::add));
                }
            }
            ages.put(key, "jitpack: " + jitpack
                    + (onJitpack && cachedJitpack ? ", " + VerdictCache.age(entry.jitpackTime(), now) : "")
                    + " · actions: " + actions
                    + (cachedActions ? ", " + VerdictCache.age(entry.actionsTime(), now) : ""));
        }
        // What the log names and no tag carries: the module that failed and those never reached.
        log.ifPresent(l -> {
            for (ReleaseLog.Row row : l.rows()) {
                if (!seen.contains(row.module() + "@" + row.tag())) {
                    rows.add(row);
                    l.problemsFor(row.module()).stream().filter(p -> p.kind().equals("release")).forEach(problems::add);
                }
            }
        });

        ReleaseLog synthetic = new ReleaseLog(log.map(ReleaseLog::file).orElse(null),
                log.map(ReleaseLog::stamp).orElse(""), rows, problems);
        List<ReleaseProgress.Lane> lanes = new ArrayList<>();
        for (ReleaseLog.Row row : rows) {
            String key = row.module() + "@" + row.tag();
            ReleaseProgress.Lane lane = ReleaseProgress.settledLane(row, synthetic, now);
            // What the release measured beats the gap between tags. The gap is a proxy — it counts the wait
            // for the previous module's JitPack build as this one's time — and it is all a log written
            // before 2026-09-19 can offer.
            Optional<Duration> took = ReleaseLog.duration(row.elapsed())
                    .or(() -> Optional.ofNullable(gaps.get(key)));
            lanes.add(new ReleaseProgress.Lane(lane.module(), lane.tag(), ages.getOrDefault(key, row.stage()),
                    lane.steps(), lane.errors(), took, lane.actionsUrl()));
        }
        Duration span = log.map(ReleaseLog::timing).flatMap(t -> ReleaseLog.duration(t.total()))
                .orElseGet(release::span);
        return new ReleaseProgress(ReleaseProgress.Phase.PAST, List.copyOf(lanes), release.start(), span);
    }
}
