package com.botmaker.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
