package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.Links;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * A row of small link buttons, and the context menu that offers the same pages.
 *
 * <p>Both are drawn from one {@code List<Links.Link>}, so a row's buttons and its right-click menu cannot
 * disagree about where a page is. Opening is the caller's {@code open} — {@code Browse.open} in the app, a
 * list in a test — because this widget must not know how a browser is started (the AWT crash of phase 1 was
 * exactly a widget knowing).
 */
public final class LinkBar extends HBox {

    private final Consumer<String> open;

    public LinkBar(Consumer<String> open) {
        super(4);
        this.open = open;
        getStyleClass().add("link-bar");
    }

    /** Replaces the buttons; an empty list leaves the bar empty rather than showing dead buttons. */
    public void show(List<Links.Link> links) {
        getChildren().setAll(links.stream().map(this::button).toList());
    }

    private Button button(Links.Link link) {
        Button button = new Button(link.label());
        button.getStyleClass().add("link-button");
        button.setTooltip(new Tooltip(link.url()));
        button.setFocusTraversable(false);
        button.setOnAction(e -> open.accept(link.url()));
        return button;
    }

    /** The same pages as a context menu, one item per link. */
    public static ContextMenu menu(List<Links.Link> links, Consumer<String> open) {
        ContextMenu menu = new ContextMenu();
        for (Links.Link link : links) {
            MenuItem item = new MenuItem("Open " + link.label());
            item.setOnAction(e -> open.accept(link.url()));
            menu.getItems().add(item);
        }
        return menu;
    }
}
