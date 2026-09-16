package com.botmaker.dashboard.github;

import com.botmaker.cli.gallery.VettedRecord;
import com.botmaker.cli.registry.Registry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tier a Catalog row shows, and the file Vet writes — both read and written through the CLI's records. */
class VettingTest {

    private static Catalog.Entry bot(String owner, String repo) {
        return Catalog.read(Catalog.Kind.BOT, "bots/" + owner + "-" + repo + ".json", "sha",
                owner + "-" + repo, """
                        {"schemaVersion": 2, "name": "%s", "owner": "%s", "repo": "%s", "tags": []}
                        """.formatted(repo, owner, repo));
    }

    private static final String GAMEBOT_VETTED = """
            {
              "schemaVersion" : 1,
              "owner" : "LiQiyeDev",
              "repo" : "botmaker-gamebot",
              "vettedVersion" : "v0.1.0",
              "vettedAt" : "2026-09-16",
              "vettedBy" : "LiQiyeDev"
            }
            """;

    @Test
    void aBotWithARecordIsVettedAtItsReleaseAndOneWithoutIsCommunity() {
        Catalog.Vetted record = Catalog.readVetted("vetted/LiQiyeDev-botmaker-gamebot.json", "vsha",
                GAMEBOT_VETTED).orElseThrow();
        List<Catalog.Entry> attached = Catalog.attach(
                List.of(bot("liqiyedev", "botmaker-gamebot"), bot("LiQiyeDev", "Update")), List.of(record));

        assertSame(record, attached.get(0).vetted(), "matched without case, as GalleryCatalog matches");
        assertEquals("Vetted v0.1.0", attached.get(0).tierLabel());
        assertNull(attached.get(1).vetted());
        assertEquals("Community", attached.get(1).tierLabel());
    }

    @Test
    void aPluginHasNoTier() {
        Catalog.Entry plugin = Catalog.read(Catalog.Kind.PLUGIN, "plugins/x.json", "s", "x", "{\"id\": \"x\"}");
        assertEquals("", plugin.tierLabel());
        assertSame(plugin, Catalog.attach(List.of(plugin), List.of()).get(0));
    }

    @Test
    void anUnreadableVettingIsNoVetting() {
        assertTrue(Catalog.readVetted("vetted/a-b.json", "s", "{ nope").isEmpty());
        assertTrue(Catalog.readVetted("vetted/a-b.json", "s", null).isEmpty());
        assertTrue(Catalog.readVetted("vetted/a-b.json", "s", "{\"vettedVersion\": \"v1\"}").isEmpty(),
                "a record naming no bot cannot be attached to one");
    }

    @Test
    void vetWritesTheFileTheGalleryAlreadyHolds() throws Exception {
        VettedRecord record = Vetting.record(bot("LiQiyeDev", "botmaker-gamebot"), "v0.1.0", "LiQiyeDev",
                LocalDate.of(2026, 9, 16));

        assertEquals("vetted/LiQiyeDev-botmaker-gamebot.json", record.path());
        // Byte for byte the record committed by hand in phase 2, so a dashboard vetting diffs as nothing new.
        assertEquals(GAMEBOT_VETTED.strip(), Vetting.json(record).strip());
        assertEquals(record, Registry.mapper().readValue(Vetting.json(record), VettedRecord.class));
    }
}
