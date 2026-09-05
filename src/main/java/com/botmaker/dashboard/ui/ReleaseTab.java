package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.ReleasePlan;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import com.botmaker.dashboard.umbrella.VersionTargets;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p><b>The one number this tab computes is the one it must not guess.</b> "Would cut" is
 * {@code com.botmaker.cli.release} — {@code release.sh}'s own {@code latest_version} and
 * {@code resolve_version}, ported into {@code botmaker-cli}'s library artifact — applied to that module's
 * newest tag. That is not a weakening of the rule above but the strict form of it: a level is meaningless
 * until it is resolved, and the alternative to calling the owner is either a second implementation or an
 * operator picking {@code minor} without being told what {@code minor} means for that module today.
 *
 * <p><b>The module rows come from the script's own decide pass</b> ({@link ReleasePlan#verdicts()}), which is
 * why they are empty until the first preview runs. This module keeps no list of which modules are releasable
 * — {@code botmaker-gallery}, {@code botmaker-plugin-registry} and this repository are not, and the way to
 * know that is that the pass never names them.
 */
public final class ReleaseTab extends BorderPane {

    /**
     * One module's line: whether it is asked for, at what level or exact version, and what that resolves to.
     *
     * <p><b>A level and a typed version are two states, not one box.</b> They were one free-text field until
     * 2026-09-05, which made the commonest choice — <i>patch</i> — a word to spell correctly, and made the
     * rarest one, an exact version, look identical to it. The level is now always set (the script's own
     * default, since a bare module flag means {@code patch}) and {@link #exact} only matters while
     * {@link #exactChosen} is on, so switching back and forth never loses what was typed.
     *
     * <p>{@link #latest} is {@code null} until this module's tags have been read, which is a git call and so
     * happens off the FX thread. Three states — unread, no tag, a tag — and they are three different things
     * to show.
     */
    public static final class Row {
        private final String module;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final ObjectProperty<Level> level = new SimpleObjectProperty<>(Level.DEFAULT);
        private final BooleanProperty exactChosen = new SimpleBooleanProperty(false);
        private final StringProperty exact = new SimpleStringProperty("");
        private final StringProperty target = new SimpleStringProperty(VersionTargets.READING);
        private Optional<Version> latest;
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

        public StringProperty targetProperty() {
            return target;
        }

        public String getVerdict() {
            return verdict;
        }

        /** What goes on the command line — a level word, or the exact version as typed. */
        String spec() {
            return exactChosen.get() ? exact.get().strip() : level.get().spelling();
        }

        /** A level is always well formed; only what somebody types can fail to be a version. */
        boolean specWellFormed() {
            return !exactChosen.get() || ReleaseSpec.wellFormed(exact.get());
        }

        /**
         * Recomputes the arrow. Pure once the tags are read, so every keystroke and every level click can
         * call it — {@link VersionTargets#latest} is the only part that touches git, and it is cached here.
         */
        void retarget() {
            if (latest == null) {
                target.set(VersionTargets.releasable(module)
                        ? VersionTargets.READING
                        : VersionTargets.UNKNOWN_MODULE);
            } else if (exactChosen.get()) {
                target.set(VersionTargets.forExact(module, exact.get(), latest));
            } else {
                target.set(VersionTargets.forLevel(module, level.get(), latest));
            }
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

        Label hint = new Label("Pick a level, or x.y.z to type one. \"Would cut\" is what that resolves to "
                + "off the module's own latest tag — computed by com.botmaker.cli.release, the same code the "
                + "release runs. An explicit module beats --all: release.sh's rule, and it decides.");
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

        TableColumn<Row, Row> spec = new TableColumn<>("Version / level");
        spec.setPrefWidth(250);
        spec.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        spec.setCellFactory(c -> new SpecCell());

        TableColumn<Row, String> target = new TableColumn<>("Would cut");
        target.setPrefWidth(150);
        target.setCellValueFactory(c -> c.getValue().targetProperty());

        TableColumn<Row, String> verdict = new TableColumn<>("Last preview said");
        verdict.setPrefWidth(220);
        verdict.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getVerdict()));

