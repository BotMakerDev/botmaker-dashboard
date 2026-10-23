package com.botmaker.dashboard.github;

import com.botmaker.cli.catalog.CatalogJson;
import com.botmaker.cli.catalog.Publishers;
import com.botmaker.cli.catalog.Rank;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
    void aTagChangeKeepsEveryOtherFieldInItsOrder() {
        String text = CatalogEdits.withTag("""
                {"repo": "somedev/firestone", "tag": "v1.0.0", "detached": true}
                """, "v2.0.0");
        assertEquals("""
                {
                  "repo" : "somedev/firestone",
                  "tag" : "v2.0.0",
                  "detached" : true
                }
                """, text);
        assertTrue(CatalogEdits.withTag("{\"id\":\"a.b\",\"repo\":\"x/y\",\"tag\":\"v1\"}", "v2")
                .contains("\"id\" : \"a.b\""));
        assertThrows(IllegalStateException.class, () -> CatalogEdits.withTag("[1]", "v2"));
        assertThrows(IllegalStateException.class, () -> CatalogEdits.withTag("{ not json", "v2"));
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
