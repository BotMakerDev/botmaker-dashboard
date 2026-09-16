package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.GateVerdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The badge is the gate's verdict rendered, never a second reading of {@code gh}. */
class CiStatusTest {

    @Test
    void aGreenRunIsGreenAndKeepsTheGatesOwnSentence() {
        CiStatus status = CiStatus.of("botmaker-sdk",
                GateVerdict.ok("  sdk: CI on main is green at a1b2c3d — ok"));
        assertEquals(CiStatus.Health.GREEN, status.health());
        assertEquals("CI on main is green at a1b2c3d — ok", status.text());
    }

    @Test
    void aRefusalIsRedAndItsFirstLineIsTheBadge() {
        GateVerdict refused = GateVerdict.refused("""
                botmaker-plugin-host: the newest finished CI run on main is failure (9e21a4c).
                     https://github.com/LiQiyeDev/botmaker-plugin-host/actions/runs/1
                     Fix main first, or wait for a newer run. --force overrides.""");
        CiStatus status = CiStatus.of("botmaker-plugin-host", refused);
        assertEquals(CiStatus.Health.RED, status.health());
        assertEquals("the newest finished CI run on main is failure (9e21a4c).", status.text());
        assertEquals(refused.refusal(), status.detail(), "the whole refusal is the tooltip");
    }

    @Test
    void aRunStillGoingAndAMissingGhAreBothUnknownRatherThanGreen() {
        assertEquals(CiStatus.Health.UNKNOWN, CiStatus.of("botmaker-cli",
                GateVerdict.skipped("  cli: CI on main is still running — not checked")).health());
        assertEquals("no gh on PATH — CI on main not checked", CiStatus.of("botmaker-cli",
                GateVerdict.skipped("  cli: no gh on PATH — CI on main not checked")).text());
    }

    @Test
    void aModuleTheReleaseNeverCutsIsNotAskedAboutAtAll() {
        // No gh call, so no process and no wrong answer for a repository with no ci.yml.
        CiStatus status = CiStatus.check("botmaker-gallery");
        assertEquals(CiStatus.Health.UNKNOWN, status.health());
        assertEquals("not released", status.text());
    }
}
