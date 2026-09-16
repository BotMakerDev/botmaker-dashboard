package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.ReleaseRun;
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
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
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
 * The Release tab: what a release would do for a given set of flags, and — since 2026-09-16 — the button
 * that makes it do it.
 *
 * <p><b>There was no execute button until then, and its arrival is not a relaxation of the rule.</b> The
 * rule is that the decisions have exactly one implementation; the old shape kept that by shelling to
 * {@code release.sh} with {@code --dry-run} welded on, which kept the decisions in one place by keeping
 * them out of reach. Both buttons now call {@link ReleaseRun#go}, which calls
 * {@code com.botmaker.cli.release.Release} — the same library {@code botmaker release} and the release
 * workflow call. <b>Preview and Execute differ by one argument</b>, the {@code Runner}, which is exactly
 * the property that makes a preview worth trusting: the text on screen was produced by the code that will
 * do the work.
 *
 * <p><b>What guards it is arming, not a dialog alone.</b> Execute is dead until a preview has run <i>in this
 * session, with these exact flags</i> and returned no refusal; changing any flag disarms it, because the
 * plan on screen then describes a release nobody previewed. Then a confirmation that lists every module and
 * version about to be tagged and will not enable its own button until the word is typed. A tag is permanent
 * and no exit code recalls one — every guard here is about the gap between what was read and what is run.
 *
 * <p><b>The output pane is the run, streamed.</b> Not a parsed table: the decided version per module, the
 * skips and their reasons, the forcing edges, the tag order, the gate verdicts and — during a real run —
 * each command as it goes. A table would show strictly less than the library already prints, and the two
 * would have to be kept in step.
 *
 * <p><b>The one number this tab computes is the one it must not guess.</b> "Would cut" is
 * {@code com.botmaker.cli.release}'s own {@code latest_version} and {@code resolve_version} applied to that
 * module's newest tag — a level is meaningless until it is resolved, and the alternative to calling the
 * owner is either a second implementation or an operator picking {@code minor} without being told what
 * {@code minor} means for that module today.
 *
 * <p><b>The module rows come from the decide pass itself</b> ({@link Plan#decisions()}), which is why they
 * are empty until the first preview runs. This module keeps no list of which modules are releasable —
 * {@code botmaker-gallery}, {@code botmaker-plugin-registry} and this repository are not, and the way to
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

    /**
     * What has to be typed before the confirmation's own button works.
     *
     * <p>Lowercase and unremarkable on purpose: the barrier is having to read the list and type at all, not
     * having to shout. Compare the Catalog tab's Unpublish, which asks for the entry id — there the word
     * names the one thing being removed, and here the one thing is the release itself.
     */
    private static final String CONFIRM_WORD = "release";

    /** The confirmation's affirmative, named for what it does rather than "OK". */
    private static final ButtonType CUT = new ButtonType("Cut the release", ButtonBar.ButtonData.OK_DONE);

    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    private final CheckBox allBox = new CheckBox("--all");
    private final ComboBox<String> allLevel = new ComboBox<>(
            FXCollections.observableArrayList("patch", "minor", "major"));
    private final CheckBox forceBox = new CheckBox("--force");
    private final CheckBox noWaitBox = new CheckBox("--no-wait-jitpack");

    private final Button preview = new Button("Preview");
    private final Button execute = new Button("Execute…");
    private final Label status = new Label();
    private final TextField commandLine = new TextField();
    private final TextArea output = new TextArea();

    private Path umbrella;

    /**
     * The flags of the last preview that returned no refusal, or {@code null}.
     *
     * <p><b>This is the arming, and it is a value comparison rather than a flag.</b> A boolean would stay
     * true after the operator ticked another module, which is precisely the case worth refusing: the plan on
     * screen would then describe a release nobody previewed. {@link ReleaseSpec} is a record, so
     * {@code equals} answers "the same flags" without anything here deciding what same means.
     */
    private ReleaseSpec armed;

    /** The plan that arming was granted for — what the confirmation lists, so it cannot list a newer one. */
    private Plan armedPlan;

    public ReleaseTab(Path umbrella) {
        this.umbrella = umbrella;

        status.getStyleClass().add("status-line");
        preview.setOnAction(e -> run(false));
        execute.getStyleClass().add("danger");
        execute.setDisable(true);
        execute.setOnAction(e -> confirmThenExecute());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, preview, execute, status, spacer);
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
                : "Press Preview. Execute stays dead until a preview of these exact flags comes back clean.");
        refreshCommandLine();
    }

    /** Called when the operator picks a different checkout. The rows are another checkout's; they go. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        rows.clear();
        output.clear();
        // Arming is about one checkout as much as about one set of flags: the same flags decide different
        // versions in a checkout whose tags are somewhere else.
        disarm();
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

        Label what = new Label("The same run, from a terminal — add --execute to cut it there instead. "
                + "Both reach com.botmaker.cli.release; Execute above differs only in its Runner.");
        what.getStyleClass().add("placeholder-body");
        what.setWrapText(true);

        output.setEditable(false);
        output.getStyleClass().add("output-text");
        output.setPromptText("The run's own output, whole, as it is produced.");
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

    /**
     * What the flags currently spell, and what that does to the two buttons.
     *
     * <p>Called from every control, which is what makes arming safe: the instant a tick or a keystroke
     * makes the spec differ from the armed one, Execute goes dead again.
     */
    private void refreshCommandLine() {
        ReleaseSpec spec = spec();
        commandLine.setText(spec.empty() ? "" : spec.commandLine());
        boolean runnable = umbrella != null && !spec.empty() && allSpecsWellFormed();
        preview.setDisable(!runnable);
        execute.setDisable(!runnable || !spec.equals(armed));
    }

    /** Forgets the arming. Every path that changes what a release would do calls it. */
    private void disarm() {
        armed = null;
        armedPlan = null;
        execute.setDisable(true);
    }

    private boolean allSpecsWellFormed() {
        return rows.stream().allMatch(Row::specWellFormed);
    }

    private ReleaseSpec spec() {
        Map<Module, String> picked = new LinkedHashMap<>();
        for (Row row : rows) {
            // A row the decide pass named is a module the library knows, so byDirectory always answers here
            // — and where it would not, dropping the row is right: nothing releases a directory with no flag.
            if (row.selectedProperty().get()) {
                Module.byDirectory(row.getModule()).ifPresent(module -> picked.put(module, row.spec()));
            }
        }
        Optional<String> all = allBox.isSelected()
                ? Optional.of(allLevel.getValue() == null ? "" : allLevel.getValue())
                : Optional.empty();
        return new ReleaseSpec(all, picked, forceBox.isSelected(), noWaitBox.isSelected());
    }

    /**
     * The confirmation, and then the release.
     *
     * <p><b>It lists {@link #armedPlan}, not a plan computed now.</b> Listing a fresh one would let the
     * dialog describe something the operator has not read, which is the entire failure this gate exists to
     * prevent — and the flags cannot have changed, because that disarms the button that opened it.
     *
     * <p>The word has to be typed rather than a button pressed, for the reason Unpublish asks for an entry
     * id: a dialog that is one click from done is a dialog people dismiss. What it costs is a few seconds;
     * what it buys is that nobody tags eleven repositories by muscle memory.
     */
    private void confirmThenExecute() {
        if (umbrella == null || armed == null || armedPlan == null) {
            return;
        }
        List<String> tags = armedPlan.releasing().entrySet().stream()
                .map(cut -> "    " + cut.getKey().directory() + "  " + cut.getValue().tag())
                .toList();
        if (tags.isEmpty()) {
            say("The preview decided to release nothing — there is no tag to cut.");
            return;
        }

        TextArea list = new TextArea(String.join("\n", tags));
        list.setEditable(false);
        list.getStyleClass().add("output-text");
        list.setPrefRowCount(Math.min(12, tags.size() + 1));

        Label warning = new Label(tags.size() + " tag(s) will be pushed, in tag order, and a pushed tag "
                + "cannot be edited or recalled. Each module's CI publishes its GitHub Release from the "
                + "tag, and JitPack caches its build result per tag — a bad one is repaired only by cutting "
                + "another.\n\nType " + CONFIRM_WORD + " to enable the button.");
        warning.setWrapText(true);

        TextField typed = new TextField();
        typed.setPromptText(CONFIRM_WORD);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Cut this release");
        dialog.setHeaderText(armed.executeCommandLine());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, CUT);
        VBox body = new VBox(10, list, warning, typed);
        body.setPadding(new Insets(4));
        dialog.getDialogPane().setContent(body);

        Node cut = dialog.getDialogPane().lookupButton(CUT);
        cut.setDisable(true);
        typed.textProperty().addListener((o, was, is) -> cut.setDisable(!CONFIRM_WORD.equals(is.strip())));

        if (dialog.showAndWait().filter(CUT::equals).isPresent()) {
            run(true);
        }
    }

    /**
     * Runs it off the FX thread, streaming each line into the pane as it arrives.
     *
     * <p>Minutes, not seconds, either way: the decide pass shells to git in eleven repositories, the gates
     * run Maven, and a real run waits on each JitPack build between tags. Both buttons are disabled
     * meanwhile and the status line says what is running, because a window that simply froze for that would
     * read as broken — and during a real release, a window that looked frozen is one somebody force-quits
     * halfway through a tag chain.
     */
    private void run(boolean execute) {
        if (umbrella == null) {
            return;
        }
        Path root = umbrella;
        ReleaseSpec spec = spec();
        preview.setDisable(true);
        this.execute.setDisable(true);
        output.clear();
        say((execute ? "Cutting " : "Running ")
                + (execute ? spec.executeCommandLine() : spec.commandLine()) + " …");

        CompletableFuture
                .supplyAsync(() -> ReleaseRun.go(root, spec, execute,
                        line -> Platform.runLater(() -> output.appendText(line + "\n"))))
                .whenComplete((run, error) -> Platform.runLater(() -> {
                    preview.setDisable(false);
                    if (error != null) {
                        // Not a refusal — ReleaseRun turns those into a value. This is the thread dying.
                        disarm();
                        say((execute ? "The release" : "The preview") + " failed: " + error.getMessage());
                        return;
                    }
                    show(spec, run);
                }));
    }

    /**
     * Puts the run's verdicts back into the rows and decides whether Execute may be armed.
     *
     * <p>A refusal is reported and the plan is still shown, because the gates run <i>after</i> the decide
     * pass: "the plan is complete and a gate then refused it" is the ordinary shape of a preview over a
     * constellation that is not release-ready, and blanking the tab would hide the plan the operator asked
     * for along with the reason it was refused.
     *
     * <p><b>A release never arms anything.</b> Whatever a real run leaves behind — tags cut, a gate refused
     * halfway, a branch unpushed — the next thing to do is look, and the way to get the button back is to
     * preview again against the checkout as it now is.
     */
    private void show(ReleaseSpec spec, ReleaseRun run) {
        run.plan().ifPresent(this::mergeRows);
        disarm();

        if (!run.decided()) {
            say("The decide pass refused: " + run.error().orElse("no reason given"));
        } else if (run.refused()) {
            say(run.refusals().size() + " gate(s) refused — nothing was tagged. Their words are on the "
                    + "right.");
        } else if (run.executed()) {
            say(run.pushesOk()
                    ? "Released. Re-poll the log from the Releases tab in a few minutes."
                    : "Released, but a branch was not pushed — see the lines above. Every tag is out.");
        } else {
            Plan plan = run.plan().orElseThrow();
            armed = spec;
            armedPlan = plan;
            say(plan.releasing().size() + " of " + plan.decisions().size()
                    + " would release · Execute is armed for these flags.");
        }
        refreshCommandLine();
    }

    /**
     * Rebuilds the rows from what the pass named, keeping whatever the operator had already ticked or typed.
     *
     * <p>A module the pass stops naming is dropped rather than kept with a stale verdict — the list is the
     * library's answer to <i>what is releasable</i>, and holding a row it no longer names would be this
     * module keeping the list after all.
     */
    private void mergeRows(Plan plan) {
        Map<String, Row> existing = new LinkedHashMap<>();
        rows.forEach(r -> existing.put(r.getModule(), r));

        var rebuilt = FXCollections.<Row>observableArrayList();
        for (Plan.Decision decision : plan.decisions()) {
            String directory = decision.module().directory();
            Row row = existing.get(directory);
            if (row == null) {
                row = new Row(directory);
                // The command line is what this tab hands back, and a tick changes what a release would do
                // — so it has to follow every tick rather than being rebuilt only when a preview runs. The
                // level and the typed version are followed by SpecCell, which is where they are changed.
                // Not a disarm: arming is a value comparison, so ticking a module takes Execute dead and
                // un-ticking it puts the button back for the plan that was actually read.
                row.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
                row.retarget();
            }
            row.verdict = decision.releasing()
                    ? "releasing v" + decision.version()
                    : decision.verdict().skipReason();
            rebuilt.add(row);
        }
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
