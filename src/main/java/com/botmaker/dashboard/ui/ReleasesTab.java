package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.umbrella.Links;
import com.botmaker.dashboard.umbrella.Proc;
import com.botmaker.dashboard.umbrella.ReleaseLog;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The Releases tab: the committed {@code releases/*.md} logs, newest first, and a re-poll.
 *
 * <p>The log is the record and this tab is a reader of it. It parses nothing that {@code release.sh} did not
 * write and asks GitHub nothing directly — <b>re-poll shells to {@code ./release.sh --status <file>}</b>,
 * which rewrites the file in place through the very same {@code resolve_clean_room} the release used. An
 * easier check here (a HEAD on the {@code .pom}) would answer a different question and could turn a broken
 * row green: a published pom naming a dependency nobody can resolve passes a HEAD and fails a real build.
 *
 * <p>Because {@code --status} rewrites the file, a re-poll is a <b>reviewable diff</b> in the umbrella
 * working copy, and committing it is the operator's call. The tab says so rather than committing anything.
 */
public final class ReleasesTab extends BorderPane {

    private final ObservableList<Path> logs = FXCollections.observableArrayList();
    private final ListView<Path> logList = new ListView<>(logs);

    private final ObservableList<ReleaseLog.Row> rows = FXCollections.observableArrayList();
    private final TableView<ReleaseLog.Row> table = new TableView<>(rows);

    private final Label heading = new Label();
    private final Label status = new Label();
    private final TextArea errors = new TextArea();
    private final Button repoll = new Button("Re-poll");

    private Path umbrella;
    private ReleaseLog current;

    public ReleasesTab(Path umbrella) {
        this.umbrella = umbrella;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        repoll.setDisable(true);
        repoll.setOnAction(e -> repoll());

        Button reload = new Button("Reload");
        reload.setOnAction(e -> reload());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, reload, repoll, status, spacer);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        logList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFileName().toString().replace(".md", ""));
            }
        });
        logList.getSelectionModel().selectedItemProperty()
                .addListener((obs, was, now) -> show(now));

        buildColumns();
        table.setPlaceholder(new Label("Pick a release on the left."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setContextMenu(rowMenu());

        errors.setEditable(false);
        errors.getStyleClass().add("error-text");
        errors.setPrefRowCount(8);
        errors.setWrapText(false);

        VBox right = new VBox(8, heading, table, errors);
        right.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);

        SplitPane split = new SplitPane(logList, right);
        split.setDividerPositions(0.22);

        setTop(bar);
        setCenter(split);

        reload();
    }

    /** Called when the operator picks a different checkout. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        reload();
    }

    /** Re-lists the logs on disk and selects the newest — the one a release just wrote. */
    public void reload() {
        if (umbrella == null) {
            logs.clear();
            rows.clear();
            status.setText("No umbrella checkout chosen — pick one in the top bar.");
            return;
        }
        List<Path> found = ReleaseLog.list(umbrella);
        logs.setAll(found);
        status.setText(found.size() + " release logs in " + umbrella.resolve("releases"));
        if (!found.isEmpty()) {
            logList.getSelectionModel().select(0);
        } else {
            rows.clear();
            heading.setText("");
            errors.clear();
        }
    }

    private void show(Path file) {
        if (file == null) {
            return;
        }
        current = ReleaseLog.read(file);
        rows.setAll(current.rows());
        heading.setText("Release " + current.stamp()
                + (current.broken() ? "  —  something is broken" : ""));
        errors.setText(current.problems().isEmpty()
                ? ""
                : String.join("\n\n", current.problems().stream()
                        .map(p -> p.module() + " — " + p.kind() + "\n" + p.text())
                        .toList()));
        errors.setVisible(!current.problems().isEmpty());
        errors.setManaged(!current.problems().isEmpty());
        repoll.setDisable(false);
    }

    /**
     * Runs {@code ./release.sh --status <file>} and re-reads the file it rewrote.
     *
     * <p>Off the FX thread and slow by nature: it resolves every module's artifacts into a throwaway local
     * repository and calls {@code gh} once per module. Minutes, not seconds.
     */
    private void repoll() {
        if (current == null || umbrella == null) {
            return;
        }
        Path file = current.file();
        Path root = umbrella;
        repoll.setDisable(true);
        status.setText("./release.sh --status " + file.getFileName() + " — resolving artifacts and polling Actions…");
        CompletableFuture
                .supplyAsync(() -> ReleaseLog.repoll(root, file))
                .whenComplete((proc, error) -> Platform.runLater(() -> {
                    repoll.setDisable(false);
                    if (error != null) {
                        status.setText("Re-poll failed: " + error.getMessage());
                        return;
                    }
                    show(file);
                    status.setText(proc.ok()
                            ? "Re-polled. The log was rewritten in place — commit it as a diff."
                            : "release.sh --status exited " + proc.exit() + ": " + proc.firstLine());
                }));
    }

    /**
     * The three places a release can be looked at.
     *
     * <p>A context menu rather than three columns of links: the row's own verdict is the answer most of the
     * time, and the pages are what you open on the one row that says otherwise.
     */
    private ContextMenu rowMenu() {
        MenuItem release = new MenuItem("Open the GitHub Release");
        release.setOnAction(e -> withSelected(r -> Links.release(r.module(), r.tag())));
        MenuItem jitpack = new MenuItem("Open the JitPack build");
        jitpack.setOnAction(e -> withSelected(r -> Links.jitpack(r.module(), r.tag())));
        MenuItem actions = new MenuItem("Open the Actions runs for this tag");
        actions.setOnAction(e -> withSelected(r -> Links.actions(r.module(), r.tag())));
        return new ContextMenu(release, jitpack, actions);
    }

    private void withSelected(Function<ReleaseLog.Row, String> url) {
        ReleaseLog.Row row = table.getSelectionModel().getSelectedItem();
        if (row != null) {
            Browse.open(url.apply(row));
        }
    }

    private void buildColumns() {
        table.getColumns().setAll(
                column("Module", 200, ReleaseLog.Row::module),
                column("Tag", 110, ReleaseLog.Row::tag),
                column("Changelog", 110, ReleaseLog.Row::changelog),
                healthColumn("JitPack", 240, ReleaseLog.Row::jitpack, ReleaseLog.Row::jitpackHealth),
                healthColumn("Actions", 220, ReleaseLog.Row::actions, ReleaseLog.Row::actionsHealth));
    }

    private static TableColumn<ReleaseLog.Row, String> column(String title, double width,
                                                              Function<ReleaseLog.Row, String> text) {
        TableColumn<ReleaseLog.Row, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /** The script's word, coloured by what it means. The word itself is never rewritten. */
    private static TableColumn<ReleaseLog.Row, String> healthColumn(
            String title, double width,
            Function<ReleaseLog.Row, String> text,
            Function<ReleaseLog.Row, ReleaseLog.Health> health) {
        TableColumn<ReleaseLog.Row, String> col = column(title, width, text);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--ok", "cell--pending", "cell--broken", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                getStyleClass().add(switch (health.apply(getTableRow().getItem())) {
                    case OK -> "cell--ok";
                    case PENDING -> "cell--pending";
                    case BROKEN -> "cell--broken";
                    case NA -> "cell--dim";
                });
            }
        });
        return col;
    }
}
