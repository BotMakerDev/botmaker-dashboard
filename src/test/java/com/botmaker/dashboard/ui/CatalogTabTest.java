package com.botmaker.dashboard.ui;

import com.botmaker.cli.catalog.Publishers;
import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Catalog;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The card grid over a fixed {@code catalog.json}: what the chips show, an owner link, and who sees the edits. */
class CatalogTabTest extends FxHeadless {

    private static final String CATALOG = """
            {"plugins": [
               {"id": "com.botmaker.sdk", "repo": "BotMakerDev/botmaker-sdk", "rank": "admin",
                "name": "BotMaker SDK", "releases": [{"tag": "v1.5.0", "commit": "1111111111"}]},
               {"id": "com.example.zoom", "repo": "somedev/zoom", "rank": "community",
                "releases": [{"tag": "v0.1.0", "commit": "3333333333"}]}
             ],
             "bots": [
               {"repo": "somedev/firestone", "rank": "community", "stars": 12, "forkOf": "LiQiyeDev/firestone",
                "releases": [{"tag": "v2.0.0", "commit": "4444444444"}, {"tag": "v1.0.0", "commit": "5555555555"}]}
             ]}
            """;

    private static final Publishers PUBLISHERS = new Publishers(
            List.of(new Publishers.Publisher(330995542, "BotMakerDev")), List.of());

    private CatalogTab tab;

    @BeforeEach
    void fill() {
        interact(() -> {
            tab = new CatalogTab(null, null);
            tab.fill(Catalog.read(CATALOG), PUBLISHERS);
            show(tab);
        });
    }

    private List<String> cardKinds() {
        return lookup(".catalog-card .card-kind").queryAll().stream().map(n -> ((Label) n).getText()).toList();
    }

    private Node card(String name) {
        return lookup(".catalog-card").queryAll().stream()
                .filter(card -> card.lookupAll(".card-name").stream().anyMatch(n -> ((Label) n).getText().equals(name)))
                .findFirst().orElseThrow(() -> new AssertionError("no card named " + name));
    }

    private ToggleButton chip(String text) {
        return lookup(".chip").queryAll().stream().map(ToggleButton.class::cast)
                .filter(chip -> chip.getText().equals(text)).findFirst().orElseThrow();
    }

    private boolean hasButton(String text) {
        return lookup(".button").queryAll().stream()
                .anyMatch(n -> n instanceof Button button && button.getText().equals(text));
    }

    @Test
    void allShowsBothKindsAndAKindChipHidesTheOther() {
        assertEquals(List.of("Plugin", "Plugin", "Bot"), cardKinds());
        clickOn(chip("Bots"));
        assertEquals(List.of("Bot"), cardKinds());
        clickOn(chip("Plugins"));
        assertEquals(List.of("Plugin", "Plugin"), cardKinds());
    }

    @Test
    void aCardSaysItsRankTagAndWhatItIsBasedOn() {
        Node firestone = card("firestone");
        assertTrue(firestone.lookupAll(".rank--community").stream().anyMatch(n -> ((Label) n).getText().equals("Community")));
        assertTrue(firestone.lookupAll(".card-meta").stream().map(n -> ((Label) n).getText()).toList()
                .containsAll(List.of("v2.0.0 (+1 older)  ·  ★ 12", "based on LiQiyeDev/firestone")));
        assertTrue(card("zoom").lookupAll(".card-meta").stream().anyMatch(n -> ((Label) n).getText().equals("v0.1.0  ·  ★ 0")));
        assertFalse(card("BotMaker SDK").lookupAll(".rank--admin").isEmpty(), "admin reads Official");
    }

    @Test
    void clickingAnOwnerSelectsTheAuthorCard() {
        Hyperlink owner = (Hyperlink) card("firestone").lookup(".card-owner");
        clickOn(owner);
        assertTrue(chip("Authors").isSelected());
        assertEquals(List.of("Author", "Author"), cardKinds(), "BotMakerDev and somedev");
        Node selected = lookup("." + CatalogCards.SELECTED).query();
        assertEquals("somedev", ((Label) selected.lookup(".card-name")).getText());
        assertTrue(selected.lookupAll(".card-meta").stream().anyMatch(n -> ((Label) n).getText().equals("1 bot  ·  1 plugin")));
    }

    @Test
    void theEditsAreOfferedToAnAdminOnly() {
        clickOn(card("firestone"));
        assertEquals(0, removeButtons());
        assertFalse(hasButton("Remove from catalog…"));

        interact(() -> tab.setAdmin(new Admin(true, "", "LiQiyeDev", 298085676)));
        assertEquals(2, removeButtons(), "one beside each release");
        assertTrue(hasButton("Remove from catalog…"));

        Node owner = card("firestone").lookup(".card-owner");
        clickOn(owner);
        assertTrue(hasButton("Save rank"));
        interact(() -> tab.setAdmin(new Admin(false, "not signed in")));
        assertFalse(hasButton("Save rank"));
    }

    @Test
    void thePanelListsEveryApprovedReleaseNewestFirst() {
        clickOn(card("firestone"));
        List<String> meta = lookup(".card-meta").queryAll().stream().map(n -> ((Label) n).getText()).toList();
        assertTrue(meta.indexOf("v2.0.0  ·  4444444") >= 0, meta.toString());
        assertTrue(meta.indexOf("v2.0.0  ·  4444444") < meta.indexOf("v1.0.0  ·  5555555"), meta.toString());
    }

    @Test
    void theOnlyReleaseHasNoRemoveOfItsOwn() {
        interact(() -> tab.setAdmin(new Admin(true, "", "LiQiyeDev", 298085676)));
        clickOn(chip("Plugins"));
        clickOn(card("zoom"));
        assertEquals(0, removeButtons(), "it goes with the entry");
        assertTrue(hasButton("Remove from catalog…"));
    }

    @Test
    void aRedrawKeepsAHalfTypedReasonAndAnotherCardStartsEmpty() {
        interact(() -> tab.setAdmin(new Admin(true, "", "LiQiyeDev", 298085676)));
        clickOn(card("firestone"));
        TextField why = lookup(".text-field").queryAll().stream().map(TextField.class::cast)
                .filter(f -> f.getPromptText().startsWith("Why")).findFirst().orElseThrow();
        interact(() -> why.setText("broken build"));
        clickOn(chip("Bots"));
        assertEquals("broken build", why.getText(), "a chip redraws the panel; the typing stays");
        clickOn(chip("Plugins"));
        clickOn(card("zoom"));
        assertEquals("", why.getText());
    }

    private long removeButtons() {
        return lookup(".button").queryAll().stream()
                .filter(n -> n instanceof Button button && button.getText().equals("Remove")).count();
    }

    @Test
    void wordsNoCardHasSayNothingMatches() {
        Node search = lookup(".text-field").query();
        clickOn(search).write("nothing-like-this");
        assertTrue(lookup(".catalog-card").queryAll().isEmpty());
        assertTrue(lookup(".placeholder-body").queryAll().stream()
                .anyMatch(n -> ((Label) n).getText().startsWith("Nothing matches")));
    }
}
