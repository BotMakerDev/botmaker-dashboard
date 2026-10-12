package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Module;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-12: shared v0.2.4 never built, and the SDK, Studio and the dashboard pinned it. */
class TagBuildsTest {

    private static final String FAILED = "No build artifacts found. See the log: com/github/BotMakerDev/"
            + "botmaker-shared/v0.2.4/build.log";

    /** JitPack as it stood: shared v0.2.4 failed, sdk v1.4.3 failed on it, everything else built. */
    private static final Jitpack.Probe JITPACK = new Jitpack.Probe() {
        @Override
        public boolean built(String pomUrl) {
            return !pomUrl.contains("/v0.2.4/") && !pomUrl.contains("/v1.4.3/");
        }

        @Override
        public Optional<String> failure(String buildUrl) {
            if (buildUrl.endsWith("botmaker-shared/v0.2.4")) {
                return Optional.of(FAILED);
            }
            return buildUrl.endsWith("botmaker-sdk/v1.4.3") ? Optional.of("Build failed") : Optional.empty();
        }
    };

    private static final Pins.Pin SHARED = new Pins.Pin(Module.SHARED, "v0.2.4", Optional.empty());

    @Test
    void aJitpackBuildIsAskedAndAProgramIsJudgedByItsPins() {
        assertEquals(TagBuilds.State.BROKEN, TagBuilds.of(Module.SHARED, "v0.2.4", List.of(), JITPACK).state());
        assertEquals(TagBuilds.State.BUILT, TagBuilds.of(Module.SHARED, "v0.2.5", List.of(), JITPACK).state());

        TagBuilds.Build studio = TagBuilds.of(Module.STUDIO, "v1.4.3", List.of(SHARED), JITPACK);
        assertEquals(TagBuilds.State.BROKEN, studio.state());
        assertTrue(studio.detail().startsWith("pins botmaker-shared v0.2.4, which never built"), studio.detail());
        assertEquals(Optional.of(SHARED), studio.pinned());
        assertEquals(TagBuilds.State.NOT_ON_JITPACK, TagBuilds.of(Module.STUDIO, "v1.4.4",
                List.of(new Pins.Pin(Module.SHARED, "v0.2.5", Optional.empty())), JITPACK).state());
        assertEquals(TagBuilds.State.NOT_ON_JITPACK, TagBuilds.of(Module.PILOT, "v1.0.0", List.of(), JITPACK).state());
    }

    @Test
    void theBannerNamesEachBrokenTagAndTheFlagsInTagOrder() {
        Map<String, String> tags = Map.of("botmaker-shared", "v0.2.4", "botmaker-sdk", "v1.4.3",
                "botmaker-studio", "v1.4.3", "botmaker-cli", "v0.3.3");
        Map<String, TagBuilds.Build> built = new LinkedHashMap<>();
        built.put("botmaker-studio", TagBuilds.of(Module.STUDIO, "v1.4.3", List.of(SHARED), JITPACK));
        built.put("botmaker-cli", TagBuilds.of(Module.CLI, "v0.3.3", List.of(), JITPACK));
        built.put("botmaker-shared", TagBuilds.of(Module.SHARED, "v0.2.4", List.of(), JITPACK));
        built.put("botmaker-sdk", TagBuilds.of(Module.SDK, "v1.4.3", List.of(), JITPACK));

        TagBuilds.Banner banner = TagBuilds.banner(tags, built).orElseThrow();
        assertEquals("3 tags never built on JitPack", banner.title());
        assertEquals("./release.sh --shared --sdk --studio", banner.command());
        assertTrue(banner.lines().getFirst().startsWith("botmaker-shared v0.2.4: No build artifacts found"));

        built.keySet().removeIf(module -> !module.equals("botmaker-cli"));
        assertEquals(Optional.empty(), TagBuilds.banner(tags, built));
    }

    @Test
    void theRepairEntryGoesUnderFixed() {
        String own = TagBuilds.entry("v0.2.4", TagBuilds.of(Module.SHARED, "v0.2.4", List.of(), JITPACK));
        assertEquals("- **0.2.4 never built.** JitPack's build of it failed (No build artifacts found), and a tag"
                + " that built `Error` stays broken. This release is 0.2.4 as it was meant to resolve: everything"
                + " listed under 0.2.4 is in it.", own);
        String pinned = TagBuilds.entry("v1.4.3", TagBuilds.of(Module.STUDIO, "v1.4.3", List.of(SHARED), JITPACK));
        assertTrue(pinned.startsWith("- **1.4.3 never built.** Its pom pinned botmaker-shared v0.2.4, a tag JitPack"
                + " never built, and"), pinned);

        assertEquals("### Fixed\n\n- x", TagBuilds.withEntry("", "- x"));
        assertEquals("### Fixed\n\n- x\n\n### Added\n\n- y", TagBuilds.withEntry("### Added\n\n- y\n", "- x"));
        assertEquals("### Added\n\n- y\n\n### Fixed\n\n- x\n- z",
                TagBuilds.withEntry("### Added\n\n- y\n\n### Fixed\n\n- z", "- x"));
    }
}
