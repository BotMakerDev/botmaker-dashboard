package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code catalog.json} read as the Catalog tab's rows: both kinds, and every way a file can be wrong. */
class CatalogTest {

    static final String CATALOG = """
            {"schemaVersion": 5, "builtAt": "2026-10-24T18:00:00Z",
             "plugins": [
               {"repo": "BotMakerDev/botmaker-sdk", "rank": "admin",
                "coordinate": "com.github.BotMakerDev:botmaker-sdk", "name": "BotMaker SDK",
                "description": "Screens and flows", "tags": ["screens"], "stars": 4,
                "releases": [
                  {"tag": "v1.5.0", "commit": "1111111111111111111111111111111111111111",
                   "jarSha256": "aa", "pomSha256": "bb", "minContractVersion": "0.11.0"},
                  {"tag": "v1.4.2", "commit": "2222222222222222222222222222222222222222",
                   "jarSha256": "cc", "pomSha256": "dd"}]},
               {"repo": "somedev/zoom", "rank": "galactic",
                "releases": [{"tag": "v0.1.0", "commit": "3333333333333333333333333333333333333333"}]}
             ],
             "bots": [
               {"repo": "somedev/firestone", "rank": "community", "description": "Idle game",
                "tags": ["idle"], "stars": 12, "forkOf": "LiQiyeDev/firestone",
                "releases": [
                  {"tag": "v2.0.0", "commit": "4444444444444444444444444444444444444444",
                   "plugins": [{"repo": "BotMakerDev/botmaker-sdk", "version": "v1.5.0"}]},
                  {"tag": "v1.0.0", "commit": "5555555555555555555555555555555555555555",
                   "plugins": [{"repo": "BotMakerDev/botmaker-sdk", "version": "v1.4.2"}]}]}
             ]}
            """;

    @Test
    void everyApprovedReleaseIsReadNewestFirst() {
        List<CatalogRow> rows = Catalog.read(CATALOG).rows();
        CatalogRow sdk = rows.getFirst();
        assertEquals(List.of("v1.5.0", "v1.4.2"), sdk.releases().stream().map(CatalogRow.Release::tag).toList());
        assertEquals("v1.5.0", sdk.tag(), "the card's tag is the newest");
        assertEquals("1111111", sdk.releases().getFirst().shortCommit());

        CatalogRow bot = rows.getLast();
        assertEquals("v2.0.0", bot.tag());
        assertEquals(List.of(new CatalogRow.Pin("BotMakerDev/botmaker-sdk", "v1.4.2")), bot.releases().get(1).plugins(),
                "each release keeps its own plugins");
    }

    @Test
    void aSchemaThreeRowWithOnlyATagIsDropped() {
        Catalog.Read read = Catalog.read("""
                {"plugins": [{"id": "a.b", "repo": "x/y", "tag": "v1"}],
                 "bots": [{"repo": "x/bot", "tag": "v1"}, {"repo": "x/ok", "releases": [{"tag": "v1"}]}]}
                """);
        assertEquals(List.of("bots/x__ok.json"), read.rows().stream().map(CatalogRow::entryPath).toList());
    }

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
        assertEquals("plugins/BotMakerDev__botmaker-sdk.json", sdk.entryPath());
        assertEquals(List.of("screens"), sdk.tags());

        CatalogRow bot = read.rows().getLast();
        assertEquals(CatalogRow.Kind.BOT, bot.kind());
        assertEquals("firestone", bot.name(), "a bot is named by its repository");
        assertEquals("LiQiyeDev/firestone", bot.forkOf());
        assertEquals(List.of(new CatalogRow.Pin("BotMakerDev/botmaker-sdk", "v1.5.0")), bot.plugins());
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
    void aRowMissingItsRepoOrReleasesOrOfTheWrongShapeIsDroppedAndTheRestStay() {
        Catalog.Read read = Catalog.read("""
                {"plugins": [{"repo": "x/y"}, {"releases": [{"tag": "v1"}]},
                             {"repo": "x/v", "releases": [{"commit": "abc"}]},
                             {"repo": "x/u", "stars": "many", "releases": [{"tag": "v1"}]},
                             {"repo": "x/w", "releases": [{"tag": "v1"}]}],
                 "bots": [{"releases": [{"tag": "v1"}]}, {"repo": "noslash", "releases": [{"tag": "v1"}]},
                          {"repo": "x/bot", "releases": [{"tag": "v1"}]}]}
                """);
        assertEquals(List.of("plugins/x__w.json", "bots/x__bot.json"),
                read.rows().stream().map(CatalogRow::entryPath).toList(),
                "a row needs its repo, a release its tag, and a field its type");
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
