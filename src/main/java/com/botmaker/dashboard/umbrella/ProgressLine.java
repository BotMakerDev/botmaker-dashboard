package com.botmaker.dashboard.umbrella;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line a release child wrote, with the moment it wrote it — the text {@link ReleaseProgress} is read from.
 *
 * <p>The child stamps every line itself ({@link #format}) because the window may not be there to: it can
 * be closed and reopened mid-release, and a lane's elapsed time read off the moment the window happened
 * to read the file would be the window's history, not the release's.
 *
 * <p>Its own file since 2026-09-29: the parsing is what {@link ReleaseJob} writes, {@link JobTail} reads and
 * {@link ReleaseProgress} interprets, and it sat inside the last of the three.
 *
 * @param at   when it was written; empty for a line the child did not stamp — a JVM warning on stderr
 */
public record ProgressLine(Optional<Instant> at, String text) {

    /** {@code ReleaseLog.write}: which file the chain is keeping. */
    private static final Pattern LOG = Pattern.compile("^Release log: releases/(\\S+\\.md)$");

    public static String format(Instant at, String text) {
        return at + " " + text;
    }

    /** A stamped line, or the whole thing as text when the first word is not an instant. */
    public static ProgressLine parse(String raw) {
        int space = raw.indexOf(' ');
        if (space > 0) {
            try {
                return new ProgressLine(Optional.of(Instant.parse(raw.substring(0, space))),
                        raw.substring(space + 1));
            } catch (DateTimeParseException e) {
                // Not stamped: fall through.
            }
        }
        return new ProgressLine(Optional.empty(), raw);
    }

    public static List<ProgressLine> parseAll(String text) {
        List<ProgressLine> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            if (!raw.isEmpty()) {
                lines.add(parse(raw));
            }
        }
        return List.copyOf(lines);
    }

    /** The log file the child's chain is keeping, once it has said so. */
    public static Optional<String> logName(List<ProgressLine> lines) {
        for (ProgressLine line : lines) {
            Matcher match = LOG.matcher(line.text());
            if (match.matches()) {
                return Optional.of(match.group(1));
            }
        }
        return Optional.empty();
    }

    /**
     * The last line {@link ReleaseJob} writes, and so the only way to tell "finished" from "killed".
     *
     * <p>Written by the child rather than inferred from the log, because a refusal and a decide-pass error
     * leave no log at all, and a run that died after its last tag leaves a log that looks finished.
     */
    public enum Ending {
        DONE("done"), UNPUSHED("done, a branch was not pushed"), REFUSED("refused"), STOPPED("stopped");

        public static final String PREFIX = "release-job: ";

        private final String word;

        Ending(String word) {
            this.word = word;
        }

        public String line(String detail) {
            return PREFIX + word + (detail.isBlank() ? "" : " — " + detail);
        }

        static Optional<Ending> of(String text) {
            if (!text.startsWith(PREFIX)) {
                return Optional.empty();
            }
            String rest = text.substring(PREFIX.length());
            // Longest first: "done, a branch…" also starts with "done".
            for (Ending ending : List.of(UNPUSHED, DONE, REFUSED, STOPPED)) {
                if (rest.equals(ending.word) || rest.startsWith(ending.word + " — ")) {
                    return Optional.of(ending);
                }
            }
            return Optional.empty();
        }

        ReleaseProgress.Phase phase() {
            return switch (this) {
                case DONE -> ReleaseProgress.Phase.DONE;
                case UNPUSHED -> ReleaseProgress.Phase.UNPUSHED;
                case REFUSED -> ReleaseProgress.Phase.REFUSED;
                case STOPPED -> ReleaseProgress.Phase.STOPPED;
            };
        }
    }
}
