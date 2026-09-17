package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltWithTest {

    private static final String DEPS_ENV = """
            # The upstream refs this botmaker-dashboard release is built against.

            SHARED_TAG=v0.0.21
            STUDIO_API_TAG=v0.1.0
            PLUGIN_HOST_TAG=v0.1.0
            CLI_TAG=v0.0.14
            """;

    @Test
    void theCliPinIsReadOutOfTheBakedFile() {
        assertEquals(Optional.of("v0.0.14"), BuiltWith.cliTag(DEPS_ENV));
        assertTrue(BuiltWith.cliTag("SHARED_TAG=v0.0.21\n").isEmpty());
    }

    @Test
    void aDevelopmentBuildCarriesNoFileAndSaysNothing() {
        // The resource is baked by the dist profile only; the test classpath is a development one.
        assertTrue(BuiltWith.cliTag().isEmpty());
        assertTrue(BuiltWith.notice(Optional.empty(), Optional.of("v0.0.14-3-gabc1234")).isEmpty());
    }

    @Test
    void theNoticeNamesBothSidesAndOnlyWhenTheyDiffer() {
        assertTrue(BuiltWith.notice(Optional.of("v0.0.14"), Optional.of("v0.0.14")).isEmpty());
        assertTrue(BuiltWith.notice(Optional.of("v0.0.14"), Optional.empty()).isEmpty());
        assertEquals(Optional.of("built with cli v0.0.14, checkout at v0.0.14-3-gabc1234"
                        + " — preview may follow older rules"),
                BuiltWith.notice(Optional.of("v0.0.14"), Optional.of("v0.0.14-3-gabc1234")));
    }

    @Test
    void aCheckoutWithoutTheCliSubmoduleIsUnknownRatherThanAnError() {
        assertTrue(BuiltWith.checkoutCli(Path.of("/nonexistent/umbrella")).isEmpty());
    }
}
