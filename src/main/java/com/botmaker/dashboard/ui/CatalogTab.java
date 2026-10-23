package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.CatalogRow;
import com.botmaker.dashboard.ui.widgets.LinkBar;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * The Catalog tab: every listed bot and plugin, read from {@code catalog.json} on the catalog's {@code main} —
 * the file Studio reads ({@link Catalog}).
 *
 * <p>A table until the card grid replaces it (the catalog plan's Phase 10, 2026-10-23), with the admin's edits
 * ({@code CatalogEdits}) in its side panel. Nothing here judges an entry: what let it in was the catalog's
 * {@code validate} check.
 */
public final class CatalogTab extends BorderPane {

    private final GitHubClient client;

    private final ObservableList<CatalogRow> rows = FXCollections.observableArrayList();
    private final TableView<CatalogRow> table = new TableView<>(rows);

    private final Label heading = new Label();
    private final Label where = new Label();
    private final Label description = new Label();
    private final Label status = new Label();

    private final Button refresh = new Button("Refresh");
    private final LinkBar links = new LinkBar(url -> Browse.open(url, status::setText));

    private Admin admin = new Admin(false, "not checked yet");

    public CatalogTab(GitHubClient client) {
        this.client = client;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        where.getStyleClass().addAll("status-line", "cell--dim");
        description.setWrapText(true);

        refresh.setOnAction(e -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        table.getColumns().setAll(
                column("Kind", 80, row -> row.kind().displayName()),
                column("Name", 200, CatalogRow::name),
                column("Author", 140, CatalogRow::owner),
                column("Rank", 100, row -> row.rank().displayName()),
                column("Tag", 100, CatalogRow::tag),
                column("Description", 360, CatalogRow::description));
        table.setPlaceholder(new Label("Nothing listed yet — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));
        table.setRowFactory(t -> {
            TableRow<CatalogRow> row = new TableRow<>();
            // Built when the menu is asked for, so a recycled row never offers the previous entry's pages.
            row.setOnContextMenuRequested(e -> {
                if (row.getItem() != null) {
                    LinkBar.menu(row.getItem().links(), url -> Browse.open(url, status::setText))
                            .show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });

        VBox bottom = new VBox(8, heading, where, links, description);
        bottom.setPadding(new Insets(12));

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);

        setTop(bar);
        setCenter(split);
        // No reload here: whoever builds the tab reloads it once it has the admin verdict.
    }

    /** {@code catalog.json}, off the FX thread. */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + Catalog.REPO + " …");
        Catalog.fetch(client).whenComplete((read, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                status.setText("Could not read the catalog: " + QueueTab.message(error));
                return;
            }
            rows.setAll(read.rows());
            status.setText((read.problem().isEmpty() ? summary(read.rows()) : read.problem())
                    + (admin.canWrite() ? "" : "  ·  read-only: " + admin.reason()));
        }));
    }

    private static String summary(List<CatalogRow> found) {
        long bots = found.stream().filter(r -> r.kind() == CatalogRow.Kind.BOT).count();
        long plugins = found.size() - bots;
        return bots + " bot" + (bots == 1 ? "" : "s") + ", " + plugins + " plugin" + (plugins == 1 ? "" : "s");
    }

    private void show(CatalogRow row) {
        if (row == null) {
            heading.setText("");
            where.setText("");
            description.setText("");
            links.show(List.of());
            return;
        }
        heading.setText(row.kind().displayName() + " · " + row.name() + " — " + row.rank().displayName());
        where.setText(row.repo() + " @ " + row.tag() + " · " + row.entryPath()
                + (row.forkOf().isEmpty() ? "" : " · based on " + row.forkOf()));
        description.setText(row.description());
        links.show(row.links());
    }

    /** Called whenever the admin probe answers again — sign-in, sign-out, and once at startup. */
    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    private static TableColumn<CatalogRow, String> column(String title, double width,
                                                          Function<CatalogRow, String> text) {
        TableColumn<CatalogRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }
}
