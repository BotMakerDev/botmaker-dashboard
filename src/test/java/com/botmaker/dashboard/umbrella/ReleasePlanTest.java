package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parse is the whole of this class's risk: it is what stands between the script's answer and a window
 * that claims something the script never said.
 */
class ReleasePlanTest {

    /** A real run, colours and all, cut off where a gate refused it — which is the ordinary shape. */
    private static final String REAL = """
            [1;34m==>[0m DRY RUN — no changes will be made.
            [1;34m==>[0m Release plan:
                studio-api: patch -> v0.0.5  (the plugin contract)
                shared : patch -> v0.0.21
            [1;34m==>[0m Deciding what to release:
                botmaker-pilot: no changes since its latest tag — skipping
                botmaker-studio-api: only docs since v0.0.4 — skipping (the artifact would be identical; --force overrides)
                botmaker-cli: releasing v0.0.13
                botmaker-shared: releasing v0.0.21
            [1;34m==>[0m   sdk: @ReplacedBy pointers complete for v1.1.7 — ok
            error: sdk: botmaker validate refuses this build of botmaker-sdk.
            """;

    @Test
    void readsOnlyTheDecidePass() {
        ReleasePlan plan = ReleasePlan.parse(REAL, 1);

        // "studio-api: patch -> v0.0.5" is the PLAN, printed before the decide pass and keyed by a short
        // name. Reading it as a verdict would report a module as releasing that the pass then skipped.
        assertEquals(4, plan.verdicts().size());
        assertTrue(plan.forModule("botmaker-cli").orElseThrow().releasing());
        assertEquals("0.0.13", plan.forModule("botmaker-cli").orElseThrow().version().orElseThrow());
    }

    @Test
    void keepsTheScriptsOwnWordsForASkip() {
        ReleasePlan plan = ReleasePlan.parse(REAL, 1);

        // The two skips have different causes and the difference is the reason to read the tab at all.
        assertEquals("no changes since its latest tag — skipping",
                plan.forModule("botmaker-pilot").orElseThrow().text());
        assertTrue(plan.forModule("botmaker-studio-api").orElseThrow().text().startsWith("only docs since"));
        assertFalse(plan.forModule("botmaker-studio-api").orElseThrow().releasing());
    }

    @Test
    void stopsBeforeTheGates() {
        // The gates print module-prefixed lines of their own ("  sdk: @ReplacedBy pointers complete"), so a
        // parse that kept scanning would invent verdicts out of gate output.
        assertFalse(ReleasePlan.parse(REAL, 1).verdicts().containsKey("botmaker-sdk"));
    }

    @Test
    void aModuleNeverNamedHasNoVerdict() {
        // botmaker-gallery and botmaker-plugin-registry are data-only, and this module is not published:
        // "absent" is how the window knows that, with no list of releasable modules kept here.
        assertTrue(ReleasePlan.parse(REAL, 1).forModule("botmaker-gallery").isEmpty());
    }

    @Test
    void aRunThatDiedBeforeDecidingHasNoPlanRatherThanAnEmptyOne() {
        ReleasePlan plan = ReleasePlan.parse("error: not a git repository\n", 1);

        assertFalse(plan.decided());
        assertEquals(1, plan.exit());
        assertTrue(plan.raw().contains("not a git repository"));
    }
}
