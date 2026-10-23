package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.EntryFields;
import com.botmaker.dashboard.ui.widgets.LinkBar;
import com.botmaker.shared.github.GitHubAuth;
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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Catalog tab: what is <b>published</b> — every merged plugin entry. Bots left it on 2026-10-23 with the
 * gallery repository: a bot is listed by a topic on its own repository, and nothing about it is reviewed.
 *
 * <p><b>It is the other half of the Queue tab and the distinction is the point.</b> Queue lists open pull
 * requests, which is zero most days; that is the truthful answer to "what is waiting on me" and no answer at
 * all to "what can somebody install". This tab answers the second question, and it reads github.com rather
 * than the checked-out registry submodule, whose recorded pointer trails its own {@code main} whenever the
 * registry's CI regenerates an index.
 *
 * <p><b>Nothing here judges an entry.</b> Everything listed is already merged, and what let it in was
 * {@code RegistryGate}'s check run on the pull request that added it. A second opinion formed in this window
 * would eventually disagree with the gate, and the operator would have no way to know which was right.
 *
 * <p><b>The writes are pull requests, and they are gated on {@link Admin#canWrite()}</b> exactly as the
 * Queue tab's are — which is a courtesy and not a boundary, since GitHub answers 403 regardless. What it
 * buys is that the operator learns they cannot do it before typing an edit rather than after. None of them
 * changes {@code main}: {@link CatalogDialogs} asks, this tab opens the pull request, and a human merges or
 * does not.
 */
public final class CatalogTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;
    private final CatalogDialogs dialogs;

    private final ObservableList<Catalog.Entry> entries = FXCollections.observableArrayList();
    private final TableView<Catalog.Entry> table = new TableView<>(entries);

    private final ObservableList<EntryFields.Field> fields = FXCollections.observableArrayList();
    private final TableView<EntryFields.Field> detail = new TableView<>(fields);

    private final Label heading = new Label();
    private final Label where = new Label();
    private final Label status = new Label();

    private final Button refresh = new Button("Refresh");
    private final LinkBar links = new LinkBar(url -> Browse.open(url, status::setText));
    private final Button edit = new Button("Edit…");
    private final Button unpublish = new Button("Unpublish…");

    private Admin admin = new Admin(false, "not checked yet");

    public CatalogTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;
        this.dialogs = new CatalogDialogs(client, auth, this::window, status::setText);

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        where.getStyleClass().add("status-line");

        refresh.setOnAction(e -> reload());
        edit.setOnAction(e -> withSelected(entry -> dialogs.edit(entry)
                .ifPresent(running -> propose(entry, "Edit", running))));
        unpublish.setOnAction(e -> withSelected(entry -> dialogs.unpublish(entry)
                .ifPresent(running -> propose(entry, "Unpublish", running))));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, edit, unpublish, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        buildColumns();
        table.setPlaceholder(new Label("Nothing published yet — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));
        table.setRowFactory(t -> {
            TableRow<Catalog.Entry> row = new TableRow<>();
            // Built when the menu is asked for, so a recycled row never offers the previous entry's pages.
            row.setOnContextMenuRequested(e -> {
                if (row.getItem() != null) {
                    LinkBar.menu(row.getItem().links(), url -> Browse.open(url, status::setText))
                            .show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });

        detail.setPlaceholder(new Label("Pick an entry above to see what it says."));
        detail.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detail.getColumns().setAll(
                field("Field", 220, EntryFields.Field::name),
                field("Value", 480, EntryFields.Field::value));

        VBox bottom = new VBox(8, heading, where, links, detail);
        bottom.setPadding(new Insets(12));
        VBox.setVgrow(detail, Priority.ALWAYS);

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.52);

        setTop(bar);
        setCenter(split);

        // No reload here: whoever builds the tab reloads it once it has the admin verdict. Reading here as well
        // was a second listing of the registry at every start (2026-09-29).
        gateButtons();
    }

    /** The registry's merged entries, off the FX thread. */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + Catalog.REPO + " …");
        Catalog.list(client, auth).whenComplete((found, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                // Named, not paraphrased: an offline machine, a rate limit and a renamed repository are
                // different problems and only GitHub's own sentence says which.
                status.setText("Could not read the catalog: " + message(error));
                return;
            }
            entries.setAll(found);
            status.setText(summary(found) + readOnly());
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
    private static String summary(List<Catalog.Entry> found) {
        long plugins = found.size();
        long broken = found.stream().filter(e -> !e.readable()).count();
        return plugins + " plugin" + (plugins == 1 ? "" : "s")
                + (broken == 0 ? "" : " — " + broken + " that could not be read");
    }

    /** Why the write buttons are dark, on the one line the operator is already reading. */
    private String readOnly() {
        return admin.canWrite() ? "" : "  ·  read-only: " + admin.reason();
    }

    private void show(Catalog.Entry entry) {
        gateButtons();
        if (entry == null) {
            heading.setText("");
            where.setText("");
            links.show(List.of());
            fields.clear();
            return;
        }
        links.show(entry.links());
        heading.setText("Plugin · " + entry.label()
                + (entry.name().equals(entry.label()) ? "" : " — " + entry.name()));
        where.setText(Catalog.REPO + " · " + entry.path());
        where.getStyleClass().removeAll("cell--broken", "cell--dim");
        where.getStyleClass().add(entry.readable() ? "cell--dim" : "cell--broken");
        // The bytes are already in hand from the listing pass, so there is nothing to fetch and nothing to
        // race: no stale-selection guard is needed here, unlike the Queue tab's.
        fields.setAll(EntryFields.read(entry.json()));
    }

    /**
     * Called whenever the admin probe answers again — sign-in, sign-out, and once at startup.
     *
     * <p>Held rather than asked at click time, for the Queue tab's reason: the buttons have to be disabled
     * <i>before</i> an edit is typed, which is the entire value of reading the permission at all.
     */
    public void setAdmin(Admin admin) {
        this.admin = admin;
        gateButtons();
    }

    /**
     * Which buttons are live.
     *
     * <p>Unpublish additionally requires a <b>readable</b> entry. Not as a judgement — an unreadable entry
     * is exactly one worth removing — but because the confirmation asks the operator to type the entry's
     * id, and the id of an entry that did not parse is the filename this window guessed. Asking somebody to
     * confirm a removal by typing a guess is not a confirmation.
     */
    private void gateButtons() {
        Catalog.Entry selected = table.getSelectionModel().getSelectedItem();
        boolean row = selected != null;
        edit.setDisable(!row || !admin.canWrite());
        unpublish.setDisable(!row || !admin.canWrite() || !selected.readable());
    }

    /**
     * Runs one proposal and says what became of it.
     *
     * <p>The catalog is <b>not</b> reloaded afterwards, and that is the honest thing: nothing about what is
     * published has changed, because a pull request is not a merge. The Queue tab is where the proposal now
     * lives, with the gate's verdict against it.
     */
    private void propose(Catalog.Entry entry, String what, CompletableFuture<Catalog.Proposal> running) {
        setWritesDisabled(true);
        status.setText(what.toLowerCase() + " " + entry.id() + " — opening a pull request …");
        running.whenComplete((proposal, error) -> Platform.runLater(() -> {
            setWritesDisabled(false);
            gateButtons();
            if (error != null) {
                status.setText(what + " failed.");
                dialogs.failed(message(error));
                return;
            }
            status.setText(what + " proposed as " + Catalog.REPO + " #" + proposal.number()
                    + " — it is in the Queue tab now, with the gate's verdict.");
            dialogs.opened(proposal);
        }));
    }

    private void setWritesDisabled(boolean disabled) {
        edit.setDisable(disabled);
        unpublish.setDisable(disabled);
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void withSelected(Consumer<Catalog.Entry> action) {
        Catalog.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry != null) {
            action.accept(entry);
        }
    }

    static String message(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void buildColumns() {
        table.getColumns().setAll(
                entryColumn(),
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

    /** The entry's id, red when the entry could not be parsed at all. */
    private static TableColumn<Catalog.Entry, String> entryColumn() {
        TableColumn<Catalog.Entry, String> col = column("Entry", 260, Catalog.Entry::label);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--broken");
                if (!empty && getTableRow() != null && getTableRow().getItem() != null
                        && !getTableRow().getItem().readable()) {
                    getStyleClass().add("cell--broken");
                }
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
