package com.botmaker.dashboard.github;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything {@link Catalog} decides, as a pure function over one entry file's text.
 *
 * <p>No network and no JavaFX, which is the module's own rule: the HTTP shape is {@code Contents}' and the
 * rendering is the tab's, so what is left here is the reading, and it is all testable.
 */
class CatalogTest {

    private static Catalog.Entry plugin(String json) {
        return Catalog.read("plugins/com.botmaker.sdk.json", "sha1", "com.botmaker.sdk", json);
    }

    @Test
    void aPluginEntryIsReadThroughTheRegistrysOwnRecord() {
        Catalog.Entry entry = plugin("""
                {"id": "com.botmaker.sdk", "name": "BotMaker SDK",
                 "coordinate": "com.github.BotMakerDev:botmaker-sdk",
                 "description": "The palette and the bot runtime.", "tags": ["sdk", "official"]}
                """);
        assertTrue(entry.readable());
        assertEquals("com.botmaker.sdk", entry.id());
        assertEquals("BotMaker SDK", entry.name());
        assertEquals("sdk, official", entry.tagLine());
    }

    @Test
    void aKeyThisWindowHasNeverHeardOfDoesNotMakeAnEntryUnreadable() {
        // The registry's own lenient mapper, so a field added to the entry shape tomorrow lists today
        // rather than reading as a corrupt file. It still reaches the field view, which reads the raw text.
        Catalog.Entry entry = plugin("""
                {"id": "com.example.demo", "name": "Demo", "somethingAddedLater": 3}
                """);
        assertTrue(entry.readable());
        assertEquals("com.example.demo", entry.id());
        assertEquals("3", EntryFields.read(entry.json()).stream()
                .filter(f -> f.name().equals("somethingAddedLater")).findFirst()
                .orElseThrow().value());
    }

    @Test
    void brokenJsonIsARowWithItsFilenameAsItsIdentity() {
        // It is on main, so somebody merged it. Dropping the row is the one thing that must not happen —
        // an entry the window cannot read is exactly the entry an operator has to see.
        Catalog.Entry entry = plugin("{ not json");
        assertFalse(entry.readable());
        assertEquals("com.botmaker.sdk", entry.id(), "the filename is the key, and it still parses");
        assertEquals("{ not json", entry.json(), "the raw text survives for the field view");
    }

    @Test
    void anUnreadableFileIsAlsoARow() {
        Catalog.Entry entry = plugin(null);
        assertFalse(entry.readable());
        assertEquals("com.botmaker.sdk", entry.id());
        assertEquals(List.of(), entry.tags());
    }

    @Test
    void anEntryWithNoNameFallsBackToItsIdentityRatherThanShowingBlank() {
        Catalog.Entry entry = plugin("""
                {"id": "com.example.demo", "name": ""}
                """);
        assertEquals("com.example.demo", entry.name());
    }

    @Test
    void theIdComesFromTheFilenameBecauseTheLayoutIsTheKey() {
        assertEquals("com.botmaker.sdk", Catalog.idFromFilename("com.botmaker.sdk.json"));
        assertEquals("no-extension", Catalog.idFromFilename("no-extension"));
    }

    @Test
    void anIdTheFileDisagreesWithLosesToTheFilename_onlyWhenTheFileSaysNothing() {
        // The gate refuses a mismatch, so a merged entry cannot have one; what this covers is an entry
        // whose id key is absent, where the filename is the only identity left.
        assertEquals("com.botmaker.sdk", plugin("{\"name\": \"No id here\"}").id());
        assertEquals("com.example.other", plugin("{\"id\": \"com.example.other\"}").id());
    }

    @Test
    void theRegistryIsQueuesOwnName() {
        // A list this tab does not keep: the repository name is Queue's.
        assertSame(Queue.REGISTRY, Catalog.REPO);
    }

    @Test
    void theUrlPointsAtTheFileOnMain() {
        assertEquals("https://github.com/" + Queue.REGISTRY + "/blob/main/plugins/com.botmaker.sdk.json",
                plugin("{\"id\": \"com.botmaker.sdk\"}").url());
    }

    @Test
    void anEntryLinksTheRepositoryItNamesAndThenItsOwnFile() {
        Catalog.Entry sdk = plugin("""
                {"id": "com.botmaker.sdk", "repo": "https://github.com/LiQiyeDev/botmaker-sdk.git"}
                """);
        assertEquals("LiQiyeDev/botmaker-sdk", sdk.repo());
        assertEquals(List.of("GitHub", "Actions", "JitPack", "Releases", "Entry file"),
                sdk.links().stream().map(l -> l.label()).toList());
        assertEquals("https://github.com/LiQiyeDev/botmaker-sdk", sdk.links().get(0).url());
        assertEquals(sdk.url(), sdk.links().get(4).url());
        assertEquals("https://jitpack.io/#LiQiyeDev/botmaker-sdk", sdk.links().get(2).url());
    }

    @Test
    void anEntryWhoseRepositoryCannotBeReadOffersOnlyItsFile() {
        // A link guessed from the id would open somebody else's page.
        assertEquals(List.of("Entry file"),
                plugin("{\"id\": \"com.example.demo\"}").links().stream().map(l -> l.label()).toList());
        assertEquals(List.of("Entry file"),
                plugin("{ not json").links().stream().map(l -> l.label()).toList());
    }

    @Test
    void aBranchNameIsALegalRefEvenThoughEveryPluginIdHasDotsInIt() {
        String branch = Catalog.branchFor("edit", plugin("{\"id\": \"com.botmaker.sdk\"}"));
        assertTrue(branch.startsWith("dashboard/edit-com.botmaker.sdk-"), branch);
        assertTrue(branch.matches("[A-Za-z0-9._/-]+"), "must be a legal git ref: " + branch);
    }

    @Test
    void aSlashInAnIdDoesNotBecomeASecondPathSegment() {
        // A branch named `dashboard/edit-a/b-…` is a different ref from the one the pull request names.
        String branch = Catalog.branchFor("unpublish", plugin("{\"id\": \"a/b\"}"));
        assertEquals(1, branch.chars().filter(c -> c == '/').count(), branch);
        assertTrue(branch.startsWith("dashboard/unpublish-a-b-"), branch);
    }

    @Test
    void twoBranchesForOneEntryDoNotCollide() throws Exception {
        // A second edit while the first pull request is still open would otherwise be refused with a 422
        // naming a reference that already exists, which reads as a bug in the window.
        Catalog.Entry entry = plugin("{\"id\": \"com.botmaker.sdk\"}");
        String first = Catalog.branchFor("edit", entry);
        Thread.sleep(1100);
        assertNotEquals(first, Catalog.branchFor("edit", entry));
    }

    @Test
    void theBlobShaTravelsWithTheEntrySoAWriteCanBeRefusedIfTheFileMoved() {
        // Optimistic locking rather than last-one-wins: two operators editing one entry is exactly the case
        // one-file-per-entry was shaped to make visible.
        assertEquals("sha1", plugin("{\"id\": \"com.botmaker.sdk\"}").sha());
    }
}
