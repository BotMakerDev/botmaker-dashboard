package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Version;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionTargetsTest {

    private static final Optional<Version> V116 = Optional.of(new Version(1, 1, 6));

    @Test
    void aLevelResolvesAgainstThatModulesOwnTag() {
        assertEquals("1.1.6 → 1.1.7", VersionTargets.forLevel("botmaker-sdk", Level.PATCH, V116));
        assertEquals("1.1.6 → 1.2.0", VersionTargets.forLevel("botmaker-sdk", Level.MINOR, V116));
        assertEquals("1.1.6 → 2.0.0", VersionTargets.forLevel("botmaker-sdk", Level.MAJOR, V116));
    }

    @Test
    void aModuleWithNoTagSaysSoRatherThanShowingZeros() {
        // The script's `cur="0.0.0"` is the arithmetic, not something to display: "0.0.0 → 0.0.1" would
        // read as a version that exists.
        assertEquals("no tag → 0.0.1",
                VersionTargets.forLevel("botmaker-cli", Level.PATCH, Optional.empty()));
    }

    @Test
    void anExactVersionIsShownAsWhatItIsRegardlessOfTheTag() {
        assertEquals("1.1.6 → 3.0.0", VersionTargets.forExact("botmaker-sdk", "3.0.0", V116));
        // Even backwards: refusing that is the release's judgement to make, not this window's.
        assertEquals("1.1.6 → 1.0.0", VersionTargets.forExact("botmaker-sdk", "1.0.0", V116));
    }

    @Test
    void somethingThatIsNotAVersionIsSaidRatherThanGuessed() {
        assertEquals("not a version", VersionTargets.forExact("botmaker-sdk", "1.2", V116));
        assertEquals("not a version", VersionTargets.forExact("botmaker-sdk", "v1.2.0", V116));
        assertEquals("not a version", VersionTargets.forExact("botmaker-sdk", "", V116));
    }

    @Test
    void aDirectoryTheReleaseNeverCutsHasNoArrowAtAll() {
        // The data repositories and this one have no flag and no tag arithmetic. They are not an error —
        // the Modules tab lists them, and the answer there is that a release does not name them.
        assertFalse(VersionTargets.releasable("botmaker-gallery"));
        assertFalse(VersionTargets.releasable("botmaker-dashboard"));
        assertTrue(VersionTargets.releasable("botmaker-plugin-toolkit"));
        assertEquals("not released", VersionTargets.forLevel("botmaker-gallery", Level.PATCH, V116));
        assertEquals("not released", VersionTargets.forExact("botmaker-gallery", "1.0.0", V116));
    }
}
