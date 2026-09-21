package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Level;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Order;
import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.ui.widgets.CopyButton;
import com.botmaker.dashboard.ui.widgets.LiveBadge;
import com.botmaker.dashboard.ui.widgets.ReleaseBoard;
import com.botmaker.dashboard.umbrella.ChangelogDrafts;
import com.botmaker.dashboard.umbrella.ClaudeDraft;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
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
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The Release tab: what a release would do for a given set of flags, and the button that makes it do it.
 *
 * <p><b>Preview runs here; Execute starts a process of its own.</b> Both reach {@code com.botmaker.cli.release}
 * through {@link ReleaseRun#go} and differ by one argument, the {@code Runner}, which is what makes a preview
 * worth trusting: the text on screen was produced by the code that will do the work. A preview writes nothing,
 * so it runs in this JVM and a crash costs nothing. A release is started as {@link ReleaseLauncher} →
 * {@code ReleaseJob} since 2026-09-16, because the one cut in this JVM that day died with the window four tags
 * in. The window then only <i>watches</i>: it reads the child's output and the release log, draws them as a
 * {@link ReleaseBoard}, and can be closed and reopened — it reattaches to a job that is still alive.
 *
 * <p><b>What guards it is arming, not a dialog alone.</b> Execute is dead until a preview has run <i>in this
 * session, with these exact flags</i> and returned no refusal; changing any flag disarms it, because the plan
 * on screen then describes a release nobody previewed. Then a confirmation that lists every module and version
 * about to be tagged and will not enable its own button until the word is typed. And it stays dead while a
 * release process is alive. A tag is permanent and no exit code recalls one — every guard here is about the
 * gap between what was read and what is run.
 *
 * <p><b>The rows are every module the library knows, before any preview</b>, in {@link Order#TAG}. They came
 * from the decide pass until 2026-09-16, which left the table empty until the first preview and meant a level
 * could not be picked before one. {@link Module} is the library's list, not a copy of it here.
 *
 * <p><b>With one exception, and it is a placement rather than an omission</b>: a template
 * ({@link Module#template}) has no row here. {@code --gamebot} works identically through all three doors —
 * one implementation is the house rule — but the thing a template is published as is a <i>bot</i>, listed in
 * the Catalog tab beside the vetted and community ones, and that is where its fast update lives. A row here
 * would put an admin-owned template in the middle of the module chain it is not part of.
 *
 * <p><b>Picking a
 * level or typing a version ticks that row</b>: it did not, and a level chosen on an unticked row changed
 * nothing while Execute stayed armed for the global level — which read as the tab remembering only the
 * preview's settings. Unticking resets nothing.
 *
 * <p><b>A refusal is a red banner above the output</b>, one line per gate in the gate's own words. It was one
 * count in the status line, with the reason somewhere in a long plan.
 *
 * <p><b>The one number this tab computes is the one it must not guess.</b> "Would cut" is
 * {@code com.botmaker.cli.release}'s own {@code latest_version} and {@code resolve_version} applied to that
 * module's newest tag, through {@link VersionTargets}.
 */
public final class ReleaseTab extends BorderPane {

    /**
     * What this tab calls outside itself — the library and the process launcher — so a test can hand in a
     * preview that does not shell to git in eleven repositories.
     */
    interface Backend {
        ReleaseRun preview(Path umbrella, ReleaseSpec spec, Consumer<String> line);

        /**
         * Writes and commits an {@code [Unreleased]} section for each of {@code modules} that has none, and
         * answers what happened to each — see {@link ChangelogDrafts}. Nothing by default, which is what a
         * test backend wants: a preview over a fixture is not a preview that commits into it.
         */
        default List<ChangelogDrafts.Result> autoDraft(Path umbrella, List<String> modules,
                                                       Consumer<String> line) {
            return List.of();
        }

        Optional<Version> latest(Path umbrella, String module);

        ReleaseLauncher.Launched launch(Path umbrella, ReleaseSpec spec) throws IOException;

        Optional<ReleaseLauncher.Job> latestJob(Path umbrella);

        Backend REAL = new Backend() {
            @Override
            public ReleaseRun preview(Path umbrella, ReleaseSpec spec, Consumer<String> line) {
                return ReleaseRun.go(umbrella, spec, false, line);
            }

            @Override
            public List<ChangelogDrafts.Result> autoDraft(Path umbrella, List<String> modules,
                                                          Consumer<String> line) {
                List<String> needing = ChangelogDrafts.needing(umbrella, modules);
                if (needing.isEmpty()) {
                    return List.of();
                }
                // Without Claude the copies still land and the drafted ones are reported as left, which
                // is what turns into the refusal below.
                ChangelogDrafts.Drafter drafter = ClaudeDraft.available()
                        ? ClaudeDraft::draft
                        : (where, request, progress) -> new ClaudeDraft.Result("", "",
                                "Claude is not on this machine");
                return ChangelogDrafts.draftAll(umbrella, needing, drafter, line);
            }

            @Override
            public Optional<Version> latest(Path umbrella, String module) {
                return VersionTargets.latest(umbrella, module);
            }

            @Override
            public ReleaseLauncher.Launched launch(Path umbrella, ReleaseSpec spec) throws IOException {
                return ReleaseLauncher.launch(umbrella, spec);
            }

            @Override
            public Optional<ReleaseLauncher.Job> latestJob(Path umbrella) {
                return ReleaseLauncher.latest(umbrella);
            }
        };
    }

    /**
     * One module's line: whether it is asked for, at what level or exact version, and what that resolves to.
     *
     * <p><b>A level and a typed version are two states, not one box.</b> The level is always set (the script's
     * own default, since a bare module flag means {@code patch}) and {@link #exact} only matters while
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
        private final StringProperty verdict = new SimpleStringProperty("");
        private Optional<Version> latest;

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

        public StringProperty verdictProperty() {
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
     * having to shout.
     */
    static final String CONFIRM_WORD = "release";

    /** The confirmation's affirmative, named for what it does rather than "OK". */
    private static final ButtonType CUT = new ButtonType("Cut the release", ButtonBar.ButtonData.OK_DONE);

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final Backend backend;

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
    private final TitledPane outputPane = new TitledPane("Output", output);

    private final VBox banner = new VBox(4);
    private final ReleaseBoard board = new ReleaseBoard(url -> Browse.open(url, this::say));
    private final LiveBadge badge = new LiveBadge();

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

    /** A preview is running in this JVM. */
    private boolean previewing;

    /** The release process being watched, and whether it is still going. */
    private ReleaseLauncher.Job watched;
    private boolean jobRunning;
    private int outputLinesShown;
    private ScheduledFuture<?> watching;
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "release-watcher");
        thread.setDaemon(true);
        return thread;
    });

    public ReleaseTab(Path umbrella) {
        this(umbrella, Backend.REAL);
    }

    ReleaseTab(Path umbrella, Backend backend) {
        this.umbrella = umbrella;
        this.backend = backend;

        status.getStyleClass().add("status-line");
        preview.setOnAction(e -> preview());
        execute.getStyleClass().add("danger");
        execute.setDisable(true);
        execute.setOnAction(e -> confirmThenExecute());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, preview, execute, status, spacer);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        SplitPane split = new SplitPane(flags(), result());
        split.setDividerPositions(0.42);
        setTop(bar);
        setCenter(split);

        // --all on by default: it is the flag set that answers "what would a release do right now".
        allBox.setSelected(true);
        allLevel.setValue("patch");
        allLevel.disableProperty().bind(allBox.selectedProperty().not());

        for (Module module : Order.TAG) {
            if (module.template()) {
                // Released from the Catalog tab, where the thing it is published as is listed. See the
                // class javadoc: this is the one place the rows are not every module the library knows.
                continue;
            }
            Row row = new Row(module.directory());
            // A tick changes what a release would do, so the command line follows every one. Not a disarm:
            // arming is a value comparison, so un-ticking puts the button back for the plan actually read.
            row.selectedProperty().addListener((o, was, is) -> refreshCommandLine());
            row.retarget();
            rows.add(row);
        }

        say(umbrella == null
                ? "No umbrella checkout chosen — pick one in the top bar."
                : "Press Preview. Execute stays dead until a preview of these exact flags comes back clean.");
        refreshCommandLine();
        loadLatest();
        reattach();
    }

    /** Called when the operator picks a different checkout. Verdicts and arrows are another checkout's. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        stopWatching();
        output.clear();
        hideBanner();
        board.setVisible(false);
        board.setManaged(false);
        badge.show(null);
        rows.forEach(row -> {
            row.verdict.set("");
            row.latest = null;
            row.retarget();
        });
        // Arming is about one checkout as much as about one set of flags: the same flags decide different
        // versions in a checkout whose tags are somewhere else.
        disarm();
        say("Press Preview to read " + umbrella + ".");
        refreshCommandLine();
        loadLatest();
        reattach();
    }

    /** The {@code ● Releasing — 4/10} label for this tab's header. */
    public LiveBadge badge() {
        return badge;
    }

    /** Whether the tab can be seen — the board's pulses stop while it cannot. */
    public void setShowing(boolean showing) {
        board.setAnimated(showing);
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
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        Label hint = new Label("Pick a level, or x.y.z to type one — either ticks the row. \"Would cut\" is what "
                + "that resolves to off the module's own latest tag, computed by com.botmaker.cli.release, the "
                + "same code the release runs. An explicit module beats --all.");
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

        banner.getStyleClass().add("refusal-banner");
        hideBanner();

        board.setVisible(false);
        board.setManaged(false);
        VBox.setVgrow(board, Priority.ALWAYS);

        output.setEditable(false);
        output.getStyleClass().add("output-text");
        output.setPromptText("The run's own output, whole, as it is produced.");
        outputPane.getStyleClass().add("output-pane");
        outputPane.setExpanded(true);
        outputPane.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(outputPane, Priority.ALWAYS);
        // A collapsed pane that still grows takes the board's room with an empty strip.
        outputPane.expandedProperty().addListener((o, was, is) ->
                VBox.setVgrow(outputPane, is ? Priority.ALWAYS : Priority.NEVER));

        VBox box = new VBox(8, commandLine, what, banner, board, outputPane);
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
        verdict.setCellValueFactory(c -> c.getValue().verdictProperty());

        table.getColumns().setAll(pick, module, spec, target, verdict);
    }

    /**
     * The level picker: three segments and a fourth for a typed version.
     *
     * <p><b>The typed box is marked rather than refusing the keystroke</b>: a half-typed {@code 1.2} is an
     * ordinary state on the way to {@code 1.2.0}. Preview stays disabled while any row is marked.
     *
     * <p>A toggle group can be cleared by clicking the selected button, and here that would mean a module
     * asked for at no level at all — so a null selection is put back.
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
            button.getStyleClass().addAll("segment", "segment--" + text.replace('.', '-'));
            // A click on the level already chosen changes no toggle, so it would not tick the row through the
            // listener — and "I clicked patch on this row" plainly means this row.
            button.setOnMouseClicked(e -> {
                if (row != null && !filling) {
                    row.selected.set(true);
                }
            });
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
            // Choosing a level for a row is asking for that row. The tick's own listener refreshes the command
            // line; the call below covers a row that was already ticked.
            row.selected.set(true);
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
     * <p>Called from every control, which is what makes arming safe: the instant a tick or a keystroke makes
     * the spec differ from the armed one, Execute goes dead again.
     */
    private void refreshCommandLine() {
        ReleaseSpec spec = spec();
        commandLine.setText(spec.empty() ? "" : spec.commandLine());
        boolean runnable = umbrella != null && !spec.empty() && allSpecsWellFormed();
        boolean busy = previewing || jobRunning;
        preview.setDisable(!runnable || busy);
        execute.setDisable(!runnable || busy || !spec.equals(armed));
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
     * The confirmation, and then the release process.
     *
     * <p><b>It lists {@link #armedPlan}, not a plan computed now.</b> Listing a fresh one would let the dialog
     * describe something the operator has not read — and the flags cannot have changed, because that disarms
     * the button that opened it.
     */
    private void confirmThenExecute() {
        if (umbrella == null || armed == null || armedPlan == null || jobRunning) {
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
                + "another.\n\nThe release runs as a process of its own: closing this window does not stop it, "
                + "and reopening the window shows it again.\n\nType " + CONFIRM_WORD + " to enable the button.");
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
        Themed.dialog(dialog, getScene() == null ? null : getScene().getWindow());

        if (dialog.showAndWait().filter(CUT::equals).isPresent()) {
            launch(armed);
        }
    }

    /**
     * Starts the release process and begins watching it.
     *
     * <p><b>A release never arms anything.</b> Whatever it leaves behind — tags cut, a gate refused halfway, a
     * branch unpushed — the next thing to do is look, and the way to get the button back is to preview again
     * against the checkout as it now is.
     */
    private void launch(ReleaseSpec spec) {
        Path root = umbrella;
        disarm();
        hideBanner();
        try {
            ReleaseLauncher.Launched launched = backend.launch(root, spec);
            say("Started " + launched.how() + ".");
            watch(launched.job());
        } catch (IOException e) {
            say("The release process did not start, and nothing was run: " + e.getMessage());
            refreshCommandLine();
        }
    }

    /** A job that is still alive in this checkout gets watched again — the window was closed mid-release. */
    private void reattach() {
        Path root = umbrella;
        if (root == null) {
            return;
        }
        backend.latestJob(root).filter(ReleaseLauncher.Job::alive).ifPresent(job -> {
            watch(job);
            say("Release in progress, started " + HOUR.format(job.startedAt()) + " — reattached.");
        });
    }

    /**
     * Reads the job once a second, off the FX thread, and redraws from what it read.
     *
     * <p>A poll rather than a {@code WatchService}: two files in two directories, one of them rewritten whole
     * after every module, and the liveness of a process that no file event reports. One read a second of a few
     * hundred kilobytes is nothing beside that, and it is the same code on every platform.
     */
    private void watch(ReleaseLauncher.Job job) {
        stopWatching();
        watched = job;
        jobRunning = true;
        outputLinesShown = 0;
        output.clear();
        outputPane.setExpanded(false);
        board.setVisible(true);
        board.setManaged(true);
        refreshCommandLine();

        watching = watcher.scheduleWithFixedDelay(() -> {
            ReleaseProgress progress = job.progress(Instant.now());
            List<ReleaseProgress.Line> lines = ReleaseProgress.Line.parseAll(job.output());
            Platform.runLater(() -> showJob(job, progress, lines));
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void showJob(ReleaseLauncher.Job job, ReleaseProgress progress, List<ReleaseProgress.Line> lines) {
        if (job != watched) {
            return;
        }
        for (int i = outputLinesShown; i < lines.size(); i++) {
            output.appendText(lines.get(i).text() + "\n");
        }
        outputLinesShown = Math.max(outputLinesShown, lines.size());
        board.show(progress);
        badge.show(progress);

        if (!progress.phase().running()) {
            stopWatching();
            jobRunning = false;
            say(switch (progress.phase()) {
                case DONE -> "Released. Re-poll the log from the Releases tab in a few minutes.";
                case UNPUSHED -> "Released, but a branch was not pushed — see the output. Every tag is out.";
                case REFUSED -> "The release process was refused by a gate — nothing was tagged. See the output.";
                case STOPPED -> "The release stopped. The log and a local 'release (stopped)' commit record what "
                        + "was tagged.";
                default -> "The release process ended without saying it had finished. The output ends where it "
                        + "stopped; each module's tags say what was cut.";
            });
            if (progress.phase() == ReleaseProgress.Phase.REFUSED) {
                showBanner("The release process was refused", List.of("See the output for the gate's words. A "
                        + "gate that passed in the preview and refused here means the tree changed in between."));
            }
            refreshCommandLine();
        }
    }

    private void stopWatching() {
        if (watching != null) {
            watching.cancel(false);
            watching = null;
        }
        watched = null;
        jobRunning = false;
    }

    /**
     * Runs a preview off the FX thread, streaming each line into the output as it arrives.
     *
     * <p>Minutes, not seconds: the decide pass shells to git in eleven repositories and the gates run Maven.
     * Both buttons are disabled meanwhile and the status line says what is running, because a window that
     * simply froze for that would read as broken.
     */
    private void preview() {
        if (umbrella == null || jobRunning) {
            return;
        }
        Path root = umbrella;
        ReleaseSpec spec = spec();
        previewing = true;
        refreshCommandLine();
        output.clear();
        outputPane.setExpanded(true);
        board.setVisible(false);
        board.setManaged(false);
        hideBanner();
        say("Running " + spec.commandLine() + " …");

        // The changelogs first, then the plan: the decide pass reads the committed changelog, so a module
        // being cut with no [Unreleased] section is drafted (or copied forward) and committed before the
        // library is asked — and a module that could not be is a refusal *here*, with its name, rather than
        // the gate's generic one three minutes later.
        List<String> cut = spec.requested().keySet().stream()
                .filter(Module::hasChangelog).map(Module::directory).toList();
        Consumer<String> line = text -> Platform.runLater(() -> output.appendText(text + "\n"));
        CompletableFuture
                .supplyAsync(() -> {
                    List<ChangelogDrafts.Result> drafted = backend.autoDraft(root, cut, line);
                    for (ChangelogDrafts.Result result : drafted) {
                        line.accept("changelog · " + result.module() + ": " + result.outcome().name()
                                .toLowerCase() + " — " + result.message());
                    }
                    List<String> left = drafted.stream().filter(r -> !r.written())
                            .map(r -> r.module() + " — " + r.message()).toList();
                    if (!left.isEmpty()) {
                        return new Previewed(null, left);
                    }
                    return new Previewed(backend.preview(root, spec, line), List.of());
                })
                .whenComplete((previewed, error) -> Platform.runLater(() -> {
                    previewing = false;
                    if (error != null) {
                        // Not a refusal — ReleaseRun turns those into a value. This is the thread dying.
                        disarm();
                        say("The preview failed: " + error.getMessage());
                        refreshCommandLine();
                        return;
                    }
                    if (previewed.run() == null) {
                        disarm();
                        say("preview refused: " + previewed.left().size() + " module(s) need a changelog "
                                + "and none could be written — Execute stays dead.");
                        showBanner("Preview refused — a changelog could not be written", previewed.left());
                        refreshCommandLine();
                        return;
                    }
                    show(spec, previewed.run());
                }));
    }

    /** A preview, or the modules whose changelog stopped it from running. */
    private record Previewed(ReleaseRun run, List<String> left) {
    }

    /**
     * Puts the preview's verdicts into the rows and decides whether Execute may be armed.
     *
     * <p>A refusal is reported and the plan is still shown, because the gates run <i>after</i> the decide pass:
     * "the plan is complete and a gate then refused it" is the ordinary shape of a preview over a constellation
     * that is not release-ready.
     */
    private void show(ReleaseSpec spec, ReleaseRun run) {
        run.plan().ifPresent(this::mergeVerdicts);
        disarm();

        if (!run.decided()) {
            String reason = run.error().orElse("no reason given");
            say("The decide pass refused — nothing would be tagged.");
            showBanner("The decide pass refused", List.of(reason));
        } else if (run.refused()) {
            say(run.refusals().size() + " gate(s) refused — nothing would be tagged.");
            showBanner(run.refusals().size() + " gate(s) refused — Execute stays dead", run.refusals());
        } else {
            Plan plan = run.plan().orElseThrow();
            armed = spec;
            armedPlan = plan;
            say(plan.releasing().size() + " of " + plan.decisions().size()
                    + " would release · Execute is armed for these flags.");
        }
        refreshCommandLine();
        loadLatest();
    }

    /**
     * Writes what the pass said about each module into its row. A module the pass did not name says so: the
     * library's answer to <i>what would this release do</i> did not include it.
     */
    private void mergeVerdicts(Plan plan) {
        Map<String, String> said = new LinkedHashMap<>();
        for (Plan.Decision decision : plan.decisions()) {
            said.put(decision.module().directory(), decision.releasing()
                    ? "releasing v" + decision.version()
                    : decision.verdict().skipReason());
        }
        rows.forEach(row -> row.verdict.set(said.getOrDefault(row.getModule(), "not in this preview")));
    }

    private void showBanner(String title, List<String> lines) {
        banner.getChildren().clear();
        Label heading = new Label(title);
        heading.getStyleClass().add("refusal-title");
        // A refusal is a column of Labels, so there is nothing to select: the one way out of the window
        // was retyping it. The whole banner goes on the clipboard, title included.
        HBox headingRow = new HBox(10, heading,
                new CopyButton("Copy", () -> title + "\n\n" + String.join("\n\n", lines)));
        headingRow.setAlignment(Pos.CENTER_LEFT);
        banner.getChildren().add(headingRow);
        for (String line : lines) {
            Label label = new Label(line);
            label.getStyleClass().add("refusal-line");
            label.setWrapText(true);
            banner.getChildren().add(label);
        }
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void hideBanner() {
        banner.getChildren().clear();
        banner.setVisible(false);
        banner.setManaged(false);
    }

    /**
     * Reads each module's newest tag in the background and fills in the arrows as they arrive.
     *
     * <p>One row at a time, rather than one batch at the end: each is a {@code git fetch --tags} against a
     * remote, so a table that stayed at {@code …} until all eleven had answered would read as stuck. Re-read
     * after every preview, since a tag cut elsewhere in between changes every arrow under it.
     */
    private void loadLatest() {
        Path root = umbrella;
        if (root == null) {
            return;
        }
        List<Row> reading = List.copyOf(rows);
        CompletableFuture.runAsync(() -> {
            for (Row row : reading) {
                Optional<Version> latest = backend.latest(root, row.getModule());
                Platform.runLater(() -> {
                    if (root.equals(umbrella)) {
                        row.latest = latest;
                        row.retarget();
                    }
                });
            }
        });
    }

    private void say(String text) {
        status.setText(text);
    }

    // ---- for tests --------------------------------------------------------------------------------------

    Button previewButton() {
        return preview;
    }

    Button executeButton() {
        return execute;
    }

    VBox banner() {
        return banner;
    }

    List<Row> rows() {
        return rows;
    }

    TableView<Row> table() {
        return table;
    }
}
