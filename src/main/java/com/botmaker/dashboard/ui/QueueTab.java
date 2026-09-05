package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.EntryFields;
import com.botmaker.dashboard.github.Queue;
import com.botmaker.dashboard.github.Submission;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The Queue tab: open submissions on the plugin registry and the gallery, and the verdict somebody else
 * already reached about each one.
 *
 * <p><b>Nothing here validates a submission.</b> The Gate column is the registry CI's own check-run
 * conclusion — {@code RegistryGate}, from {@code botmaker-cli}'s main artifact, which is the same code the
 * submitter ran as {@code botmaker plugin validate}. A cheaper opinion formed in this window would
 * eventually disagree with the check that actually refuses the pull request, and the operator would have no
 * way to know which one was right.
 *
 * <p><b>The one thing this tab does judge is shape</b>, and it is a property of the layout rather than of a
 * plugin: one file per entry means a submission is exactly one added file, so a pull request touching
 * anything else is flagged loudly. That is the shape {@code plugins/<id>.json} exists to make impossible.
 *
 * <p>The write buttons are enabled by {@link Admin#canWrite()} — {@code permissions.push} on the registry,
 * read from GitHub. That is not a security boundary and must not be written as one: GitHub answers 403
 * regardless. What it buys is that the operator learns they cannot merge <i>before</i> writing a review.
 */
public final class QueueTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;

    private final ObservableList<Submission> submissions = FXCollections.observableArrayList();
    private final TableView<Submission> table = new TableView<>(submissions);

    private final ObservableList<EntryFields.Field> fields = FXCollections.observableArrayList();
    private final TableView<EntryFields.Field> entry = new TableView<>(fields);

    private final Label heading = new Label();
    private final Label shape = new Label();
    private final Label verdict = new Label();
    private final Label status = new Label();

    private final Button refresh = new Button("Refresh");
    private final Button openOnGitHub = new Button("Open on GitHub");
    private final Button approve = new Button("Approve");
    private final Button requestChanges = new Button("Request changes…");
    private final Button merge = new Button("Merge");

    private Admin admin = new Admin(false, "not checked yet");

    public QueueTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        shape.setWrapText(true);
        verdict.getStyleClass().add("status-line");

        refresh.setOnAction(e -> reload());
        openOnGitHub.setOnAction(e -> withSelected(s -> Browse.open(s.url())));
        approve.setOnAction(e -> act("Approved", Queue::approve));
        merge.setOnAction(e -> act("Merged", Queue::merge));
        requestChanges.setOnAction(e -> requestChanges());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, openOnGitHub, approve, requestChanges, merge, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        buildColumns();
        table.setPlaceholder(new Label("Nothing waiting — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));

        entry.setPlaceholder(new Label("Pick a submission above to see the entry it adds."));
        entry.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        entry.getColumns().setAll(
                field("Field", 220, EntryFields.Field::name),
                field("Value", 480, EntryFields.Field::value));

        VBox bottom = new VBox(8, heading, shape, verdict, entry);
        bottom.setPadding(new Insets(12));
        VBox.setVgrow(entry, Priority.ALWAYS);

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.42);

        setTop(bar);
        setCenter(split);

        gateButtons();
        reload();
    }

    /**
     * Called whenever the admin probe answers again — sign-in, sign-out, and once at startup.
     *
     * <p>Held rather than asked at click time because the buttons have to be disabled *before* a review is
     * written, which is the entire value of reading the permission at all.
     */
    public void setAdmin(Admin admin) {
        this.admin = admin;
        gateButtons();
    }

    /** Both repositories' open pull requests, off the FX thread. */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + String.join(" and ", Queue.REPOS) + " …");
        Queue.open(client, auth).whenComplete((found, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                status.setText("Could not read the queue: " + error.getMessage());
                return;
            }
            submissions.setAll(found);
            long wrong = found.stream().filter(s -> !s.wellShaped()).count();
            status.setText(found.size() + " open"
                    + (wrong == 0 ? "" : " — " + wrong + " touching more than one entry file"));
            gateButtons();
        }));
    }

    private void show(Submission submission) {
        fields.clear();
        gateButtons();
        if (submission == null) {
            heading.setText("");
            shape.setText("");
            verdict.setText("");
            return;
        }
        heading.setText(submission.label() + " — " + submission.title() + "  ·  @" + submission.author());
        shape.setText(submission.shape());
        shape.getStyleClass().removeAll("cell--broken", "cell--dim");
        shape.getStyleClass().add(submission.wellShaped() ? "cell--dim" : "cell--broken");
        verdict.setText("Gate: " + submission.checks().text());

        if (submission.entryFile().isEmpty()) {
            return;
        }
        Queue.entry(client, auth, submission).whenComplete((text, error) -> Platform.runLater(() -> {
            if (table.getSelectionModel().getSelectedItem() != submission) {
                return; // The operator moved on while it loaded; showing it now would be a lie.
            }
            fields.setAll(EntryFields.read(error != null ? null : text));
        }));
    }

    /**
     * Runs one write and reloads.
     *
     * <p>Failures are shown with GitHub's own message — a 403 because the token's scope was narrowed and a
     * 405 because the branch is protected are different problems, and paraphrasing them into "could not
     * merge" costs the operator the only sentence that says which.
     */
    private void act(String past, Write write) {
        Submission submission = table.getSelectionModel().getSelectedItem();
        if (submission == null) {
            return;
        }
        setWritesDisabled(true);
        status.setText(past.toLowerCase() + " " + submission.label() + " …");
        write.run(client, auth, submission).whenComplete((node, error) -> Platform.runLater(() -> {
            if (error != null) {
                setWritesDisabled(false);
                status.setText(past + " failed.");
                failed(error);
                return;
            }
            status.setText(past + " " + submission.label() + ".");
            reload();
        }));
    }

    private void requestChanges() {
        Submission submission = table.getSelectionModel().getSelectedItem();
        if (submission == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Request changes");
        dialog.setHeaderText("What does " + submission.label() + " need?");
        dialog.setContentText("Comment:");
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.showAndWait()
                .filter(comment -> !comment.isBlank())
                .ifPresent(comment -> act("Requested changes on",
                        (c, a, s) -> Queue.requestChanges(c, a, s, comment)));
    }

    /**
     * Which buttons are live.
     *
     * <p>Three conditions and they are separate on purpose: a row must be selected, the account must have
     * push on the registry, and — for merge alone — the shape must be right. A merge is the one action that
     * cannot be undone by another click, and the shape check is this app's own, so it refuses locally rather
     * than letting the operator discover the extra file in the commit.
     */
    private void gateButtons() {
        Submission selected = table.getSelectionModel().getSelectedItem();
        boolean row = selected != null;
        openOnGitHub.setDisable(!row);
        approve.setDisable(!row || !admin.canWrite());
        requestChanges.setDisable(!row || !admin.canWrite());
        merge.setDisable(!row || !admin.canWrite() || !selected.wellShaped());
        String why = admin.canWrite() ? "" : "  ·  read-only: " + admin.reason();
        if (!why.isEmpty() && status.getText() != null && !status.getText().endsWith(why)) {
            status.setText(status.getText() + why);
        }
    }

    private void setWritesDisabled(boolean disabled) {
        approve.setDisable(disabled);
        requestChanges.setDisable(disabled);
        merge.setDisable(disabled);
    }

    private void withSelected(java.util.function.Consumer<Submission> action) {
        Submission submission = table.getSelectionModel().getSelectedItem();
        if (submission != null) {
            action.accept(submission);
        }
    }

    private void failed(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        TextArea text = new TextArea(cause.getMessage());
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("error-text");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("GitHub refused it");
        alert.getDialogPane().setContent(text);
        alert.getButtonTypes().setAll(ButtonType.OK);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.showAndWait();
    }

    private void buildColumns() {
        table.getColumns().setAll(
                column("Submission", 180, Submission::label),
                column("Author", 130, s -> "@" + s.author()),
                column("Title", 260, Submission::title),
                shapeColumn(),
                gateColumn());
    }

    private static TableColumn<Submission, String> column(String title, double width,
                                                          Function<Submission, String> text) {
        TableColumn<Submission, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /** What the pull request adds — red the moment it adds anything besides its own entry file. */
    private static TableColumn<Submission, String> shapeColumn() {
        TableColumn<Submission, String> col = column("Adds", 300, Submission::shape);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--broken", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                getStyleClass().add(getTableRow().getItem().wellShaped() ? "cell--dim" : "cell--broken");
            }
        });
        return col;
    }

    /** The CI's conclusion, in its own words, coloured by what it means. */
    private static TableColumn<Submission, String> gateColumn() {
        TableColumn<Submission, String> col = column("Gate", 220, s -> s.checks().text());
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--ok", "cell--pending", "cell--broken", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                getStyleClass().add(switch (getTableRow().getItem().checks().verdict()) {
                    case PASSED -> "cell--ok";
                    case RUNNING -> "cell--pending";
                    case FAILED -> "cell--broken";
                    case NONE -> "cell--dim";
                });
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

    /** One GitHub write, so approve/merge/request-changes share the disable-report-reload dance. */
    @FunctionalInterface
    private interface Write {
        CompletableFuture<?> run(GitHubClient client, GitHubAuth auth, Submission submission);
    }
}
