package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Tags;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Every module that has no {@code ## [Unreleased]} section, given one — drafted or copied, and committed.
 *
 * <p><b>Two ways a section gets written, decided by the commits.</b> A module with commits since its newest
 * tag has something to say, and Claude drafts it ({@link ClaudeDraft}) from those commits. A module with
 * <em>no</em> commits since its tag is being re-released only because an upstream moved — its
 * {@code .deps.env} pins will change and nothing else — and there is nothing to draft: the section is one
 * line saying so, followed by the previous section, so a reader of the release notes sees what the module
 * does rather than an empty heading. No model is asked for that; a model would be asked to invent.
 *
 * <p><b>Each section is committed as it lands</b>, inside the module, through the same
 * {@link ChangelogEdit#save} the tab's own button uses — one file, one commit, nothing pushed. That is what
 * makes this usable from the Release tab: the release's decide pass reads the committed changelog, so a
 * draft left in a text area would not lift the gate.
 *
 * <p><b>The drafter is a parameter</b>, so the rule about which modules are copied and which are drafted is
 * tested without a model, a {@code cswap} or a network.
 */
public final class ChangelogDrafts {

    /** What a module's Claude draft is asked of — {@link ClaudeDraft#draft} in production, a stub in a test. */
    @FunctionalInterface
    public interface Drafter {
        ClaudeDraft.Result draft(Path umbrella, ClaudeDraft.Request request, Consumer<String> progress);
    }

    /** What happened to one module. */
    public enum Outcome {
        /** Claude wrote the section and it is committed. */
        DRAFTED,
        /** No commits since the tag: the previous section was carried forward and committed. */
        COPIED,
        /** Nothing was written — Claude was unavailable or every account refused, or the save was refused. */
        FAILED
    }

    public record Result(String module, Outcome outcome, String message) {

        public boolean written() {
            return outcome != Outcome.FAILED;
        }
    }

    /** The one line a re-release with no source changes says about itself. */
    static final String NO_CHANGES = "No source changes since %s; re-released for updated upstream pins.";

    private ChangelogDrafts() {
    }

    /**
     * The modules among {@code modules} whose changelog the release gate would refuse: a file exists, and
     * it has no {@code [Unreleased]} section.
     *
     * <p>A module the gate exempts ({@link Module#hasChangelog()} false) never needs one; a module whose
     * changelog is missing altogether is not this class's to create.
     */
    public static List<String> needing(Path umbrella, List<String> modules) {
        List<String> out = new ArrayList<>();
        for (String module : modules) {
            boolean exempt = Module.byDirectory(module).map(m -> !m.hasChangelog()).orElse(false);
            if (exempt) {
                continue;
            }
            ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, module);
            if (doc.exists() && !doc.hasSection()) {
                out.add(module);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Writes a section for each of {@code modules}, copying or drafting as the commits decide, and commits
     * each one. One result per module, in order; a failure does not stop the rest.
     */
    public static List<Result> draftAll(Path umbrella, List<String> modules, Drafter drafter,
                                        Consumer<String> progress) {
        List<Result> out = new ArrayList<>();
        for (String module : modules) {
            progress.accept("Reading " + module + " …");
            out.add(one(umbrella, module, drafter, progress));
        }
        return List.copyOf(out);
    }

    private static Result one(Path umbrella, String module, Drafter drafter, Consumer<String> progress) {
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, module);
        if (!doc.exists()) {
            return new Result(module, Outcome.FAILED, "has no CHANGELOG.md");
        }
        if (doc.dirty()) {
            return new Result(module, Outcome.FAILED,
                    "CHANGELOG.md has uncommitted changes — commit or discard them first");
        }
        Optional<String> tag = Module.byDirectory(module)
                .flatMap(m -> Tags.latest(umbrella, m).flatMap(v -> Tags.existingRef(umbrella, m, v)));
        String commits = ChangelogEdit.commitsSince(umbrella, module, tag);

        if (commits.isBlank() && tag.isPresent()) {
            String body = copied(tag.get(), ChangelogEdit.lastStamped(doc.text()));
            ChangelogEdit.Saved saved = ChangelogEdit.save(umbrella, module, doc, body);
            return saved.committed()
                    ? new Result(module, Outcome.COPIED, "no commits since " + tag.get()
                            + " — previous section carried forward")
                    : new Result(module, Outcome.FAILED, saved.message());
        }

        ClaudeDraft.Request request = new ClaudeDraft.Request(module, preamble(doc.text()),
                ChangelogEdit.lastStamped(doc.text()), commits, ChangelogEdit.diffStat(umbrella, module, tag));
        ClaudeDraft.Result draft = drafter.draft(umbrella, request,
                line -> progress.accept(module + ": " + line));
        if (!draft.drafted()) {
            return new Result(module, Outcome.FAILED, draft.message());
        }
        ChangelogEdit.Saved saved = ChangelogEdit.save(umbrella, module, doc, draft.text());
        return saved.committed()
                ? new Result(module, Outcome.DRAFTED, "drafted with " + draft.account())
                : new Result(module, Outcome.FAILED, saved.message());
    }

    /**
     * The copy rule's text: the one line, then the previous section's body without its heading.
     *
     * <p>Without the heading, because it would sit under {@code ## [Unreleased]} as a second {@code ## }
     * line and end the section right there — the gate's extractor and {@link ChangelogEdit#section} both
     * stop at the next heading. Pure, so the text is asserted rather than trusted.
     */
    static String copied(String tag, Optional<String> lastStamped) {
        String line = NO_CHANGES.formatted(tag);
        String previous = lastStamped.map(ChangelogDrafts::withoutHeading).orElse("").strip();
        return previous.isEmpty() ? line : line + "\n\n" + previous;
    }

    private static String withoutHeading(String section) {
        int nl = section.indexOf('\n');
        return nl < 0 ? "" : section.substring(nl + 1);
    }

    /** Everything above the first {@code ## } heading: what the module says about itself. */
    public static String preamble(String text) {
        int first = text.indexOf("\n## ");
        return first < 0 ? text : text.substring(0, first);
    }
}
