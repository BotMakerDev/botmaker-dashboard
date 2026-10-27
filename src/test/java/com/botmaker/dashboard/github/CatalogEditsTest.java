package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.catalog.PluginEntry;
import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The texts an admin's edits write, without a request: every field kept, ranks moved by account id. */
class CatalogEditsTest {

    private static final Publishers PUBLISHERS = new Publishers(
            List.of(new Publishers.Publisher(298085676, "LiQiyeDev"),
                    new Publishers.Publisher(330995542, "BotMakerDev")),
            List.of(new Publishers.Publisher(42, "trusteddev")));

    private static Publishers read(String json) throws Exception {
        return CatalogJson.strict().readValue(json, Publishers.class);
    }

    @Test
    void aReleaseRemovedKeepsTheOthersAndEveryFieldInTheirOrder() {
        String text = CatalogEdits.withoutRelease("""
                {"repo": "somedev/firestone",
                 "releases": [{"tag": "v1.0.0", "commit": "a"}, {"tag": "v1.1.0", "commit": "b"},
                              {"tag": "v2.0.0", "commit": "c"}],
                 "detached": true}
                """, "v1.1.0");
        assertEquals("""
                {
                  "repo" : "somedev/firestone",
                  "releases" : [ {
                    "tag" : "v1.0.0",
                    "commit" : "a"
                  }, {
                    "tag" : "v2.0.0",
                    "commit" : "c"
                  } ],
                  "detached" : true
                }
                """, text);
    }

    @Test
    void theCatalogsCheckReadsARemovedReleaseAsARemove() throws Exception {
        String before = """
                {"repo": "x/y", "releases": [
                  {"tag": "v1", "commit": "%1$s", "jarSha256": "%2$s", "pomSha256": "%2$s"},
                  {"tag": "v2", "commit": "%1$s", "jarSha256": "%2$s", "pomSha256": "%2$s"},
                  {"tag": "v3", "commit": "%1$s", "jarSha256": "%2$s", "pomSha256": "%2$s"}]}
                """.formatted("a".repeat(40), "b".repeat(64));
        PluginEntry held = CatalogJson.strict().readValue(before, PluginEntry.class);
        PluginEntry head = CatalogJson.strict().readValue(CatalogEdits.withoutRelease(before, "v2"), PluginEntry.class);
        assertEquals(new EntryGate.Change.Remove(List.of("v2")), EntryGate.Change.between(Optional.of(held), head));
    }

    @Test
    void aReleaseRemovalTheFileCannotTakeIsRefused() {
        String two = "{\"repo\":\"x/y\",\"releases\":[{\"tag\":\"v1\"},{\"tag\":\"v2\"}]}";
        assertTrue(CatalogEdits.withoutRelease(two, "v2").contains("\"repo\" : \"x/y\""));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> CatalogEdits.withoutRelease(two, "v3"));
        assertTrue(missing.getMessage().contains("lists no release v3"), missing.getMessage());
        IllegalStateException last = assertThrows(IllegalStateException.class,
                () -> CatalogEdits.withoutRelease("{\"repo\":\"x/y\",\"releases\":[{\"tag\":\"v1\"}]}", "v1"));
        assertTrue(last.getMessage().contains("lists only v1"), last.getMessage());
        assertThrows(IllegalStateException.class,
                () -> CatalogEdits.withoutRelease("{\"repo\":\"x/y\",\"tag\":\"v1\"}", "v1"), "schema 3");
        assertThrows(IllegalStateException.class, () -> CatalogEdits.withoutRelease("[1]", "v2"));
        assertThrows(IllegalStateException.class, () -> CatalogEdits.withoutRelease("{ not json", "v2"));
    }

    @Test
    void aRankMoveTakesTheAccountOffItsOldList() throws Exception {
        Publishers promoted = read(CatalogEdits.moved(PUBLISHERS, 42, "trusteddev", Rank.ADMIN));
        assertEquals(3, promoted.admin().size());
        assertTrue(promoted.trusted().isEmpty());
        assertEquals(Rank.ADMIN, promoted.rankOf(42));

        Publishers newcomer = read(CatalogEdits.moved(PUBLISHERS, 77, "somedev", Rank.TRUSTED));
        assertEquals(List.of(new Publishers.Publisher(42, "trusteddev"), new Publishers.Publisher(77, "somedev")),
                newcomer.trusted());

        Publishers demoted = read(CatalogEdits.moved(PUBLISHERS, 42, "trusteddev", Rank.COMMUNITY));
        assertEquals(Rank.COMMUNITY, demoted.rankOf(42), "community is off both lists");
        assertEquals(PUBLISHERS.admin(), demoted.admin());
    }

    @Test
    void aRenamedLoginIsWrittenUnderItsNewNameByTheSameId() throws Exception {
        Publishers renamed = read(CatalogEdits.moved(PUBLISHERS, 42, "renameddev", Rank.TRUSTED));
        assertEquals(List.of(new Publishers.Publisher(42, "renameddev")), renamed.trusted());
    }

    @Test
    void aMoveThatChangesNothingOrLeavesNoAdminIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEdits.moved(PUBLISHERS, 42, "trusteddev", Rank.TRUSTED));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogEdits.moved(PUBLISHERS, 77, "somedev", Rank.COMMUNITY));
        Publishers one = new Publishers(List.of(new Publishers.Publisher(1, "only")), List.of());
        IllegalArgumentException last = assertThrows(IllegalArgumentException.class,
                () -> CatalogEdits.moved(one, 1, "only", Rank.TRUSTED));
        assertTrue(last.getMessage().contains("at least one admin"));
    }

    @Test
    void aBranchIsALegalRefNamedForItsVerbAndStampedInUtc() {
        Instant at = Instant.parse("2026-10-23T18:04:05Z");
        assertEquals("dashboard/tag-somedev-firestone-20261023-180405",
                CatalogEdits.branch("tag", "somedev/firestone", at));
        assertEquals("dashboard/remove-com.botmaker.sdk-20261023-180405",
                CatalogEdits.branch("remove", "com.botmaker.sdk", at));
    }

    @Test
    void aBodySaysWhereItCameFrom() {
        assertEquals("Removes x.\n\nbecause\n\nOpened from the BotMaker Dashboard.",
                CatalogEdits.body("Removes x.", "  because "));
        assertEquals("Removes x.\n\nOpened from the BotMaker Dashboard.", CatalogEdits.body("Removes x.", null));
    }
}
