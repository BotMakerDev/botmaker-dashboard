package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.umbrella.ModuleRow;
import com.botmaker.dashboard.umbrella.ModuleScan;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * The Modules tab: one row per module in the checkout, answering "what is out of date" before a release.
 *
 * <p><b>Every column is either a fact of the checkout or the script's own sentence.</b> The <i>plan</i>
 * column in particular is {@code release.sh --all --dry-run}'s decide pass quoted verbatim — including the
 * difference between "no changes since its latest tag" and "only docs since v… (the artifact would be
 * identical)", which is a distinction this window would lose the moment it tried to summarise it.
 *
 * <p>Nothing here writes. The tab exists precisely because it needs no writes: it is the one worth building
 * first, and it is complete as a read.
 */
public final class ModulesTab extends BorderPane {

    private final ObservableList<ModuleRow> rows = FXCollections.observableArrayList();
    private final TableView<ModuleRow> table = new TableView<>(rows);
    private final Label status = new Label();
    private final Button refresh = new Button("Refresh");

    private Path umbrella;

    public ModulesTab(Path umbrella) {
        this.umbrella = umbrella;

        status.getStyleClass().add("status-line");
        refresh.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, status, spacer);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        buildColumns();
        table.setPlaceholder(new Label("Nothing scanned yet."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        setTop(bar);
        setCenter(table);

        if (umbrella != null) {
            refresh();
        } else {
            status.setText("No umbrella checkout chosen — pick one in the top bar.");
        }
    }

    /** Called when the operator picks a different checkout; re-scans immediately, since the rows are stale. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        refresh();
    }

    /**
     * Re-reads git and re-asks {@code release.sh}.
     *
     * <p>Off the FX thread, and the button is disabled meanwhile: the decide pass shells to git in ten
     * repositories and runs the SDK's pointer test through Maven, which is seconds at best and a minute when
     * Maven has to resolve. A window that froze for that would look broken.
     */
    public void refresh() {
        if (umbrella == null) {
            rows.clear();
            status.setText("No umbrella checkout chosen — pick one in the top bar.");
            return;
        }
        Path root = umbrella;
        refresh.setDisable(true);
        status.setText("Reading " + root + " and running the decide pass…");
        CompletableFuture
                .supplyAsync(() -> ModuleScan.scan(root))
                .whenComplete((scan, error) -> Platform.runLater(() -> {
                    refresh.setDisable(false);
                    if (error != null) {
                        rows.clear();
                        status.setText("Scan failed: " + error.getMessage());
                        return;
                    }
                    rows.setAll(scan.rows());
                    status.setText(summary(scan));
                    showOutput(scan.output());
                }));
    }

    /**
     * One line about the scan as a whole.
     *
     * <p>A refusal is reported rather than hidden, and every row is still shown: the git half of the scan is
     * complete whatever the decide pass said, so a checkout the pass cannot reason about still lists its
     * tags, its dirty modules and its stale pins. Hovering the line shows what the pass printed — this app's
     * whole job is telling somebody what is wrong.
     *
     * <p>The gates are not run here at all, which is the one thing this tab now says less than it did. They
     * belong to a release rather than to a scan, they cost Maven, and the Release tab runs them on demand.
     */
    private String summary(ModuleScan.Scan scan) {
        long releasing = scan.rows().stream().filter(ModuleRow::releasing).count();
        String head = scan.rows().size() + " modules · " + releasing + " would release";
        return scan.decided()
                ? head
                : head + " · the decide pass refused: " + scan.error().orElse("no reason given")
                        + " — hover for its output";
    }

    private void buildColumns() {
        table.getColumns().setAll(
                column("Module", 200, ModuleRow::name),
                column("Latest tag", 140, ModuleRow::tagLabel),
                column("Working tree", 110, r -> r.dirty() ? "dirty" : "clean"),
                planColumn(),
                column("Changelog", 130, r -> r.changelog().label()),
                pinsColumn());
    }

    /**
     * Hangs the script's whole output off the status line.
     *
     * <p>A tooltip rather than a panel because it is the second question, not the first: the summary says
     * whether a gate refused, and the output says which one. Long enough to actually read — the default two
     * seconds would hide the one paragraph that matters.
     */
    private void showOutput(String output) {
        if (output.isBlank()) {
            status.setTooltip(null);
            return;
        }
        Tooltip tip = new Tooltip(output.strip());
        tip.setShowDuration(Duration.minutes(5));
        tip.setWrapText(true);
        tip.setMaxWidth(900);
        status.setTooltip(tip);
    }

    private static TableColumn<ModuleRow, String> column(String title, double width,
                                                         Function<ModuleRow, String> text) {
        TableColumn<ModuleRow, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /** The script's own sentence, coloured only by whether it decided to release. */
    private static TableColumn<ModuleRow, String> planColumn() {
        TableColumn<ModuleRow, String> col = column("release.sh --dry-run", 300, ModuleRow::planLabel);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--releasing", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                getStyleClass().add(getTableRow().getItem().releasing() ? "cell--releasing" : "cell--dim");
            }
        });
        return col;
    }

    /** The pins, marked when one is not its upstream's newest tag. */
    private static TableColumn<ModuleRow, String> pinsColumn() {
        TableColumn<ModuleRow, String> col = column(".deps.env pins", 380, ModuleRow::pinsLabel);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setTooltip(empty || item == null ? null : new Tooltip(item));
                getStyleClass().removeAll("cell--stale", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                getStyleClass().add(getTableRow().getItem().anyStalePin() ? "cell--stale" : "cell--dim");
            }
        });
        return col;
    }
}
