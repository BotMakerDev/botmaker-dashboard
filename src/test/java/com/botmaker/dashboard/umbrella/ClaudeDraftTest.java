package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The argv and the prompt — the two halves of the drafter a test can exercise without spending a token. */
class ClaudeDraftTest {

    @Test
    void theCommandRunsClaudeAsOneCswapAccountWithNoTools() {
        assertEquals(List.of("cswap", "run", "2", "--",
                        "claude", "-p",
                        "--model", "sonnet",
                        "--effort", "medium",
                        "--output-format", "text",
                        "--allowedTools", ""),
                ClaudeDraft.argv(2));
    }

    @Test
    void thePromptCarriesTheFactsAndAsksForTheSectionBodyOnly() {
        String prompt = ClaudeDraft.prompt(new ClaudeDraft.Request("botmaker-session",
                "# Changelog\n\nWhat each version of `botmaker-session` changes.",
                Optional.of("## [0.1.0] — 2026-09-16\n\n### Fixed\n\n- A reaper leak."),
                "a1b2c3d fix: the reaper no longer leaks\n\nIt closed the display first.",
                " session/Reaper.java | 12 ++++---"));

        assertTrue(prompt.contains("botmaker-session"));
        assertTrue(prompt.contains("What each version of `botmaker-session` changes."));
        assertTrue(prompt.contains("house style"));
        assertTrue(prompt.contains("a1b2c3d fix: the reaper no longer leaks"));
        assertTrue(prompt.contains("session/Reaper.java"));
        assertTrue(prompt.contains("Output the section body only"));
        assertTrue(prompt.contains("State no fact the commits and the diff do not support."));
    }

    @Test
    void aModuleWithNoStampedSectionAndNoCommitsStillMakesAPrompt() {
        String prompt = ClaudeDraft.prompt(
                new ClaudeDraft.Request("botmaker-cli", "", Optional.empty(), "", ""));
        assertFalse(prompt.contains("house style"));
        assertTrue(prompt.contains("(none)"));
    }

    @Test
    void aUsageLimitIsNotADraft() {
        // claude exits 0 and prints the sentence, so the exit code alone would commit it as release notes.
        assertTrue(ClaudeDraft.rateLimited("Claude AI usage limit reached|1758030000"));
        assertTrue(ClaudeDraft.rateLimited("You have hit the rate limit. Try again later."));
        assertFalse(ClaudeDraft.rateLimited("### Added\n\n- A limit on the number of retries."));
    }
}
