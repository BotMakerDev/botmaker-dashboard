package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One section is replaced and every other byte survives — the rule {@code Stamp} states in the library. */
class ChangelogEditTest {

    private static final String FILE = """
            # Changelog

            What each version changes.

            ## [Unreleased]

            ### Added

            - A thing.

            ## [1.1.0] — 2026-09-16

            ### Fixed

            - An older thing.
            """;

    @Test
    void theSectionIsTheBodyUpToTheNextHeading() {
        assertEquals("""

                ### Added

                - A thing.

                """, ChangelogEdit.section(FILE).orElseThrow());
    }

    @Test
    void anEmptySectionAndAMissingOneAreDifferentAnswers() {
        assertEquals(Optional.of("\n"), ChangelogEdit.section("## [Unreleased]\n\n## [1.0.0] — 2026-01-01\n"));
        assertEquals(Optional.empty(), ChangelogEdit.section("# Changelog\n\n## [1.0.0] — 2026-01-01\n"));
    }

    @Test
    void replacingRewritesOnlyThatSection() {
        String written = ChangelogEdit.replace(FILE, "### Changed\n\n- Something else.");
        assertEquals("""
                # Changelog

                What each version changes.

                ## [Unreleased]

                ### Changed

                - Something else.

                ## [1.1.0] — 2026-09-16

                ### Fixed

                - An older thing.
                """, written);
    }

    @Test
    void anEmptyBodyLeavesTheHeadingAndNothingUnderIt() {
        String written = ChangelogEdit.replace(FILE, "   \n  ");
        assertTrue(written.contains("## [Unreleased]\n\n## [1.1.0]"), written);
        assertTrue(written.endsWith("- An older thing.\n"), "the rest of the file is untouched");
    }

    @Test
    void aMissingSectionIsInsertedAboveTheLastStampedOne() {
        String file = """
                # Changelog

                Preamble.

                ## [1.1.0] — 2026-09-16

                - Old.
                """;
        assertEquals("""
                # Changelog

                Preamble.

                ## [Unreleased]

                ### Added

                - New.

                ## [1.1.0] — 2026-09-16

                - Old.
                """, ChangelogEdit.replace(file, "### Added\n\n- New."));
    }

    @Test
    void aFileWithNoHeadingAtAllGetsTheSectionAtTheEnd() {
        assertEquals("# Changelog\n\n## [Unreleased]\n\n- First.\n\n",
                ChangelogEdit.replace("# Changelog\n", "- First."));
    }

    @Test
    void windowsLineEndingsSurvive() {
        String file = "# Changelog\r\n\r\n## [Unreleased]\r\n\r\n- old\r\n\r\n## [1.0.0] — 2026-01-01\r\n";
        String written = ChangelogEdit.replace(file, "- new\n- newer");
        assertTrue(written.contains("## [Unreleased]\r\n\r\n- new\r\n- newer\r\n\r\n## [1.0.0]"), written);
        assertFalse(written.replace("\r\n", "").contains("\n"), "no bare newline was introduced");
    }

    @Test
    void theLastStampedSectionIsTheHouseStyle() {
        assertEquals("""
                ## [1.1.0] — 2026-09-16

                ### Fixed

                - An older thing.""", ChangelogEdit.lastStamped(FILE).orElseThrow());
        assertEquals(Optional.empty(), ChangelogEdit.lastStamped("# Changelog\n\n## [Unreleased]\n"));
    }
}
