package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides copy versus draft, and what each writes — over a real repository, with a drafter
 * that is a lambda. No model is asked anything here.
 */
class ChangelogDraftsTest {

    private static final String STAMPED_ONLY = """
            # Changelog

            What this module is.

            ## [1.0.0] — 2026-01-01

            ### Added

            - the first thing
            """;

    @TempDir
    Path umbrella;

    private Path module;

    @BeforeEach
    void repository() throws IOException {
        module = umbrella.resolve("botmaker-session");
        Files.createDirectories(module);
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        Files.writeString(module.resolve("CHANGELOG.md"), STAMPED_ONLY);
        git("add", "-A");
        git("commit", "-q", "-m", "first");
        git("tag", "v1.0.0");
    }

    // ---- the pure parts ---------------------------------------------------------------------------------

    @Test
    void theCopyRuleSaysWhyAndCarriesThePreviousBodyWithoutItsHeading() {
        String body = ChangelogDrafts.copied("v1.0.0",
                Optional.of("## [1.0.0] — 2026-01-01\n\n### Added\n\n- the first thing"));

        assertTrue(body.startsWith("No source changes since v1.0.0; re-released for updated upstream pins."),
                body);
        assertTrue(body.contains("- the first thing"), body);
        // A second ## line under [Unreleased] would end the section right there.
        assertFalse(body.contains("## [1.0.0]"), body);
    }

    @Test
    void aModuleWithNoPreviousSectionCopiesJustTheLine() {
        assertEquals(ChangelogDrafts.NO_CHANGES.formatted("v1.0.0"),
                ChangelogDrafts.copied("v1.0.0", Optional.empty()));
    }

    // ---- which modules need one -------------------------------------------------------------------------

    @Test
    void onlyAModuleWithAFileAndNoSectionNeedsOne() throws IOException {
        Path pilot = umbrella.resolve("botmaker-pilot");     // exempt: no changelog by rule
        Files.createDirectories(pilot);
        Path ready = umbrella.resolve("botmaker-shared");    // has a section already
        Files.createDirectories(ready);
        Files.writeString(ready.resolve("CHANGELOG.md"), "# Changelog\n\n## [Unreleased]\n\n- x\n");

        List<String> needing = ChangelogDrafts.needing(umbrella,
                List.of("botmaker-session", "botmaker-pilot", "botmaker-shared", "botmaker-nowhere"));

        assertEquals(List.of("botmaker-session"), needing);
    }

    // ---- what draftAll writes ---------------------------------------------------------------------------

    @Test
    void noCommitsSinceTheTagCopiesAndCommitsWithoutAskingClaude() throws IOException {
        boolean[] asked = {false};

        List<ChangelogDrafts.Result> results = ChangelogDrafts.draftAll(umbrella, List.of("botmaker-session"),
                (root, request, progress) -> {
                    asked[0] = true;
                    return new ClaudeDraft.Result("should not be used", "slot 1", "");
                }, line -> { });

        assertFalse(asked[0], "a module with nothing to say is not drafted");
        assertEquals(ChangelogDrafts.Outcome.COPIED, results.getFirst().outcome());
        String text = Files.readString(module.resolve("CHANGELOG.md"));
        assertTrue(text.contains("## [Unreleased]\n\nNo source changes since v1.0.0"), text);
        assertTrue(text.contains("## [1.0.0] — 2026-01-01"), "the stamped section is still there");
        assertTrue(Changelog.hasUnreleased(text));
        assertTrue(Proc.run(module, Duration.ofSeconds(20), "git", "status", "--porcelain").out().isBlank(),
                "committed, not left in the tree");
    }

    @Test
    void commitsSinceTheTagAreDraftedAndCommitted() throws IOException {
        Files.writeString(module.resolve("Thing.java"), "class Thing {}\n");
        git("add", "-A");
        git("commit", "-q", "-m", "feat: a thing consumers notice");
        String[] seen = {""};

        List<ChangelogDrafts.Result> results = ChangelogDrafts.draftAll(umbrella, List.of("botmaker-session"),
                (root, request, progress) -> {
                    seen[0] = request.commits();
                    return new ClaudeDraft.Result("### Added\n\n- a thing", "slot 2", "drafted");
                }, line -> { });

        assertEquals(ChangelogDrafts.Outcome.DRAFTED, results.getFirst().outcome());
        assertTrue(seen[0].contains("feat: a thing consumers notice"), seen[0]);
        String text = Files.readString(module.resolve("CHANGELOG.md"));
        assertTrue(text.contains("## [Unreleased]\n\n### Added\n\n- a thing"), text);
    }

    @Test
    void aDrafterThatAnswersNothingIsAFailureAndTheFileIsUntouched() throws IOException {
        Files.writeString(module.resolve("Thing.java"), "class Thing {}\n");
        git("add", "-A");
        git("commit", "-q", "-m", "feat: something");

        List<ChangelogDrafts.Result> results = ChangelogDrafts.draftAll(umbrella, List.of("botmaker-session"),
                (root, request, progress) -> new ClaudeDraft.Result("", "", "No account could draft this"),
                line -> { });

        assertEquals(ChangelogDrafts.Outcome.FAILED, results.getFirst().outcome());
        assertEquals("No account could draft this", results.getFirst().message());
        assertEquals(STAMPED_ONLY, Files.readString(module.resolve("CHANGELOG.md")));
    }

    private void git(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Proc run = Proc.run(module, Duration.ofSeconds(20), command);
        assertTrue(run.ok(), () -> String.join(" ", args) + " failed: " + run.out());
    }
}
