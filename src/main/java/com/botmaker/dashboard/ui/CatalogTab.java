package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.EntryFields;
import com.botmaker.dashboard.ui.widgets.LinkBar;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
 *
 * <p><b>The two writes are pull requests, and they are gated on {@link Admin#canWrite()}</b> exactly as the
 * Queue tab's are — which is a courtesy and not a boundary, since GitHub answers 403 regardless. What it
 * buys is that the operator learns they cannot do it before typing an edit rather than after. Neither
 * action changes {@code main}: Edit proposes new text, Unpublish proposes removing the file, and a human
 * merges or does not.
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
    private final LinkBar links = new LinkBar(url -> Browse.open(url, status::setText));
    private final Button edit = new Button("Edit…");
    private final Button unpublish = new Button("Unpublish…");

    private Admin admin = new Admin(false, "not checked yet");

    public CatalogTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        where.getStyleClass().add("status-line");

        refresh.setOnAction(e -> reload());
        edit.setOnAction(e -> withSelected(this::edit));
        unpublish.setOnAction(e -> withSelected(this::unpublish));

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
        heading.setText(entry.kindLabel() + " · " + entry.label()
                + (entry.name().equals(entry.label()) ? "" : " — " + entry.name()));
        where.setText(entry.kind().repo() + " · " + entry.path());
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
     * Proposes new text for the selected entry.
     *
     * <p><b>A text area over the JSON, not a form built from a field list.</b> A form can only show the
     * keys it was written to know about, so it would silently drop one an entry carries and this window has
     * never heard of — the same reason {@link EntryFields} reads the file rather than a schema. The dialog
     * says whether the text parses, and does not refuse it: whether an entry is <i>good</i> is
     * {@code RegistryGate}'s answer on the pull request, and a syntax opinion formed here is the first step
     * towards a second gate.
     */
    private void edit(Catalog.Entry entry) {
        TextArea json = new TextArea(entry.json() == null ? "" : entry.json());
        json.getStyleClass().add("output-text");
        json.setPrefRowCount(20);
        json.setPrefColumnCount(90);

        Label parses = new Label();
        parses.getStyleClass().add("status-line");
        json.textProperty().addListener((obs, was, now) -> sayWhetherItParses(parses, now));
        sayWhetherItParses(parses, json.getText());

        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit " + entry.id());
        dialog.setHeaderText("Opens a pull request against " + entry.kind().repo()
                + ". Nothing on " + "main" + " changes until somebody merges it.");
        VBox body = new VBox(8, json, parses, why);
        VBox.setVgrow(json, Priority.ALWAYS);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        pane.getButtonTypes().setAll(new ButtonType("Open pull request", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        Themed.dialog(dialog, window());

        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        String text = json.getText();
        if (text.equals(entry.json())) {
            // Not a gate — arithmetic. A pull request that changes nothing is one somebody has to close.
            status.setText("Nothing changed, so no pull request was opened.");
            return;
        }
        propose(entry, "Edit", Catalog.edit(client, auth, entry, text, why.getText()));
    }

    private static void sayWhetherItParses(Label label, String text) {
        try {
            new ObjectMapper().readTree(text);
            label.setText("Parses as JSON.");
            label.getStyleClass().removeAll("cell--broken");
        } catch (Exception e) {
            label.setText("Not valid JSON — the gate will refuse this: " + e.getMessage());
            if (!label.getStyleClass().contains("cell--broken")) {
                label.getStyleClass().add("cell--broken");
            }
        }
    }

    /**
     * Proposes removing the selected entry.
     *
     * <p><b>Typing the id, not clicking Yes.</b> Merging this makes a plugin disappear from every user's
     * Manage Plugins and a bot from the gallery Studio reads — and the id is the one thing that cannot be
     * recovered by re-submitting, because the file name is the claim. A confirmation somebody can dismiss
     * by reflex is not one.
     */
    private void unpublish(Catalog.Entry entry) {
        TextField typed = new TextField();
        typed.setPromptText(entry.id());
        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Unpublish " + entry.id());
        dialog.setHeaderText("This opens a pull request that deletes " + entry.path() + " from "
                + entry.kind().repo() + ".\nNothing is removed until somebody merges it. Merging it removes "
                + entry.id() + " for everyone on the next index build.\n\nType the id to confirm:");
        VBox body = new VBox(8, typed, why);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        pane.getButtonTypes().setAll(open, ButtonType.CANCEL);
        pane.lookupButton(open).setDisable(true);
        typed.textProperty().addListener((obs, was, now) ->
                pane.lookupButton(open).setDisable(!entry.id().equals(now.trim())));
        Themed.dialog(dialog, window());

        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        propose(entry, "Unpublish", Catalog.unpublish(client, auth, entry, why.getText()));
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
                failed(error);
                return;
            }
            status.setText(what + " proposed as " + entry.kind().repo() + " #" + proposal.number()
                    + " — it is in the Queue tab now, with the gate's verdict.");
            opened(proposal);
        }));
    }

    /** Offers the pull request rather than opening a browser unasked. */
    private void opened(Catalog.Proposal proposal) {
        ButtonType openIt = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("Opened #" + proposal.number());
        alert.setContentText("Branch " + proposal.branch()
                + ".\nNothing is published or unpublished until it is merged.");
        alert.getButtonTypes().setAll(openIt, ButtonType.CLOSE);
        Themed.dialog(alert, window());
        alert.showAndWait()
                .filter(b -> b == openIt)
                .ifPresent(b -> Browse.open(proposal.url(), status::setText));
    }

    private void setWritesDisabled(boolean disabled) {
        edit.setDisable(disabled);
        unpublish.setDisable(disabled);
    }

    /**
     * Shows GitHub's own sentence.
     *
     * <p>A 403 because the token's scope was narrowed, a 409 because {@code main} moved under the blob sha,
     * and a 422 because the branch already exists are three different problems, and paraphrasing them into
     * "could not open a pull request" costs the operator the only line that says which.
     */
    private void failed(Throwable error) {
        TextArea text = new TextArea(message(error));
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("error-text");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("GitHub refused it");
        alert.getDialogPane().setContent(text);
        alert.getButtonTypes().setAll(ButtonType.OK);
        Themed.dialog(alert, window());
        alert.showAndWait();
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
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
