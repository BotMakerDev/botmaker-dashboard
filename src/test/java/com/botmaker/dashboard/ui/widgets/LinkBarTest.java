package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.ui.FxHeadless;
import com.botmaker.dashboard.umbrella.Links;
import javafx.scene.control.ContextMenu;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The buttons and the menu are drawn from one list, so the two cannot offer different pages. */
class LinkBarTest extends FxHeadless {

    private final List<String> opened = new ArrayList<>();

    @Test
    void eachButtonOpensItsOwnPage() {
        LinkBar bar = new LinkBar(opened::add);
        interact(() -> {
            show(bar);
            bar.show(Links.forModule("botmaker-sdk"));
        });

        clickOn("JitPack");
        clickOn("Releases");
        assertEquals(List.of("https://jitpack.io/#LiQiyeDev/botmaker-sdk",
                "https://github.com/LiQiyeDev/botmaker-sdk/releases"), opened);
    }

    @Test
    void aRedrawReplacesTheButtonsRatherThanAddingToThem() {
        LinkBar bar = new LinkBar(opened::add);
        interact(() -> {
            show(bar);
            bar.show(Links.forModule("botmaker-sdk", Optional.of("v1.1.6"), 4));
            bar.show(Links.forModule("botmaker-cli"));
        });

        assertEquals(4, bar.getChildren().size());
    }

    @Test
    void theMenuOffersTheSamePagesTheButtonsDo() {
        List<Links.Link> links = Links.forModule("botmaker-sdk", Optional.of("v1.1.6"), 4);
        ContextMenu menu = LinkBar.menu(links, opened::add);

        assertEquals(List.of("Open GitHub", "Open Actions", "Open JitPack", "Open Releases",
                        "Open Changes since v1.1.6"),
                menu.getItems().stream().map(javafx.scene.control.MenuItem::getText).toList());

        interact(() -> menu.getItems().get(4).fire());
        assertEquals(List.of("https://github.com/LiQiyeDev/botmaker-sdk/compare/v1.1.6...main"), opened);
    }
}
