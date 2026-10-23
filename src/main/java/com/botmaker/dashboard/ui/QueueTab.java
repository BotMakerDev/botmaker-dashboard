package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.github.Queue;
import com.botmaker.dashboard.github.Submission;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * The Queue tab: open pull requests on the catalog, read-only (2026-10-23).
 *
 * <p><b>Nobody reviews them.</b> The Check column is the catalog's own {@code validate} conclusion, and
 * {@code automerge.yml} merges what passed; the {@code waiting} label is its hold for the author's daily limit. So
 * this tab has no Approve, Request changes or Merge: it says what is in flight and why it has not merged yet.
 *
 * <p><b>The one thing it does judge is shape</b>, a property of the catalog's layout: one path per pull request,
 * so anything touching more is flagged loudly.
 */
public final class QueueTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;

    private final ObservableList<Submission> submissions = FXCollections.observableArrayList();
    private final TableView<Submission> table = new TableView<>(submissions);

    private final Label heading = new Label();
    private final Label shape = new Label();
    private final Label verdict = new Label();
    private final Label status = new Label();
    /** The file's fields at the head, read off its JSON ({@link Queue#fields}); the raw text is below them. */
    private final GridPane fields = new GridPane();
    private final TextArea entry = new TextArea();

    private final Button refresh = new Button("Refresh");
    private final Button openOnGitHub = new Button("Open on GitHub");

    public QueueTab(GitHubClient client, GitHubAuth auth) {
        this.client = client;
        this.auth = auth;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        shape.setWrapText(true);
        verdict.getStyleClass().add("status-line");
        verdict.setWrapText(true);
        entry.setEditable(false);
        entry.setPromptText("Pick a pull request above to see the file it would merge.");

        refresh.setOnAction(e -> reload());
        openOnGitHub.setOnAction(e -> {
            Submission selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Browse.open(selected.url(), status::setText);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, openOnGitHub, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        table.getColumns().setAll(
                column("Pull request", 160, Submission::label),
                column("Author", 130, s -> "@" + s.author()),
                column("Title", 240, Submission::title),
                shapeColumn(),
                checkColumn(),
                column("Waiting", 80, s -> s.waiting() ? Submission.WAITING : ""));
        table.setPlaceholder(new Label("Nothing open — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));

        fields.setHgap(12);
        fields.setVgap(4);
        VBox bottom = new VBox(8, heading, shape, verdict, fields, entry);
        bottom.setPadding(new Insets(12));
        VBox.setVgrow(entry, Priority.ALWAYS);

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.42);

        setTop(bar);
        setCenter(split);
        openOnGitHub.setDisable(true);
        // No reload here: whoever builds the tab reloads it (2026-09-29).
    }

    /** The catalog's open pull requests, off the FX thread. */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + Queue.REPO + " …");
        Queue.open(client, auth).whenComplete((found, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                status.setText("Could not read the queue: " + message(error));
                return;
            }
            submissions.setAll(found);
            long wrong = found.stream().filter(s -> !s.wellShaped()).count();
            status.setText(found.size() + " open"
                    + (wrong == 0 ? "" : " — " + wrong + " not one catalog file"));
        }));
    }

    private void show(Submission submission) {
        entry.clear();
        fields.getChildren().clear();
        openOnGitHub.setDisable(submission == null);
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
        verdict.setText("Check: " + submission.checks().text()
                + (submission.waiting() ? "  ·  waiting for the author's daily limit" : ""));

        if (submission.entryFile().isEmpty()) {
            return;
        }
        Queue.entry(client, auth, submission).whenComplete((text, error) -> Platform.runLater(() -> {
            if (table.getSelectionModel().getSelectedItem() != submission) {
                return; // The operator moved on while it loaded; showing it now would be a lie.
            }
            boolean missing = error != null || text == null;
            entry.setText(missing ? "(the file is removed, or could not be read)" : text);
            if (!missing) {
                Queue.fields(text).forEach(field -> {
                    int row = fields.getRowCount();
                    Label name = new Label(field.name());
                    name.getStyleClass().add("cell--dim");
                    Label value = new Label(field.value());
                    value.setWrapText(true);
                    fields.addRow(row, name, value);
                });
            }
        }));
    }

    static String message(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static TableColumn<Submission, String> column(String title, double width,
                                                          Function<Submission, String> text) {
        TableColumn<Submission, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /** What the pull request changes — red the moment it is not one catalog file. */
    private static TableColumn<Submission, String> shapeColumn() {
        TableColumn<Submission, String> col = column("Changes", 260, Submission::shape);
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

    /** The check's conclusion, in its own words, coloured by what it means. */
    private static TableColumn<Submission, String> checkColumn() {
        TableColumn<Submission, String> col = column("Check", 200, s -> s.checks().text());
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
}
