package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One committed {@code releases/<YYYY-MM-DD-HHMM>.md} — what a release did, and what became of it.
 *
 * <p>The file exists because reporting is worth nothing if nobody reads it: {@code verify_jitpack} said
 * {@code UNVERIFIED} on three consecutive releases, each was read as a slow JitPack queue, and what it
 * actually meant was that six of eight published modules had stopped compiling on JitPack. So every release
 * writes this table, in the same commit as the submodule pointers, with the full error text under it.
 *
 * <p><b>This class reads the file and never recomputes a cell.</b> What "ok" means is
 * {@code resolve_clean_room}'s answer and what a workflow's verdict is is {@code poll_actions}', and the
 * re-poll button runs {@code ./release.sh --status <file>} for exactly that reason — the two readers cannot
 * be allowed to disagree about the word. {@link Health} classifies the script's own words for colour, which
 * is presentation; the words themselves are shown unchanged.
 *
 * @param file    where it was read from
 * @param stamp   the {@code # Release <stamp>} heading — the log's identity, and what {@code --status} reads back
 * @param rows    the table, in the order the release tagged
 * @param problems the {@code ## Errors} blocks, whole
 */
public record ReleaseLog(Path file, String stamp, List<Row> rows, List<Problem> problems) {

    /** How a verdict should read at a glance. It colours the script's word; it never replaces it. */
    public enum Health {
        /** {@code ok (resolves clean)}, {@code success (1)} — the release worked. */
        OK,
        /** {@code pending}, {@code running (1 of 2)}, {@code unknown (no gh on PATH)} — ask again later. */
        PENDING,
        /** {@code n/a (not a Maven artifact)} — the question does not apply to this module. */
        NA,
        /** Everything else: {@code NOT PUBLISHED}, {@code BROKEN}, {@code FAILED}, {@code no run on v…}. */
        BROKEN;

        /**
         * Classifies a cell.
         *
         * <p>{@code no run on <tag>} counts as broken rather than pending on purpose: a tag that triggered
         * no workflow at all is precisely the failure this log was added to catch — a tag pushed, JitPack
         * green, and the GitHub Release never cut because the workflow died before starting or never fired.
         */
        public static Health of(String cell) {
            String s = cell.toLowerCase();
            if (s.startsWith("n/a")) {
                return NA;
            }
            if (s.startsWith("ok") || s.startsWith("success") || s.startsWith("stamped")) {
                return OK;
            }
            if (s.startsWith("pending") || s.startsWith("running") || s.startsWith("unknown")) {
                return PENDING;
            }
            return BROKEN;
        }
    }

    /** One module's row: {@code | module | version | tag | changelog | jitpack | actions |}. */
    public record Row(String module, String version, String tag,
                      String changelog, String jitpack, String actions) {

        public Health jitpackHealth() {
            return Health.of(jitpack);
        }

        public Health actionsHealth() {
            return Health.of(actions);
        }

        /** The worst of the two, which is what a row should be read as. */
        public Health health() {
            return jitpackHealth() == Health.BROKEN || actionsHealth() == Health.BROKEN
                    ? Health.BROKEN
                    : Health.OK;
        }
    }

    /** One {@code **module — kind**} block from {@code ## Errors}, with the fenced text inside it. */
    public record Problem(String module, String kind, String text) {
    }

    /** True when any row's JitPack or Actions verdict is a failure. */
    public boolean broken() {
        return rows.stream().anyMatch(r -> r.health() == Health.BROKEN);
    }

    /** The errors for one module, whichever column they came from. */
    public List<Problem> problemsFor(String module) {
        return problems.stream().filter(p -> p.module().equals(module)).toList();
    }

    // ---- reading ----------------------------------------------------------------------------------

    /**
     * The logs in a checkout, newest first.
     *
     * <p>Sorted by filename, which is the timestamp: {@code 2026-09-05-1212.md} sorts correctly as text
     * because the stamp is fixed-width and big-endian. That is the same ordering {@code release_status}
     * uses to find the newest log when it is given no argument.
     */
    public static List<Path> list(Path umbrella) {
        Path dir = umbrella.resolve("releases");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static ReleaseLog read(Path file) {
        try {
            return parse(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Re-polls a log: {@code ./release.sh --status <file>}, which rewrites the file in place.
     *
     * <p>Deliberately the script rather than a JitPack HEAD and a {@code gh} call from here. The release
     * itself decided "ok" with {@code resolve_clean_room} — a real {@code dependency:resolve} into a
     * throwaway repository, because a {@code dependency:tree} warns on an unresolvable transitive pom and
     * exits 0 — and a re-poll that asked an easier question would quietly upgrade a broken row to green.
     *
     * <p>It rewrites the file from scratch and is idempotent, so the result is a reviewable diff. Never on
     * the FX thread: it resolves every module's artifacts and calls {@code gh} once per module.
     */
    public static Proc repoll(Path umbrella, Path file) {
        return Proc.run(umbrella, Duration.ofMinutes(10), "./release.sh", "--status", file.toString());
    }

    // ---- parsing ----------------------------------------------------------------------------------

    public static ReleaseLog parse(Path file, String text) {
        String stamp = "";
        List<Row> rows = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();

        String[] lines = text.split("\n", -1);
        String problemModule = null;
        String problemKind = null;
        StringBuilder problemText = null;

        for (String line : lines) {
            if (problemText != null) {                       // inside a fenced error block
                if (line.startsWith("```")) {
                    problems.add(new Problem(problemModule, problemKind, problemText.toString().strip()));
                    problemText = null;
                } else {
                    problemText.append(line).append('\n');
                }
                continue;
            }
            if (stamp.isEmpty() && line.startsWith("# Release ")) {
                stamp = line.substring("# Release ".length()).strip();
                continue;
            }
            if (line.startsWith("**") && line.contains(" — ") && line.endsWith("**")) {
                String head = line.substring(2, line.length() - 2);
                int dash = head.indexOf(" — ");
                problemModule = head.substring(0, dash).strip();
                problemKind = head.substring(dash + 3).strip();
                continue;
            }
            if (line.startsWith("```") && problemModule != null) {
                problemText = new StringBuilder();
                continue;
            }
            Row row = row(line);
            if (row != null) {
                rows.add(row);
            }
        }
        return new ReleaseLog(file, stamp, List.copyOf(rows), List.copyOf(problems));
    }

    /**
     * One table line, or {@code null} for anything else — the header, the {@code |---|} separator and every
     * line of prose. Six cells exactly: a table with a different width is a log this version cannot read,
     * and skipping it is better than showing a row whose columns have shifted by one.
     */
    private static Row row(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|") || trimmed.startsWith("|---")) {
            return null;
        }
        String[] cells = trimmed.substring(1, trimmed.length() - 1).split("\\|", -1);
        if (cells.length != 6) {
            return null;
        }
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].strip();
        }
        if (cells[0].equals("module") || cells[0].isEmpty()) {
            return null;
        }
        return new Row(cells[0], cells[1], cells[2], cells[3], cells[4], cells[5]);
    }
}
