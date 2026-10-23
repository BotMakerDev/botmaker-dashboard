package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog.json} read as the Catalog tab's rows: both kinds, and every way a file can be wrong. */
class CatalogTest {

    static final String CATALOG = """
            {"schemaVersion": 3, "builtAt": "2026-10-23T18:00:00Z",
             "plugins": [
               {"id": "com.botmaker.sdk", "repo": "BotMakerDev/botmaker-sdk", "tag": "v1.5.0", "rank": "admin",
                "coordinate": "com.github.BotMakerDev:botmaker-sdk", "name": "BotMaker SDK",
                "description": "Screens and flows", "tags": ["screens"], "stars": 4},
               {"id": "com.example.zoom", "repo": "somedev/zoom", "tag": "v0.1.0", "rank": "galactic"}
             ],
             "bots": [
               {"repo": "somedev/firestone", "tag": "v2.0.0", "rank": "community", "description": "Idle game",
                "tags": ["idle"], "stars": 12, "forkOf": "LiQiyeDev/firestone",
                "plugins": [{"id": "com.botmaker.sdk", "version": "v1.5.0"}]}
             ]}
            """;

    @Test
    void bothKindsAreRead() {
        Catalog.Read read = Catalog.read(CATALOG);
        assertEquals("", read.problem());
        assertEquals(3, read.rows().size());

        CatalogRow sdk = read.rows().getFirst();
        assertEquals(CatalogRow.Kind.PLUGIN, sdk.kind());
        assertEquals("BotMaker SDK", sdk.name());
        assertEquals("BotMakerDev", sdk.owner());
        assertEquals(Rank.ADMIN, sdk.rank());
        assertEquals("plugins/com.botmaker.sdk.json", sdk.entryPath());
        assertEquals(List.of("screens"), sdk.tags());

        CatalogRow bot = read.rows().getLast();
        assertEquals(CatalogRow.Kind.BOT, bot.kind());
        assertEquals("firestone", bot.name(), "a bot is named by its repository");
        assertEquals("LiQiyeDev/firestone", bot.forkOf());
        assertEquals(List.of(new CatalogRow.Pin("com.botmaker.sdk", "v1.5.0")), bot.plugins());
        assertEquals("bots/somedev__firestone.json", bot.entryPath());
        assertEquals("https://github.com/BotMakerDev/botmaker-catalog/blob/main/bots/somedev__firestone.json",
                bot.entryUrl());
    }

    @Test
    void anUnknownRankIsCommunityAndAMissingNameIsTheRepository() {
        CatalogRow zoom = Catalog.read(CATALOG).rows().get(1);
        assertEquals(Rank.COMMUNITY, zoom.rank(), "as Studio reads it: the least trusted");
        assertEquals("zoom", zoom.name());
    }

    @Test
    void aRowMissingItsRepoOrTagIsDroppedAndTheRestStay() {
        Catalog.Read read = Catalog.read("""
                {"plugins": [{"id": "a.b", "repo": "x/y"}, {"repo": "x/z", "tag": "v1"},
                             {"id": "c.d", "repo": "x/w", "tag": "v1"}],
                 "bots": [{"tag": "v1"}, {"repo": "noslash", "tag": "v1"}, {"repo": "x/bot", "tag": "v1"}]}
                """);
        assertEquals(List.of("plugins/c.d.json", "bots/x__bot.json"),
                read.rows().stream().map(CatalogRow::entryPath).toList(), "a plugin needs its id too");
    }

    @Test
    void aBrokenFileIsAnEmptyListWithTheReason() {
        Catalog.Read broken = Catalog.read("{ not json");
        assertTrue(broken.rows().isEmpty());
        assertTrue(broken.problem().contains("not JSON"), broken.problem());
        assertTrue(Catalog.read("[]").problem().contains("not a catalog"));
        assertTrue(Catalog.read(null).problem().contains("not a catalog"));
        assertEquals("", Catalog.read("{}").problem(), "an empty catalog is a catalog");
    }

    @Test
    void aPluginLinksJitpackAndABotDoesNot() {
        List<CatalogRow> rows = Catalog.read(CATALOG).rows();
        assertTrue(rows.getFirst().links().stream().anyMatch(l -> l.label().equals("JitPack")));
        assertTrue(rows.getLast().links().stream().noneMatch(l -> l.label().equals("JitPack")));
        assertEquals("Entry file", rows.getLast().links().getLast().label());
    }
}
