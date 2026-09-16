package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and rewriting one module's {@code ## [Unreleased]} section, and committing it where it lives.
 *
 * <p><b>Only that section is replaced, and every other byte survives.</b> It is the rule {@code Stamp} states
 * in the release library: a changelog is a file the maintainer has been editing all week, so a whole-file
 * rewrite through {@code readAllLines}/{@code join("\n")} would normalise line endings and the final newline
 * and carry that reformatting in the commit as if it were the edit. So the section is spliced in by offset:
 * the heading, the body up to the next heading, and nothing else.
 *
 * <p><b>A missing section is inserted rather than refused</b>, immediately before the first {@code ## }
 * heading — which is where a new release's notes belong, above the last stamped one — or at the end of a
 * file that has none. That is the state {@code ChangelogGate} refuses a release over, and the point of this
 * editor is to leave it behind.
 *
 * <p><b>The save refuses a {@code CHANGELOG.md} that was already modified</b> when the tab read it. The
 * commit this makes carries one file, so an edit somebody had in flight would be committed with a message
 * about something else, in a repository whose working tree the operator believed they controlled. Nothing is
 * pushed: the release's own pointer commit is where a push gets decided.
 */
public final class ChangelogEdit {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(20);

    /** {@code ## [Unreleased]}, however it is spaced — the same heading {@link Changelog} looks for. */
    private static final Pattern UNRELEASED =
            Pattern.compile("(?im)^[ ]{0,3}##[ \t]*\\[[ \t]*unreleased[ \t]*][ \t]*$");

    /** Any {@code ## } heading. What ends a section, and where a missing one is inserted. */
    private static final Pattern HEADING = Pattern.compile("(?m)^[ ]{0,3}##[ \t]");

    /** A stamped heading — {@code ## [1.1.0] — 2026-09-16}. The newest one is the tone to write in. */
    private static final Pattern STAMPED =
            Pattern.compile("(?m)^[ ]{0,3}##[ \t]*\\[[ \t]*\\d+\\.\\d+\\.\\d+[ \t]*]");

    /**
     * One module's changelog as the tab opened it.
     *
     * @param file       where it is, whether or not it exists
     * @param text       the whole file, or {@code ""} when there is none
     * @param exists     whether the file is there at all — {@code botmaker-pilot} has none
     * @param unreleased the body under {@code ## [Unreleased]}, or {@code ""}
     * @param hasSection whether that heading exists, which is what the release's gate asks
     * @param dirty      whether git already had changes to this file when it was read
     */
    public record Doc(Path file, String text, boolean exists, String unreleased, boolean hasSection,
                      boolean dirty) {
    }

    private ChangelogEdit() {
    }

    /** Reads a module's changelog, and asks git whether the file was already modified. */
    public static Doc read(Path umbrella, String module) {
        Path dir = umbrella.resolve(module);
        Path file = dir.resolve("CHANGELOG.md");
        if (!Files.isRegularFile(file)) {
            return new Doc(file, "", false, "", false, false);
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return new Doc(file, "", false, "", false, false);
        }
        Optional<String> body = section(text);
        return new Doc(file, text, true, body.orElse(""), body.isPresent(), modified(dir));
    }

    /**
     * The body under {@code ## [Unreleased]}, up to the next {@code ## } heading.
     *
     * <p>Empty ({@code Optional.of("")}) and absent are different answers and the caller needs both: a
     * section with nothing under it is prose somebody has yet to write, and no section at all is the shape
     * the release refuses.
     */
    public static Optional<String> section(String text) {
        Matcher heading = UNRELEASED.matcher(text);
        if (!heading.find()) {
            return Optional.empty();
        }
        return Optional.of(text.substring(bodyStart(text, heading.end()), bodyEnd(text, heading.end())));
    }

    /** The newest stamped section, heading and all — what the drafter is shown as the house style. */
    public static Optional<String> lastStamped(String text) {
        Matcher heading = STAMPED.matcher(text);
        if (!heading.find()) {
            return Optional.empty();
        }
        return Optional.of(text.substring(heading.start(), bodyEnd(text, heading.end())).strip());
    }

    /**
     * The whole file with the {@code [Unreleased]} body replaced by {@code body}.
     *
     * <p>Pure, so what is written can be asserted without a repository. The line ending is the file's own:
     * a changelog written on Windows keeps its {@code \r\n}, since the diff is the thing being read and a
     * whole-file ending flip hides one paragraph in a thousand changed lines.
     */
    public static String replace(String text, String body) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String written = body.strip().isEmpty() ? "" : body.strip().replace("\r\n", "\n").replace("\n", newline);
        String block = newline + newline + (written.isEmpty() ? "" : written + newline + newline);
        Matcher heading = UNRELEASED.matcher(text);
        if (heading.find()) {
            return text.substring(0, heading.end()) + block + text.substring(bodyEnd(text, heading.end()));
        }
        String inserted = "## [Unreleased]" + block;
        Matcher first = HEADING.matcher(text);
        if (first.find()) {
            return text.substring(0, first.start()) + inserted + text.substring(first.start());
        }
        String ending = text.isEmpty() || text.endsWith(newline) ? "" : newline;
        return text + ending + newline + inserted;
    }

    /**
     * Writes the section and commits that one file inside the submodule. Nothing is pushed.
     *
     * @return the commit's one-line description, or the reason nothing was committed
     */
    public static Saved save(Path umbrella, String module, Doc opened, String body) {
        Path dir = umbrella.resolve(module);
        if (opened.dirty()) {
            return new Saved(false, "CHANGELOG.md already had uncommitted changes when this tab read it — "
                    + "commit or discard them first, so this commit carries only what was typed here.");
        }
        if (modified(dir)) {
            return new Saved(false, "CHANGELOG.md changed on disk since this tab read it — reload first.");
        }
        String written = replace(opened.text(), body);
        if (written.equals(opened.text())) {
            return new Saved(false, "Nothing changed, so nothing was committed.");
        }
        try {
            Files.writeString(opened.file(), written);
        } catch (IOException e) {
            return new Saved(false, "Could not write " + opened.file() + ": " + e.getMessage());
        }
        Proc commit = Proc.run(dir, GIT_TIMEOUT, "git", "commit",
                "-m", "docs: changelog for the next release", "--", "CHANGELOG.md");
        if (!commit.ok()) {
            return new Saved(false, "Written, but git refused the commit: " + commit.out().strip());
        }
        Proc head = Proc.run(dir, GIT_TIMEOUT, "git", "log", "-1", "--format=%h %s");
        return new Saved(true, "Committed in " + module + ": " + head.firstLine() + " (not pushed)");
    }

    /** What a save did, in one sentence the status line shows either way. */
    public record Saved(boolean committed, String message) {
    }

    /**
     * The commits since a module's newest tag, newest first — the raw material for the notes.
     *
     * <p>The subject and the body of each, because the body is where a commit says what a consumer of the
     * module notices; a subject line alone drafts a changelog that reads like a list of refactors.
     */
    public static String commitsSince(Path umbrella, String module, Optional<String> latestTag) {
        Path dir = umbrella.resolve(module);
        List<String> command = latestTag
                .map(tag -> List.of("git", "log", tag + "..HEAD", "--no-merges", "--format=%h %s%n%b"))
                .orElse(List.of("git", "log", "--no-merges", "-40", "--format=%h %s%n%b"));
        Proc log = Proc.run(dir, GIT_TIMEOUT, command);
        return log.ok() ? log.out().strip() : "";
    }

    /** {@code git diff --stat} over the same span — what moved, without the diff itself. */
    public static String diffStat(Path umbrella, String module, Optional<String> latestTag) {
        Path dir = umbrella.resolve(module);
        List<String> command = latestTag
                .map(tag -> List.of("git", "diff", "--stat", tag + "..HEAD"))
                .orElse(List.of("git", "diff", "--stat", "HEAD"));
        Proc diff = Proc.run(dir, GIT_TIMEOUT, command);
        return diff.ok() ? diff.out().strip() : "";
    }

    /** Whether git has uncommitted changes to this module's {@code CHANGELOG.md}. */
    private static boolean modified(Path dir) {
        Proc status = Proc.run(dir, GIT_TIMEOUT, "git", "status", "--porcelain", "--", "CHANGELOG.md");
        return status.ok() && !status.out().isBlank();
    }

    /** The body starts after the heading's own line ending, so the heading itself is never rewritten. */
    private static int bodyStart(String text, int headingEnd) {
        int nl = text.indexOf('\n', headingEnd);
        return nl < 0 ? text.length() : nl + 1;
    }

    /** A section ends at the next {@code ## } heading, or at the end of the file. */
    private static int bodyEnd(String text, int headingEnd) {
        Matcher next = HEADING.matcher(text);
        return next.find(headingEnd) ? next.start() : text.length();
    }
}
