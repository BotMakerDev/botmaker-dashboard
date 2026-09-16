package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Version;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Every release this checkout's tags describe, whether or not anything wrote a log for it.
 *
 * <p><b>The tags are the record; a log is enrichment.</b> Until 2026-09-16 the Releases tab listed
 * {@code releases/*.md} and nothing else, and the release cut that day died before writing one — four tags on
 * origin, and a tab whose newest entry was eleven days old. A tag cannot be missing for a release that
 * happened, so the tab reads tags first and lays a log over the group it describes.
 *
 * <p><b>Grouping is by time, and by one rule a release cannot break.</b> Tags are sorted by date and a new
 * release starts when the gap to the previous tag exceeds {@link #GAP}, <i>or</i> when the module already has
 * a tag in the current group — a release tags each module once. The gap alone does not work on this project's
 * history: the 2026-09-04 23:06 and 23:18 releases are twelve minutes apart and tag the same seven modules,
 * while tags within one release are at most one JitPack wait apart (ten minutes). Fifteen sits between.
 *
 * <p><b>A tag's date is its commit's</b>, since every tag here is lightweight. The release commits right before
 * it tags, so that is the moment of the release — except for a module whose release commit was skipped
 * because nothing changed (the pilot, a resumed release), whose tag then dates from an older commit and can
 * land in an earlier group. Nothing records the push time locally; the log, where one exists, is the
 * correction.
 */
public final class ReleaseHistory {

    /** The longest silence inside one release: a JitPack wait is ten minutes at most. */
    public static final Duration GAP = Duration.ofMinutes(15);

    private ReleaseHistory() {
    }

    /** One module's tag and when it was made. */
    public record TagRow(String module, String tag, Instant date) {
    }

    /**
     * One release.
     *
     * @param start the first tag's date, or the log's file time for a log that matches no tag
     * @param tags  the group's tags in date order — empty only for a log nothing on origin matches
     * @param log   the release log laid over this group, when one matches
     */
    public record Release(Instant start, List<TagRow> tags, Optional<ReleaseLog> log) {

        public Release {
            tags = List.copyOf(tags);
        }

        /** How long from the first tag to the last. */
        public Duration span() {
            return tags.isEmpty() ? Duration.ZERO : Duration.between(tags.getFirst().date(), tags.getLast().date());
        }

        /** Modules this release reached: its tags, plus any a log names that never got one. */
        public int moduleCount() {
            Set<String> modules = new HashSet<>();
            tags.forEach(t -> modules.add(t.module()));
            log.ifPresent(l -> l.rows().forEach(r -> modules.add(r.module())));
            return modules.size();
        }
    }

    // ---- reading ------------------------------------------------------------------------------------

    /**
     * Every version tag of every module the release library knows, with its date.
     *
     * @param fetch whether to {@code git fetch --tags} each module first — what {@code Tags.latest} does. A
     *              network call per module, so the tab reads local tags first and fetches after.
     */
    public static List<TagRow> tags(Path umbrella, boolean fetch) {
        List<TagRow> rows = new ArrayList<>();
        for (Module module : Module.values()) {
            Path dir = umbrella.resolve(module.directory());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (fetch) {
                Proc.run(dir, Duration.ofSeconds(30), "git", "fetch", "--tags", "--quiet", "origin");
            }
            Proc listing = Proc.run(dir, Duration.ofSeconds(15), "git", "for-each-ref", "refs/tags",
                    "--format=%(refname:short) %(creatordate:iso-strict)");
            if (listing.ok()) {
                rows.addAll(parse(module.directory(), listing.out()));
            }
        }
        return List.copyOf(rows);
    }

    /** {@code v1.1.0 2026-09-16T12:23:18+02:00} lines into rows; anything not a version tag is skipped. */
    static List<TagRow> parse(String module, String forEachRef) {
        List<TagRow> rows = new ArrayList<>();
        for (String line : forEachRef.lines().toList()) {
            String[] parts = line.strip().split(" ");
            if (parts.length != 2 || Version.parse(parts[0]).isEmpty()) {
                continue;
            }
            try {
                rows.add(new TagRow(module, parts[0], OffsetDateTime.parse(parts[1]).toInstant()));
            } catch (DateTimeParseException e) {
                // A tag with no readable date cannot be placed in time, and a guessed place is worse than none.
            }
        }
        return rows;
    }

    // ---- grouping -----------------------------------------------------------------------------------

    /** The tags, grouped into releases, oldest first. Pure. */
    public static List<List<TagRow>> cluster(List<TagRow> tags, Duration gap) {
        List<TagRow> sorted = tags.stream().sorted(Comparator.comparing(TagRow::date)).toList();
        List<List<TagRow>> groups = new ArrayList<>();
        List<TagRow> current = new ArrayList<>();
        Set<String> modules = new HashSet<>();
        for (TagRow tag : sorted) {
            boolean split = !current.isEmpty()
                    && (Duration.between(current.getLast().date(), tag.date()).compareTo(gap) > 0
                    || modules.contains(tag.module()));
            if (split) {
                groups.add(List.copyOf(current));
                current.clear();
                modules.clear();
            }
            current.add(tag);
            modules.add(tag.module());
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return groups;
    }

    /**
     * The releases, newest first: tag groups with the logs that describe them laid over.
     *
     * <p>A log goes to the group sharing the most {@code (module, tag)} pairs with it, and each group takes one
     * log. A log that matches no group — every tag it names deleted, say — is still a release, listed by its own
     * time, because the tab must not hide what the record says either.
     */
    public static List<Release> releases(List<TagRow> tags, List<ReleaseLog> logs) {
        List<List<TagRow>> groups = cluster(tags, GAP);
        List<Optional<ReleaseLog>> laid = new ArrayList<>(groups.stream().map(g -> Optional.<ReleaseLog>empty()).toList());
        List<ReleaseLog> unmatched = new ArrayList<>();

        for (ReleaseLog log : logs) {
            Set<String> named = new LinkedHashSet<>();
            log.rows().forEach(r -> named.add(r.module() + "@" + r.tag()));
            int best = -1;
            long bestShared = 0;
            for (int i = 0; i < groups.size(); i++) {
                long shared = groups.get(i).stream().filter(t -> named.contains(t.module() + "@" + t.tag())).count();
                if (shared > bestShared && laid.get(i).isEmpty()) {
                    best = i;
                    bestShared = shared;
                }
            }
            if (best >= 0) {
                laid.set(best, Optional.of(log));
            } else {
                unmatched.add(log);
            }
        }

        List<Release> releases = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            releases.add(new Release(groups.get(i).getFirst().date(), groups.get(i), laid.get(i)));
        }
        for (ReleaseLog log : unmatched) {
            releases.add(new Release(stampTime(log), List.of(), Optional.of(log)));
        }
        releases.sort(Comparator.comparing(Release::start).reversed());
        return List.copyOf(releases);
    }

    /** A log's own minute, from its file name ({@code 2026-09-05-0050.md}), in this machine's zone. */
    private static Instant stampTime(ReleaseLog log) {
        String name = log.file() == null ? "" : log.file().getFileName().toString().replace(".md", "");
        try {
            return java.time.LocalDateTime.parse(name, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }
}
