package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PinsTest {

    /** Studio's pom as its release commit leaves it: the four upstreams at their released versions. */
    private static final String STUDIO_TAG_POM = """
            <project>
                <version>1.3.1</version>
                <properties>
                    <!-- botmaker-shared version: <botmaker.shared.version>9.9.9</botmaker.shared.version> -->
                    <botmaker.shared.version>0.1.2</botmaker.shared.version>
                    <botmaker.session.version>0.1.2</botmaker.session.version>
                    <botmaker.studioapi.version>0.4.2</botmaker.studioapi.version>
                    <botmaker.pluginhost.version>0.3.3</botmaker.pluginhost.version>
                    <botmaker.contract.tag>${botmaker.studioapi.version}</botmaker.contract.tag>
                </properties>
            </project>
            """;

    @Test
    void eachUpstreamIsReadInTheOrderTheReleaseNamesThemAndJudgedAgainstItsNewestTag() {
        List<Pins.Pin> pins = Pins.read(Module.STUDIO, STUDIO_TAG_POM, Map.of(
                "botmaker-shared", "v0.1.3",
                "botmaker-session", "v0.1.2",
                "botmaker-studio-api", "v0.4.2"));

        assertEquals(List.of(Module.SHARED, Module.SESSION, Module.STUDIO_API, Module.PLUGIN_HOST),
                pins.stream().map(Pins.Pin::upstream).toList());
        // The comment's 9.9.9 is not a pin; the tag spelling (v) is not a difference.
        assertEquals("shared 0.1.2 (upstream v0.1.3)", pins.get(0).toString());
        assertTrue(pins.get(0).stale());
        assertFalse(pins.get(1).stale());
        assertFalse(pins.get(2).stale());
        // No tag known for plugin-host: unjudged, never reported as current or stale.
        assertEquals("plugin-host 0.3.3", pins.get(3).toString());
        assertFalse(pins.get(3).stale());
    }

    @Test
    void aTagCutBeforeRealVersionsShowsItsCosmeticSnapshotUnjudged() {
        List<Pins.Pin> pins = Pins.read(Module.SESSION,
                "<project><version>0.0.0-SNAPSHOT</version><properties>"
                        + "<botmaker.shared.version>0.0.0-SNAPSHOT</botmaker.shared.version></properties></project>",
                Map.of("botmaker-shared", "v0.1.3"));

        assertEquals("shared 0.0.0-SNAPSHOT", pins.getFirst().toString());
        assertFalse(pins.getFirst().stale());
    }

    @Test
    void aModuleThatPinsNothingHasNoPins() {
        assertTrue(Pins.read(Module.STUDIO_API, STUDIO_TAG_POM, Map.of()).isEmpty());
    }
}
