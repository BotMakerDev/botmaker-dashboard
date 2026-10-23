package com.botmaker.dashboard.ui;

import com.botmaker.cli.catalog.Rank;
import com.botmaker.dashboard.github.Authors;
import com.botmaker.dashboard.github.CatalogRow;
import javafx.event.Event;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * The Catalog tab's cards: one per bot or plugin, one per author. Presentation only — what a card says is already
 * a field of {@link CatalogRow} or {@link Authors.Author}, and which cards show is {@code CatalogFilter}'s.
 */
final class CatalogCards {

    /** On the card whose details the side panel shows. */
    static final String SELECTED = "catalog-card--selected";

    static final double WIDTH = 250;

    private CatalogCards() {
    }

    /** The badge a rank reads as, coloured by {@code rank--<id>}. */
    static Label rankBadge(Rank rank) {
        Label badge = new Label(rank.displayName());
        badge.getStyleClass().addAll("rank-badge", "rank--" + rank.id());
        return badge;
    }

    /**
     * A bot or plugin card. Its owner is a link that calls {@code onOwner} with the login; the click stops there,
     * so it never also selects the card it sits on.
     */
    static VBox entry(CatalogRow row, Consumer<String> onOwner) {
        Hyperlink owner = new Hyperlink("@" + row.owner());
        owner.getStyleClass().add("card-owner");
        owner.setOnAction(e -> onOwner.accept(row.owner()));
        owner.addEventHandler(MouseEvent.MOUSE_CLICKED, Event::consume);

        VBox card = card(row.kind().displayName(), row.rank(), row.name());
        card.getChildren().addAll(owner, meta(row.tag() + "  ·  ★ " + row.stars()));
        if (!row.forkOf().isEmpty()) {
            card.getChildren().add(meta("based on " + row.forkOf()));
        }
        if (!row.description().isEmpty()) {
            Label description = new Label(row.description());
            description.getStyleClass().add("card-description");
            description.setWrapText(true);
            description.setMaxHeight(34);
            card.getChildren().add(description);
        }
        return card;
    }

    /** An author card: login, rank, and how much they list. */
    static VBox author(Authors.Author author) {
        VBox card = card("Author", author.rank(), author.login());
        card.getChildren().add(meta(count(author.bots().size(), "bot") + "  ·  "
                + count(author.plugins().size(), "plugin")));
        return card;
    }

    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static VBox card(String kind, Rank rank, String name) {
        Label kindLabel = new Label(kind);
        kindLabel.getStyleClass().add("card-kind");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(6, kindLabel, spacer, rankBadge(rank));

        Label title = new Label(name);
        title.getStyleClass().add("card-name");

        VBox card = new VBox(4, top, title);
        card.getStyleClass().add("catalog-card");
        card.setPrefWidth(WIDTH);
        return card;
    }

    private static Label meta(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("card-meta");
        return label;
    }
}
