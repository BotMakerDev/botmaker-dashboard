package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The save, over a real git repository: one file, one commit, nothing pushed, and a dirty file refused. */
class ChangelogSaveTest {

    private static final String FILE = """
            # Changelog

            ## [Unreleased]

            - old

            ## [1.0.0] — 2026-01-01

            - older
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
        Files.writeString(module.resolve("CHANGELOG.md"), FILE);
        Files.writeString(module.resolve("README.md"), "hello\n");
        git("add", "-A");
        git("commit", "-q", "-m", "first");
    }

    private void git(String... args) {
        Proc run = Proc.run(module, Duration.ofSeconds(20), prepend(args));
        assertTrue(run.ok(), () -> String.join(" ", args) + " failed: " + run.out());
    }

    private static String[] prepend(String[] args) {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return command;
    }

    @Test
    void oneSectionIsWrittenAndOneFileIsCommitted() throws IOException {
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, "botmaker-session");
        assertTrue(doc.hasSection());
        assertFalse(doc.dirty());

        ChangelogEdit.Saved saved = ChangelogEdit.save(umbrella, "botmaker-session", doc, "- new");
        assertTrue(saved.committed(), saved.message());
        assertTrue(saved.message().contains("not pushed"), saved.message());

        assertTrue(Files.readString(module.resolve("CHANGELOG.md")).contains("## [Unreleased]\n\n- new\n"));
        assertEquals("CHANGELOG.md", Proc.run(module, Duration.ofSeconds(20),
                "git", "show", "--name-only", "--format=", "HEAD").out().strip());
        assertEquals("", Proc.run(module, Duration.ofSeconds(20), "git", "status", "--porcelain").out().strip());
    }

    @Test
    void aChangelogSomebodyWasAlreadyEditingIsRefused() throws IOException {
        Files.writeString(module.resolve("CHANGELOG.md"), FILE + "\n- in flight\n");
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, "botmaker-session");
        assertTrue(doc.dirty());

        ChangelogEdit.Saved saved = ChangelogEdit.save(umbrella, "botmaker-session", doc, "- new");
        assertFalse(saved.committed());
        assertTrue(saved.message().contains("already had uncommitted changes"), saved.message());
        assertTrue(Files.readString(module.resolve("CHANGELOG.md")).contains("- in flight"),
                "the file somebody was editing is untouched");
    }

    @Test
    void aSaveThatChangesNothingCommitsNothing() {
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, "botmaker-session");
        ChangelogEdit.Saved saved = ChangelogEdit.save(umbrella, "botmaker-session", doc, "- old");
        assertFalse(saved.committed());
        assertTrue(saved.message().contains("Nothing changed"), saved.message());
    }

    @Test
    void aModuleWithNoChangelogReadsAsAbsentRatherThanEmpty() {
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, "botmaker-pilot");
        assertFalse(doc.exists());
        assertFalse(doc.hasSection());
    }
}
