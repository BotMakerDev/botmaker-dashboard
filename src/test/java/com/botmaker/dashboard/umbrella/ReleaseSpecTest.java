package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleaseSpecTest {

    private static Map<String, String> modules(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put(pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void everyModuleFlagIsItsDirectoryNameWithoutThePrefix() {
        // Derived rather than tabulated: a table here would be a third list this module keeps, and it would
        // be short by exactly one module on the day an eleventh is added.
        assertEquals("--studio-api", ReleaseSpec.flagFor("botmaker-studio-api"));
        assertEquals("--plugin-toolkit", ReleaseSpec.flagFor("botmaker-plugin-toolkit"));
        assertEquals("--plugin-archetype", ReleaseSpec.flagFor("botmaker-plugin-archetype"));
        assertEquals("--cli", ReleaseSpec.flagFor("botmaker-cli"));
        assertEquals("--sdk", ReleaseSpec.flagFor("botmaker-sdk"));
        assertEquals("--pilot", ReleaseSpec.flagFor("botmaker-pilot"));
    }

    @Test
    void dryRunIsAlwaysLastAndCannotBeLeftOff() {
        // The one rule this record enforces rather than reports. Part B visualises and does not execute.
        for (ReleaseSpec spec : java.util.List.of(
                new ReleaseSpec(Optional.of("minor"), Map.of(), false, false),
                new ReleaseSpec(Optional.empty(), modules("botmaker-cli", ""), true, true))) {
            assertEquals("--dry-run", spec.command().get(spec.command().size() - 1));
        }
    }

    @Test
    void aBlankSpecIsTheBareFlag() {
        ReleaseSpec spec = new ReleaseSpec(Optional.empty(), modules("botmaker-cli", ""), false, false);
        assertEquals("./release.sh --cli --dry-run", spec.commandLine());
    }

    @Test
    void anExplicitModuleIsPassedBesideAllRatherThanResolvedHere() {
        // release.sh seeds every unset module from --all and lets an explicit flag win. That is its rule,
        // so both are passed and it applies it; deciding here would be the second implementation.
        ReleaseSpec spec = new ReleaseSpec(Optional.of("minor"),
                modules("botmaker-sdk", "1.2.0"), false, false);
        assertEquals("./release.sh --all minor --sdk 1.2.0 --dry-run", spec.commandLine());
    }

    @Test
    void theBareAllFlagCarriesNoLevel() {
        ReleaseSpec spec = new ReleaseSpec(Optional.of(""), Map.of(), false, false);
        assertEquals("./release.sh --all --dry-run", spec.commandLine());
    }

    @Test
    void bothOptionFlagsAreSpelledAsTheScriptSpellsThem() {
        ReleaseSpec spec = new ReleaseSpec(Optional.of("patch"), Map.of(), true, true);
        assertEquals("./release.sh --all patch --force --no-wait-jitpack --dry-run", spec.commandLine());
    }

    @Test
    void nothingSelectedIsEmptyRatherThanACommandTheScriptWouldRefuse() {
        assertTrue(new ReleaseSpec(Optional.empty(), Map.of(), false, false).empty());
        assertFalse(new ReleaseSpec(Optional.of(""), Map.of(), false, false).empty());
    }

    @Test
    void aVersionOrLevelIsCheckedAgainstTheScriptsOwnGrammar() {
        assertTrue(ReleaseSpec.wellFormed(""));
        assertTrue(ReleaseSpec.wellFormed("patch"));
        assertTrue(ReleaseSpec.wellFormed("major"));
        assertTrue(ReleaseSpec.wellFormed("1.2.0"));
        // Half-typed, and a bump level nobody has: both refused, both for the same reason resolve_version
        // gives — "want x.y.z or patch|minor|major".
        assertFalse(ReleaseSpec.wellFormed("1.2"));
        assertFalse(ReleaseSpec.wellFormed("v1.2.0"));
        assertFalse(ReleaseSpec.wellFormed("tiny"));
    }
}
