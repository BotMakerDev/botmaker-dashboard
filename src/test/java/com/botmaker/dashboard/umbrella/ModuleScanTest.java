package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A row's tag is the release library's newest release, spelled as git holds it (2026-09-29). */
class ModuleScanTest {

    @Test
    void theNewestIsByVersionNotByText() {
        assertEquals(Optional.of("v1.10.0"), ModuleScan.newestTag(List.of("v1.9.0", "v1.10.0", "v1.2.0")));
    }

    @Test
    void aTagThatIsNotAReleaseIsIgnored() {
        // git's --sort=-v:refname put both of these above v1.1.6, and the row then disagreed with the arrow.
        assertEquals(Optional.of("v1.1.6"), ModuleScan.newestTag(List.of("v1.1.6", "demo-2026", "v1.2.0-rc1")));
    }

    @Test
    void theNameIsKeptAsGitHoldsIt() {
        // The SDK's older tags are bare; the name is what a sibling's pin and every later git call use.
        assertEquals(Optional.of("1.0.24"), ModuleScan.newestTag(List.of("v0.9.0", "1.0.24", "  ")));
    }

    @Test
    void noReleaseIsEmpty() {
        assertEquals(Optional.empty(), ModuleScan.newestTag(List.of()));
        assertEquals(Optional.empty(), ModuleScan.newestTag(List.of("nightly")));
    }
}
