package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

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
}
