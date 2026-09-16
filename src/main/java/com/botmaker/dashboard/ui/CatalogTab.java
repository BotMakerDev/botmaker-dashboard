package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.EntryFields;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/**
 * The Catalog tab: what is <b>published</b> — every merged plugin entry and every published bot, with the
 * templates among them marked.
 *
 * <p><b>It is the other half of the Queue tab and the distinction is the point.</b> Queue lists open pull
 * requests, which is zero most days; that is the truthful answer to "what is waiting on me" and no answer at
 * all to "what can somebody install". This tab answers the second question, and it reads github.com rather
 * than the checked-out data submodules, whose recorded pointers trail their own {@code main} whenever either
 * repository's CI regenerates an index.
 *
 * <p><b>Nothing here judges an entry.</b> Everything listed is already merged, and what let it in was
 * {@code RegistryGate}'s check run on the pull request that added it. A second opinion formed in this window
 * would eventually disagree with the gate, and the operator would have no way to know which was right.
 */
public final class CatalogTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;

    private final ObservableList<Catalog.Entry> entries = FXCollections.observableArrayList();
    private final TableView<Catalog.Entry> table = new TableView<>(entries);

    private final ObservableList<EntryFields.Field> fields = FXCollections.observableArrayList();
    private final TableView<EntryFields.Field> detail = new TableView<>(fields);

    private final Label heading = new Label();
    private final Label where = new Label();
    private final Label status = new Label();

    private final Button refresh = new Button("Refresh");
    private final Button openOnGitHub = new Button("Open on GitHub");

    public CatalogTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        where.getStyleClass().add("status-line");

        refresh.setOnAction(e -> reload());
        openOnGitHub.setOnAction(e -> withSelected(entry -> Browse.open(entry.url())));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, openOnGitHub, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        buildColumns();
        table.setPlaceholder(new Label("Nothing published yet — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));

        detail.setPlaceholder(new Label("Pick an entry above to see what it says."));
        detail.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detail.getColumns().setAll(
                field("Field", 220, EntryFields.Field::name),
                field("Value", 480, EntryFields.Field::value));

        VBox bottom = new VBox(8, heading, where, detail);
        bottom.setPadding(new Insets(12));
        VBox.setVgrow(detail, Priority.ALWAYS);

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.52);

        setTop(bar);
        setCenter(split);

        gateButtons();
        reload();
    }

    /**
     * Both repositories' merged entries, off the FX thread.
     *
     * <p>The status line counts what was found by kind, because the two numbers answer different questions
     * and an operator reading "6 entries" learns neither.
     */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + String.join(" and ", Catalog.Kind.PLUGIN.repo(),
                Catalog.Kind.BOT.repo()) + " …");
        Catalog.list(client, auth).whenComplete((found, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                // Named, not paraphrased: an offline machine, a rate limit and a renamed repository are
                // different problems and only GitHub's own sentence says which.
                status.setText("Could not read the catalog: " + message(error));
                return;
            }
            entries.setAll(found);
            status.setText(summary(found));
            gateButtons();
        }));
    }

    /**
     * One line of counts.
     *
     * <p>Unreadable entries are counted separately rather than folded in. An entry on {@code main} that this
     * window cannot parse is a fact about the repository, not about this window, and burying it in a total
     * is how it stays unnoticed.
     */
    private static String summary(java.util.List<Catalog.Entry> found) {
        long plugins = found.stream().filter(e -> e.kind() == Catalog.Kind.PLUGIN).count();
        long bots = found.stream().filter(e -> e.kind() == Catalog.Kind.BOT).count();
        long templates = found.stream().filter(Catalog.Entry::template).count();
        long broken = found.stream().filter(e -> !e.readable()).count();
        return plugins + " plugin" + (plugins == 1 ? "" : "s")
                + " · " + bots + " bot" + (bots == 1 ? "" : "s")
                + " (" + templates + " template" + (templates == 1 ? "" : "s") + ")"
                + (broken == 0 ? "" : " — " + broken + " that could not be read");
    }

    private void show(Catalog.Entry entry) {
        gateButtons();
        if (entry == null) {
            heading.setText("");
            where.setText("");
            fields.clear();
            return;
        }
        heading.setText(entry.kindLabel() + " · " + entry.label()
                + (entry.name().equals(entry.label()) ? "" : " — " + entry.name()));
        where.setText(entry.kind().repo() + " · " + entry.path());
        where.getStyleClass().removeAll("cell--broken", "cell--dim");
        where.getStyleClass().add(entry.readable() ? "cell--dim" : "cell--broken");
        // The bytes are already in hand from the listing pass, so there is nothing to fetch and nothing to
        // race: no stale-selection guard is needed here, unlike the Queue tab's.
        fields.setAll(EntryFields.read(entry.json()));
    }

    private void gateButtons() {
        openOnGitHub.setDisable(table.getSelectionModel().getSelectedItem() == null);
    }

    private void withSelected(java.util.function.Consumer<Catalog.Entry> action) {
        Catalog.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry != null) {
            action.accept(entry);
        }
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void buildColumns() {
        table.getColumns().setAll(
                kindColumn(),
                column("Entry", 260, Catalog.Entry::label),
                column("Name", 180, Catalog.Entry::name),
                column("Tags", 200, Catalog.Entry::tagLine),
                column("Description", 380, Catalog.Entry::summary));
    }

    private static TableColumn<Catalog.Entry, String> column(String title, double width,
                                                             Function<Catalog.Entry, String> text) {
        TableColumn<Catalog.Entry, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /** Plugin / Bot / Template, red when the entry could not be parsed at all. */
    private static TableColumn<Catalog.Entry, String> kindColumn() {
        TableColumn<Catalog.Entry, String> col = column("Kind", 90, Catalog.Entry::kindLabel);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--ok", "cell--broken", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                Catalog.Entry entry = getTableRow().getItem();
                getStyleClass().add(!entry.readable() ? "cell--broken"
                        : entry.template() ? "cell--ok" : "cell--dim");
            }
        });
        return col;
    }

    private static TableColumn<EntryFields.Field, String> field(String title, double width,
                                                                Function<EntryFields.Field, String> text) {
        TableColumn<EntryFields.Field, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }
}
