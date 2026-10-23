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
               {"id": "com.botmaker.sdk", "repo": "BotMakerDev/botmaker-sdk", "tag": "v1.5.0", "rank": "admin",
                "name": "BotMaker SDK"},
               {"id": "com.example.zoom", "repo": "somedev/zoom", "tag": "v0.1.0", "rank": "community"}
             ],
             "bots": [
               {"repo": "somedev/firestone", "tag": "v2.0.0", "rank": "community", "stars": 12,
                "forkOf": "LiQiyeDev/firestone"}
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
                .containsAll(List.of("v2.0.0  ·  ★ 12", "based on LiQiyeDev/firestone")));
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
        assertFalse(hasButton("List this tag"));
        assertFalse(hasButton("Remove from catalog…"));

        interact(() -> tab.setAdmin(new Admin(true, "", "LiQiyeDev", 298085676)));
        assertTrue(hasButton("List this tag"));
        assertTrue(hasButton("Remove from catalog…"));

        Node owner = card("firestone").lookup(".card-owner");
        clickOn(owner);
        assertTrue(hasButton("Save rank"));
        interact(() -> tab.setAdmin(new Admin(false, "not signed in")));
        assertFalse(hasButton("Save rank"));
    }

    @Test
    void aRedrawKeepsAHalfTypedTagAndAnotherCardStartsEmpty() {
        interact(() -> tab.setAdmin(new Admin(true, "", "LiQiyeDev", 298085676)));
        clickOn(card("firestone"));
        TextField tag = lookup(".text-field").queryAll().stream().map(TextField.class::cast)
                .filter(f -> f.getPromptText().startsWith("a tag")).findFirst().orElseThrow();
        interact(() -> tag.setText("v3.0.0"));
        clickOn(chip("Bots"));
        assertEquals("v3.0.0", tag.getText(), "a chip redraws the panel; the typing stays");
        clickOn(chip("Plugins"));
        clickOn(card("zoom"));
        assertEquals("", tag.getText());
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