        table.getColumns().setAll(pick, module, spec, target, verdict);
    }

    /**
     * The level picker: three segments and a fourth for a typed version.
     *
     * <p><b>The typed box is marked rather than refusing the keystroke</b>, which is the same rule the free
     * text field had: a half-typed {@code 1.2} is an ordinary state on the way to {@code 1.2.0}, and a box
     * that fought the operator mid-word would be worse than one that waits. Preview stays disabled while any
     * row is marked, so the script is never asked a question it will only answer with
     * {@code bad version/level}.
     *
     * <p>A toggle group can be cleared by clicking the selected button, and here that would mean a module
     * asked for at no level at all — so a null selection is put back. The script has no such state:
     * {@code --cli} with nothing after it is {@code --cli patch}.
     */
    private final class SpecCell extends TableCell<Row, Row> {

        private final ToggleGroup group = new ToggleGroup();
        private final ToggleButton patch = segment("patch");
        private final ToggleButton minor = segment("minor");
        private final ToggleButton major = segment("major");
        private final ToggleButton exactly = segment("x.y.z");
        private final TextField typed = new TextField();
        private final HBox box;

        private Row row;
        /** Set while the cell is being filled from a row, so writing the controls does not write back. */
        private boolean filling;

        SpecCell() {
            typed.setPrefColumnCount(6);
            typed.setPromptText("1.2.0");
            typed.getStyleClass().add("exact-version");

            HBox segments = new HBox(patch, minor, major, exactly);
            segments.getStyleClass().add("segmented");
            box = new HBox(8, segments, typed);
            box.setAlignment(Pos.CENTER_LEFT);

            group.selectedToggleProperty().addListener((o, was, is) -> {
                if (is == null) {
                    group.selectToggle(was);
                    return;
                }
                apply();
            });
            typed.textProperty().addListener((o, was, is) -> apply());
        }

        private ToggleButton segment(String text) {
            ToggleButton button = new ToggleButton(text);
            button.setToggleGroup(group);
            button.getStyleClass().add("segment");
            return button;
        }

        private void apply() {
            if (filling || row == null) {
                return;
            }
            boolean exact = group.getSelectedToggle() == exactly;
            row.exactChosen.set(exact);
            if (exact) {
                row.exact.set(typed.getText());
            } else if (group.getSelectedToggle() == minor) {
                row.level.set(Level.MINOR);
            } else if (group.getSelectedToggle() == major) {
                row.level.set(Level.MAJOR);
            } else {
                row.level.set(Level.PATCH);
            }
            typed.setDisable(!exact);
            mark();
            row.retarget();
            refreshCommandLine();
        }

        private void mark() {
            typed.getStyleClass().remove("cell--broken");
            if (row != null && !row.specWellFormed()) {
                typed.getStyleClass().add("cell--broken");
            }
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            row = empty ? null : item;
            if (row == null) {
                setGraphic(null);
                return;
            }
            filling = true;
            group.selectToggle(switch (row.exactChosen.get() ? null : row.level.get()) {
                case MINOR -> minor;
                case MAJOR -> major;
                case PATCH -> patch;
                case null -> exactly;
            });
            typed.setText(row.exact.get());
            typed.setDisable(!row.exactChosen.get());
            filling = false;
            mark();
            setGraphic(box);
        }
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
                picked.put(row.getModule(), row.spec());
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
                // The command line is what this tab hands back, so it has to follow every tick rather than
                // being rebuilt only when a preview runs. The level and the typed version are followed by
                // SpecCell, which is where they are changed.
                row.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
                row.retarget();
            }
            row.verdict = verdict.text();
            rebuilt.add(row);
        });
        rows.setAll(rebuilt);
        loadLatest();
    }

    /**
     * Reads each module's newest tag in the background and fills in the arrows as they arrive.
     *
     * <p>One row at a time through {@link Platform#runLater}, rather than one batch at the end: each is a
     * {@code git fetch --tags} against a remote, so the last module can be seconds behind the first and a
     * table that stayed at {@code …} until all ten had answered would read as stuck.
     *
     * <p>Re-read on every preview, deliberately. A tag cut elsewhere between two previews changes every
     * arrow under it, and a cached answer here would be this window quietly showing a version that is
     * already taken.
     */
    private void loadLatest() {
        Path root = umbrella;
        if (root == null) {
            return;
        }
        List<Row> reading = List.copyOf(rows);
        CompletableFuture.runAsync(() -> {
            for (Row row : reading) {
                Optional<Version> latest = VersionTargets.latest(root, row.getModule());
                Platform.runLater(() -> {
                    row.latest = latest;
                    row.retarget();
                });
            }
        });
    }

    private void say(String text) {
        status.setText(text);
    }
}
