package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogTest {

    @Test
    void findsTheSectionAReleaseWouldStamp() {
        assertTrue(Changelog.hasUnreleased("# Changelog\n\n## [Unreleased]\n\n### Added\n\n- a thing\n"));
    }

    @Test
    void aStampedChangelogWithAFreshSectionStillCounts() {
        // The ordinary state one release after another: a stamped section AND a new [Unreleased] above it.
        assertTrue(Changelog.hasUnreleased("## [Unreleased]\n\n## [1.0.37] — 2026-09-05\n"));
    }

    @Test
    void aChangelogWithOnlyStampedSectionsDoesNot() {
        assertFalse(Changelog.hasUnreleased("# Changelog\n\n## [1.0.37] — 2026-09-05\n\n- a thing\n"));
    }
}
