package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.umbrella.ReleasePlan;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.converter.DefaultStringConverter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Release tab: what {@code release.sh} would do for a given set of flags, and the line to type to make
 * it do it.
 *
 * <p><b>There is no execute button, and its absence is the design rather than an omission.</b> A tag is
 * permanent and no exit code recalls one, so the terminal stays the only place a release is cut until Part C
 * of the plan makes the decision typed code that this window and the script's other two callers share.
 * {@link ReleaseSpec#command()} appends {@code --dry-run} unconditionally, so the rule is enforced by the
 * only class that can build the command rather than remembered at each button.
 *
 * <p><b>What it hands back is the command line.</b> The preview underneath is that exact line's own output —
 * the decided version per module, the skips and their reasons, the forcing, the tag order and the gate
 * verdicts, none of which is re-rendered here. A parsed table would show strictly less than the script
 * already prints, and the two would have to be kept in step.
 *
 * <p><b>The module rows come from the script's own decide pass</b> ({@link ReleasePlan#verdicts()}), which is
 * why they are empty until the first preview runs. This module keeps no list of which modules are releasable
 * — {@code botmaker-gallery}, {@code botmaker-plugin-registry} and this repository are not, and the way to
 * know that is that the pass never names them.
 */
public final class ReleaseTab extends BorderPane {

    /** One module's line in the form: whether it is asked for, and at what version or level. */
    public static final class Row {
        private final String module;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final StringProperty spec = new SimpleStringProperty("");
        private String verdict = "";

        Row(String module) {
            this.module = module;
        }

        public String getModule() {
            return module;
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        public StringProperty specProperty() {
            return spec;
        }

        public String getVerdict() {
            return verdict;
        }

        boolean specWellFormed() {
            return ReleaseSpec.wellFormed(spec.get());
        }
    }

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    private final CheckBox allBox = new CheckBox("--all");
    private final ComboBox<String> allLevel = new ComboBox<>(
            FXCollections.observableArrayList("patch", "minor", "major"));
    private final CheckBox forceBox = new CheckBox("--force");
    private final CheckBox noWaitBox = new CheckBox("--no-wait-jitpack");

    private final Button preview = new Button("Preview");
    private final Label status = new Label();
    private final TextField commandLine = new TextField();
    private final TextArea output = new TextArea();

    private Path umbrella;

    public ReleaseTab(Path umbrella) {
        this.umbrella = umbrella;

        status.getStyleClass().add("status-line");
        preview.setOnAction(e -> preview());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, preview, status, spacer);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        SplitPane split = new SplitPane(flags(), result());
        split.setDividerPositions(0.38);
        setTop(bar);
        setCenter(split);

        // --all on by default: it is the flag set that answers "what would a release do right now", and it
        // is also the only one that lists every module the script releases, which is what fills the table.
        allBox.setSelected(true);
        allLevel.setValue("patch");
        allLevel.disableProperty().bind(allBox.selectedProperty().not());

        say(umbrella == null
                ? "No umbrella checkout chosen — pick one in the top bar."
                : "Press Preview. Nothing here can push a tag: every run is --dry-run.");
        refreshCommandLine();
    }

    /** Called when the operator picks a different checkout. The rows are another checkout's; they go. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        rows.clear();
        output.clear();
        say("Press Preview to read " + umbrella + ".");
        refreshCommandLine();
    }

    private VBox flags() {
        allBox.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
        allLevel.valueProperty().addListener((o, was, is) -> refreshCommandLine());
        forceBox.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
        noWaitBox.selectedProperty().addListener((o, was, is) -> refreshCommandLine());

        HBox all = new HBox(8, allBox, allLevel);
        VBox options = new VBox(6, all, forceBox, noWaitBox);
        options.getStyleClass().add("flag-box");

        buildColumns();
        table.setEditable(true);
        table.setPlaceholder(new Label("Preview once to list the modules release.sh releases."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        Label hint = new Label("A module's box is blank for a patch bump, or a level, or an exact x.y.z. "
                + "An explicit module beats --all — release.sh's rule, and it decides.");
        hint.getStyleClass().add("placeholder-body");
        hint.setWrapText(true);

        VBox box = new VBox(10, options, table, hint);
        box.setPadding(new Insets(12));
        return box;
    }

    private VBox result() {
        commandLine.setEditable(false);
        commandLine.getStyleClass().add("command-line");

        Label what = new Label("Run this in a terminal to cut it for real — without --dry-run.");
        what.getStyleClass().add("placeholder-body");
        what.setWrapText(true);

        output.setEditable(false);
        output.getStyleClass().add("output-text");
        output.setPromptText("release.sh's own output, whole.");
        VBox.setVgrow(output, Priority.ALWAYS);

        VBox box = new VBox(8, commandLine, what, output);
        box.setPadding(new Insets(12));
        return box;
    }

    private void buildColumns() {
        TableColumn<Row, Boolean> pick = new TableColumn<>("");
        pick.setPrefWidth(40);
        pick.setCellValueFactory(c -> c.getValue().selectedProperty());
        pick.setCellFactory(CheckBoxTableCell.forTableColumn(pick));
        pick.setEditable(true);

        TableColumn<Row, String> module = new TableColumn<>("Module");
        module.setPrefWidth(190);
        module.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getModule()));

        TableColumn<Row, String> spec = new TableColumn<>("Version / level");
        spec.setPrefWidth(120);
        spec.setCellValueFactory(c -> c.getValue().specProperty());
        spec.setCellFactory(c -> specCell());
        spec.setEditable(true);

        TableColumn<Row, String> verdict = new TableColumn<>("Last preview said");
        verdict.setPrefWidth(220);
        verdict.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getVerdict()));

        table.getColumns().setAll(pick, module, spec, verdict);
    }

    /**
     * The version box, marked when what is typed is not something {@code resolve_version} accepts.
     *
     * <p>Marking rather than refusing the keystroke: a half-typed {@code 1.2} is an ordinary state on the way
     * to {@code 1.2.0}, and a box that fought the operator mid-word would be worse than one that waits.
     * Preview is disabled while any row is marked, so the script is never asked a question it will only
     * answer with {@code bad version/level}.
     */
    private TableCell<Row, String> specCell() {
        return new TextFieldTableCell<>(new DefaultStringConverter()) {
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("cell--broken");
                if (!empty && item != null && !ReleaseSpec.wellFormed(item)) {
                    getStyleClass().add("cell--broken");
                }
            }
        };
    }

    /** What the flags currently spell, shown whether or not anything has been previewed yet. */
    private void refreshCommandLine() {
        ReleaseSpec spec = spec();
        commandLine.setText(spec.empty() ? "" : spec.commandLine());
        preview.setDisable(umbrella == null || spec.empty() || !allSpecsWellFormed());
    }

    private boolean allSpecsWellFormed() {
        return rows.stream().allMatch(Row::specWellFormed);
    }

    private ReleaseSpec spec() {
        Map<String, String> picked = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.selectedProperty().get()) {
                picked.put(row.getModule(), row.specProperty().get());
            }
        }
        Optional<String> all = allBox.isSelected()
                ? Optional.of(allLevel.getValue() == null ? "" : allLevel.getValue())
                : Optional.empty();
        return new ReleaseSpec(all, picked, forceBox.isSelected(), noWaitBox.isSelected());
    }

    /**
     * Runs the preview off the FX thread.
     *
     * <p>Minutes, not seconds: the decide pass shells to git in ten repositories and runs the SDK's pointer
     * test through Maven. The button is disabled meanwhile and the status line says what is running, because
     * a window that simply froze for that would read as broken.
     */
    private void preview() {
        if (umbrella == null) {
            return;
        }
        Path root = umbrella;
        ReleaseSpec spec = spec();
        preview.setDisable(true);
        say("Running " + spec.commandLine() + " …");
        CompletableFuture
                .supplyAsync(() -> spec.preview(root))
                .whenComplete((plan, error) -> Platform.runLater(() -> {
                    preview.setDisable(false);
                    if (error != null) {
                        say("Preview failed: " + error.getMessage());
                        return;
                    }
                    show(plan);
                }));
    }

    /**
     * Puts the run's whole output on screen and folds its verdicts back into the rows.
     *
     * <p>A non-zero exit is reported and the verdicts are still shown, because the gates run <i>after</i> the
     * decide pass: "the plan is complete and a gate then refused it" is the ordinary shape of a dry run over
     * a constellation that is not release-ready, and blanking the tab would hide the plan the operator asked
     * for along with the reason it was refused.
     */
    private void show(ReleasePlan plan) {
        output.setText(plan.raw());
        mergeRows(plan);
        refreshCommandLine();

        long releasing = plan.verdicts().values().stream().filter(ReleasePlan.Verdict::releasing).count();
        if (!plan.decided()) {
            say("release.sh printed no plan (exit " + plan.exit() + ") — its output is on the right.");
        } else if (plan.exit() != 0) {
            say(releasing + " of " + plan.verdicts().size() + " would release · exited " + plan.exit()
                    + " on a gate after deciding.");
        } else {
            say(releasing + " of " + plan.verdicts().size() + " would release.");
        }
    }

    /**
     * Rebuilds the rows from what the pass named, keeping whatever the operator had already ticked or typed.
     *
     * <p>A module the pass stops naming is dropped rather than kept with a stale verdict — the list is the
     * script's answer to <i>what is releasable</i>, and holding a row it no longer names would be this module
     * keeping the list after all.
     */
    private void mergeRows(ReleasePlan plan) {
        Map<String, Row> existing = new LinkedHashMap<>();
        rows.forEach(r -> existing.put(r.getModule(), r));

        var rebuilt = FXCollections.<Row>observableArrayList();
        plan.verdicts().forEach((module, verdict) -> {
            Row row = existing.get(module);
            if (row == null) {
                row = new Row(module);
                // The command line is what this tab hands back, so it has to follow every tick and every
                // keystroke rather than being rebuilt only when a preview runs.
                row.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
                row.specProperty().addListener((o, was, is) -> refreshCommandLine());
            }
            row.verdict = verdict.text();
            rebuilt.add(row);
        });
        rows.setAll(rebuilt);
    }

    private void say(String text) {
        status.setText(text);
    }
}
