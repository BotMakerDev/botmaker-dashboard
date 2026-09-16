package com.botmaker.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The umbrella check, which is what stops a wrong directory being reported as four empty tabs.
 */
class DashboardConfigTest {

    @Test
    void bothFilesTogetherAreTheUmbrella(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("release.sh"));
        Files.createFile(dir.resolve(".gitmodules"));
        assertTrue(DashboardConfig.looksLikeUmbrella(dir));
    }

    @Test
    void oneWithoutTheOtherIsSomethingElse(@TempDir Path dir) throws Exception {
        // A submodule checkout has neither; a repo with only a release script is not this constellation.
        assertFalse(DashboardConfig.looksLikeUmbrella(dir));
        Files.createFile(dir.resolve("release.sh"));
        assertFalse(DashboardConfig.looksLikeUmbrella(dir));
    }

    @Test
    void nullIsNotTheUmbrella() {
        assertFalse(DashboardConfig.looksLikeUmbrella(null));
    }

    @Test
    void theThemeAndTheCheckoutSurviveEachOthersSaves(@TempDir Path dir) {
        Path file = dir.resolve("dashboard.json");
        DashboardConfig.save(new DashboardConfig(dir, null).withTheme(Theme.LIGHT), file);
        DashboardConfig read = DashboardConfig.load(file);
        assertEquals(dir, read.umbrella());
        assertEquals(Theme.LIGHT, read.theme());

        // Choosing another checkout keeps the palette — the bug a one-key save would have had.
        DashboardConfig.save(read.withUmbrella(dir.resolve("other")), file);
        assertEquals(Theme.LIGHT, DashboardConfig.load(file).theme());
    }

    @Test
    void anUnknownThemeMeansFollowTheDesktop(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dashboard.json");
        Files.writeString(file, "{\"theme\":\"sepia\"}");
        DashboardConfig read = DashboardConfig.load(file);
        assertNull(read.theme());
        assertNull(read.umbrella());
    }

    @Test
    void aMissingFileIsTheFirstRun(@TempDir Path dir) {
        DashboardConfig read = DashboardConfig.load(dir.resolve("absent.json"));
        assertNull(read.theme());
        assertNull(read.umbrella());
    }
}
