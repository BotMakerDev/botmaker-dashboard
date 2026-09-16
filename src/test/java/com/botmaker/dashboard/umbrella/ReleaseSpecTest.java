package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a set of ticked boxes spells, and — since 2026-09-16 — what it no longer spells.
 *
 * <p>The load-bearing case in this file is {@link #aPreviewAndAReleaseDifferByOneWord()}. Until this date
 * {@code command()} appended {@code --dry-run} with no way to leave it off, and that was the whole of the
 * module's safety story. It is the Release tab's arming and confirmation now, so what this class must get
 * right is narrower: the request handed to the decide pass, and the two lines an operator may copy.
 */
class ReleaseSpecTest {

    private static Map<Module, String> modules(Module module, String spec) {
        Map<Module, String> map = new LinkedHashMap<>();
        map.put(module, spec);
        return map;
    }

    @Test
    void theModuleFlagIsTheLibrarysOwnSpelling() {
        // Not a table here: Module derives every flag from its directory, and a copy in this window would
        // go stale on the day an eleventh module is added.
        assertEquals("--studio-api", Module.STUDIO_API.flag());
        assertEquals("--plugin-toolkit", Module.PLUGIN_TOOLKIT.flag());
        assertEquals("--sdk", Module.SDK.flag());
    }

    @Test
    void aPreviewAndAReleaseDifferByOneWord() {
        ReleaseSpec spec = new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.0"), false, false);

        assertEquals("botmaker release --all patch --sdk 1.2.0", spec.commandLine());
        assertEquals("botmaker release --all patch --sdk 1.2.0 --execute", spec.executeCommandLine());
    }

    @Test
    void nothingHereCanSpellDryRunAnyMore() {
        // The flag does not exist in this vocabulary: a preview is a Runner, and the Runner is chosen by
        // whoever calls ReleaseRun.go.
        for (ReleaseSpec spec : List.of(
                new ReleaseSpec(Optional.of("minor"), Map.of(), false, false),
                new ReleaseSpec(Optional.empty(), modules(Module.CLI, ""), true, true))) {
            assertFalse(spec.commandLine().contains("--dry-run"), spec.commandLine());
            assertFalse(spec.executeCommandLine().contains("--dry-run"), spec.executeCommandLine());
        }
    }

    @Test
    void theCommandNamesTheCliRatherThanTheScript() {
        // The line an operator copies has to reach the same code the button does. It named ./release.sh
        // until 2026-09-16, which meant the window and the fallback were two implementations.
        assertTrue(new ReleaseSpec(Optional.of(""), Map.of(), false, false)
                .commandLine().startsWith("botmaker release "));
    }

    @Test
    void aBlankLevelIsTheBareFlagAndMeansPatch() {
        ReleaseSpec spec = new ReleaseSpec(Optional.of(""), Map.of(), false, false);

        assertEquals("botmaker release --all", spec.commandLine());
        assertEquals("patch", spec.requested().get(Module.SDK),
                "a bare --all is what the library reads as patch");
    }

    @Test
    void anExplicitModuleBeatsAll() {
        // The rule is Requested's, not this record's — what is asserted here is that this record asks it.
        Map<Module, String> requested =
                new ReleaseSpec(Optional.of("minor"), modules(Module.SDK, "1.2.0"), false, false)
                        .requested();

        assertEquals("1.2.0", requested.get(Module.SDK));
        assertEquals("minor", requested.get(Module.STUDIO));
        assertEquals(Module.values().length, requested.size(), "--all names every module");
    }

    @Test
    void aBareModuleFlagAsksForAPatchRatherThanForNothing() {
        assertEquals(Map.of(Module.CLI, "patch"),
                new ReleaseSpec(Optional.empty(), modules(Module.CLI, ""), false, false).requested());
    }

    @Test
    void theTwoRunFlagsAreCarriedThrough() {
        ReleaseSpec spec = new ReleaseSpec(Optional.of("patch"), Map.of(), true, true);

        assertTrue(spec.commandLine().contains("--force"));
        assertTrue(spec.commandLine().contains("--no-wait-jitpack"));
        assertTrue(spec.force());
        assertTrue(spec.noWaitJitpack());
    }

    @Test
    void nothingSelectedIsRefusedBeforeTheLibraryIsAsked() {
        assertTrue(new ReleaseSpec(Optional.empty(), Map.of(), false, false).empty());
        assertFalse(new ReleaseSpec(Optional.of(""), Map.of(), false, false).empty());
    }

    @Test
    void twoSpecsAreEqualExactlyWhenTheyWouldReleaseTheSameThing() {
        // This is what arms Execute: the tab holds the spec of the last clean preview and compares it by
        // value, so a tick or a keystroke that changes what a release would do takes the button dead.
        ReleaseSpec armed = new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.0"), false, false);

        assertEquals(armed,
                new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.0"), false, false));
        assertNotEquals(armed,
                new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.1"), false, false));
        assertNotEquals(armed,
                new ReleaseSpec(Optional.of("minor"), modules(Module.SDK, "1.2.0"), false, false));
        assertNotEquals(armed,
                new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.0"), true, false),
                "--force changes which modules are cut, so it must not keep an arming");
        assertNotEquals(armed,
                new ReleaseSpec(Optional.of("patch"), modules(Module.SDK, "1.2.0"), false, true),
                "--no-wait-jitpack changes what the run does between tags");
    }

    @Test
    void aTypedVersionIsCheckedForItsGrammarAndNothingElse() {
        assertTrue(ReleaseSpec.wellFormed(""));
        assertTrue(ReleaseSpec.wellFormed("patch"));
        assertTrue(ReleaseSpec.wellFormed("major"));
        assertTrue(ReleaseSpec.wellFormed("1.2.0"));

        assertFalse(ReleaseSpec.wellFormed("1.2"));
        assertFalse(ReleaseSpec.wellFormed("v1.2.0"));
        assertFalse(ReleaseSpec.wellFormed("tiny"));
    }
}
