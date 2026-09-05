package com.botmaker.dashboard.umbrella;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UmbrellaTest {

    @Test
    void readsTheSubmodulePathsInFileOrder() {
        // Both indentation styles appear in the real .gitmodules — it has been edited by `git submodule add`
        // and by hand, and the two disagree about the leading whitespace.
        String gitmodules = """
                [submodule "botmaker-sdk"]
                \tpath = botmaker-sdk
                \turl = git@github.com:LiQiyeDev/botmaker-sdk.git
                [submodule "botmaker-cli"]
                    path = botmaker-cli
                    url = git@github.com:LiQiyeDev/botmaker-cli.git
                """;

        assertEquals(List.of("botmaker-sdk", "botmaker-cli"), Umbrella.modules(gitmodules));
    }

    @Test
    void aCheckoutWithNoSubmodulesYieldsNothing() {
        assertEquals(List.of(), Umbrella.modules(""));
    }
}
