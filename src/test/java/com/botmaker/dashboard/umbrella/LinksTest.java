package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinksTest {

    @Test
    void namesTheThreePlacesAReleaseCanBeWrong() {
        assertEquals("https://github.com/LiQiyeDev/botmaker-cli/releases/tag/v0.0.8",
                Links.release("botmaker-cli", "v0.0.8"));
        assertEquals("https://jitpack.io/#LiQiyeDev/botmaker-cli/v0.0.8",
                Links.jitpack("botmaker-cli", "v0.0.8"));
        // branch, not tag: a tag-triggered run records the tag in headBranch, which is the same filter
        // poll_actions uses.
        assertEquals("https://github.com/LiQiyeDev/botmaker-cli/actions?query=branch%3Av0.0.8",
                Links.actions("botmaker-cli", "v0.0.8"));
    }

    @Test
    void aModuleLinksItsRepositoryAndOffersAComparisonOnlyPastItsTag() {
        assertEquals(List.of(
                        new Links.Link("GitHub", "https://github.com/LiQiyeDev/botmaker-sdk"),
                        new Links.Link("Actions",
                                "https://github.com/LiQiyeDev/botmaker-sdk/actions?query=branch%3Amain"),
                        new Links.Link("JitPack", "https://jitpack.io/#LiQiyeDev/botmaker-sdk"),
                        new Links.Link("Releases", "https://github.com/LiQiyeDev/botmaker-sdk/releases"),
                        new Links.Link("Changes since v1.1.6",
                                "https://github.com/LiQiyeDev/botmaker-sdk/compare/v1.1.6...main")),
                Links.forModule("botmaker-sdk", Optional.of("v1.1.6"), 3));

        assertEquals(4, Links.forModule("botmaker-sdk", Optional.of("v1.1.6"), 0).size());
        assertEquals(4, Links.forModule("botmaker-dashboard", Optional.empty(), 12).size());
    }

    @Test
    void aRepositoryFieldReducesToOwnerAndName() {
        assertEquals(Optional.of("LiQiyeDev/botmaker-sdk"), Links.slug("LiQiyeDev/botmaker-sdk"));
        assertEquals(Optional.of("someone/plugin"), Links.slug("https://github.com/someone/plugin.git"));
        assertEquals(Optional.of("someone/plugin"), Links.slug(" github.com/someone/plugin/ "));
        assertEquals(Optional.empty(), Links.slug("plugin"));
        assertEquals(Optional.empty(), Links.slug("https://gitlab.com/someone/plugin"));
        assertEquals(Optional.empty(), Links.slug(""));
        assertEquals(Optional.empty(), Links.slug(null));

        assertEquals("https://jitpack.io/#someone/plugin", Links.forRepository("someone/plugin").get(2).url());
    }
}
