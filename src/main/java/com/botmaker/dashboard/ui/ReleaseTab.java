package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Order;
import com.botmaker.cli.release.Plan;
import com.botmaker.dashboard.ui.widgets.CopyButton;
import com.botmaker.dashboard.ui.widgets.LiveBadge;
import com.botmaker.dashboard.ui.widgets.ReleaseBoard;
import com.botmaker.dashboard.umbrella.ChangelogDrafts;
import com.botmaker.dashboard.umbrella.Io;
import com.botmaker.dashboard.umbrella.ProgressLine;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseRun;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import com.botmaker.dashboard.umbrella.VersionTargets;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The Release tab: what a release would do for a given set of flags, and the button that makes it do it.
 *
 * <p><b>Preview runs here; Execute starts a process of its own.</b> Both reach {@code com.botmaker.cli.release}
 * through {@link ReleaseRun#go} and differ by one argument, the {@code Runner}, which is what makes a preview
 * worth trusting: the text on screen was produced by the code that will do the work. A preview writes nothing,
 * so it runs in this JVM and a crash costs nothing. A release is started as {@link ReleaseLauncher} →
 * {@code ReleaseJob} since 2026-09-16, because the one cut in this JVM that day died with the window four tags
 * in. The window then only <i>watches</i> ({@link ReleaseWatcher}): it reads the child's output and the release
 * log, draws them as a {@link ReleaseBoard}, and can be closed and reopened — it reattaches to a job that is
 * still alive.
 *
 * <p><b>What guards it is arming, not a dialog alone.</b> Execute is dead until a preview has run <i>in this
 * session, with these exact flags</i> and returned no refusal; changing any flag disarms it, because the plan
 * on screen then describes a release nobody previewed. Then a confirmation that lists every module and version
 * about to be tagged and will not enable its own button until the word is typed. And it stays dead while a
 * release process is alive. A tag is permanent and no exit code recalls one — every guard here is about the
 * gap between what was read and what is run.
 *
 * <p><b>The rows are every module the library knows, before any preview</b>, in {@link Order#TAG}
 * ({@link ReleaseRowTable}). They came from the decide pass until 2026-09-16, which left the table empty until
 * the first preview and meant a level could not be picked before one. {@link Module} is the library's list,
 * not a copy of it here.
 *
 * <p><b>With one exception, and it is a placement rather than an omission</b>: a template
 * ({@link Module#template}) has no row here. {@code --gamebot} works identically through all three doors —
 * one implementation is the house rule — but the thing a template is published as is a <i>bot</i>, listed in
 * the Catalog tab beside the vetted and community ones, and that is where its fast update lives. A row here
 * would put an admin-owned template in the middle of the module chain it is not part of.
 *
 * <p><b>Picking a level or typing a version ticks that row</b>: it did not, and a level chosen on an unticked
 * row changed nothing while Execute stayed armed for the global level — which read as the tab remembering only
 * the preview's settings. Unticking resets nothing.
 *
 * <p><b>A refusal is a red banner above the output</b>, one line per gate in the gate's own words. It was one
 * count in the status line, with the reason somewhere in a long plan.
 *
 * <p><b>The one number this tab computes is the one it must not guess.</b> "Would cut" is
 * {@code com.botmaker.cli.release}'s own {@code latest_version} and {@code resolve_version} applied to that
 * module's newest tag, through {@link VersionTargets}.
 */
public final class ReleaseTab extends BorderPane {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final ReleaseBackend backend;

    private final ReleaseRowTable table = new ReleaseRowTable(this::refreshCommandLine);

    private final CheckBox allBox = new CheckBox("--all");
    private final ComboBox<String> allLevel = new ComboBox<>(
            FXCollections.observableArrayList("patch", "minor", "major"));
    private final CheckBox forceBox = new CheckBox("--force");
    private final CheckBox noWaitBox = new CheckBox("--no-wait-jitpack");

    private final Button preview = new Button("Preview");
    private final Button cancel = new Button("Cancel");
    private final Button execute = new Button("Execute…");
    private final Button stop = new Button("Stop");
    private final Label status = new Label();
    private final TextField commandLine = new TextField();
    private final TextArea output = new TextArea();
    private final TitledPane outputPane = new TitledPane("Output", output);

    private final VBox banner = new VBox(4);
    private final ReleaseBoard board = new ReleaseBoard(url -> Browse.open(url, this::say));
    private final LiveBadge badge = new LiveBadge();
    private final ReleaseWatcher watcher = new ReleaseWatcher();

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

    /**
     * The checkout that arming was granted in. The same flags decide different versions in a checkout whose
     * tags are somewhere else, so Execute also needs {@code armedRoot.equals(umbrella)}: a preview still
     * running when the operator switched checkout came back and armed the new one until 2026-09-29.
     */
    private Path armedRoot;

    /** The preview running in this JVM, or {@code null}; Cancel stops it. */
    private Io.Task<Previewed> previewing;

    public ReleaseTab(Path umbrella) {
        this(umbrella, ReleaseBackend.REAL);
    }

    ReleaseTab(Path umbrella, ReleaseBackend backend) {
        this.umbrella = umbrella;
        this.backend = backend;

        status.getStyleClass().add("status-line");
        preview.setOnAction(e -> preview());
        cancel.setOnAction(e -> cancelPreview());
        showCancel(false);
        execute.getStyleClass().add("danger");
        execute.setDisable(true);
        execute.setOnAction(e -> confirmThenExecute());
        stop.getStyleClass().add("danger");
        stop.setOnAction(e -> stopRelease());
        showStop(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, preview, cancel, execute, stop, status, spacer);
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
        watcher.stop();
        showStop(false);
        output.clear();
        hideBanner();
        board.setVisible(false);
        board.setManaged(false);
        badge.show(null);
        table.forget();
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

    /**
     * What the flags currently spell, and what that does to the two buttons.
     *
     * <p>Called from every control, which is what makes arming safe: the instant a tick or a keystroke makes
     * the spec differ from the armed one, Execute goes dead again.
     */
    private void refreshCommandLine() {
        ReleaseSpec spec = spec();
        commandLine.setText(spec.empty() ? "" : spec.commandLine());
        boolean runnable = umbrella != null && !spec.empty() && table.allSpecsWellFormed();
        boolean busy = previewing != null || watcher.watching();
        preview.setDisable(!runnable || busy);
        execute.setDisable(!runnable || busy || !spec.equals(armed) || !umbrella.equals(armedRoot));
    }

    /** Forgets the arming. Every path that changes what a release would do calls it. */
    private void disarm() {
        armed = null;
        armedPlan = null;
        armedRoot = null;
        execute.setDisable(true);
    }

    private ReleaseSpec spec() {
        Optional<String> all = allBox.isSelected()
                ? Optional.of(allLevel.getValue() == null ? "" : allLevel.getValue())
                : Optional.empty();
        return new ReleaseSpec(all, table.picked(), forceBox.isSelected(), noWaitBox.isSelected());
    }

    /**
     * The confirmation, and then the release process.
     *
     * <p><b>It lists {@link #armedPlan}, not a plan computed now.</b> Listing a fresh one would let the dialog
     * describe something the operator has not read — and the flags cannot have changed, because that disarms
     * the button that opened it.
     */
    private void confirmThenExecute() {
        if (umbrella == null || armed == null || armedPlan == null || watcher.watching()
                || !umbrella.equals(armedRoot)) {
            return;
        }
        List<String> tags = ReleaseConfirm.tags(armedPlan);
        if (tags.isEmpty()) {
            say("The preview decided to release nothing — there is no tag to cut.");
            return;
        }
        String warning = tags.size() + " tag(s) will be pushed, in tag order, and a pushed tag "
                + "cannot be edited or recalled. Each module's CI publishes its GitHub Release from the "
                + "tag, and JitPack caches its build result per tag — a bad one is repaired only by cutting "
                + "another.\n\nThe release runs as a process of its own: closing this window does not stop it, "
                + "and reopening the window shows it again.";
        if (ReleaseConfirm.ask(getScene() == null ? null : getScene().getWindow(), armed.executeCommandLine(),
                tags, warning)) {
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
        Path root = armedRoot;
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

    private void watch(ReleaseLauncher.Job job) {
        output.clear();
        outputPane.setExpanded(false);
        board.setVisible(true);
        board.setManaged(true);
        watcher.watch(job, new ReleaseWatcher.View() {
            @Override
            public void added(List<ProgressLine> lines) {
                for (ProgressLine line : lines) {
                    output.appendText(line.text() + "\n");
                }
            }

            @Override
            public void progress(ReleaseProgress progress) {
                board.show(progress);
                badge.show(progress);
            }

            @Override
            public void ended(ReleaseProgress progress) {
                showStop(false);
                ReleaseTab.this.ended(progress);
            }

            @Override
            public void unreadable(String sentence) {
                say(sentence);
            }
        });
        showStop(true);
        refreshCommandLine();
    }

    private void ended(ReleaseProgress progress) {
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

    /**
     * Runs a preview off the FX thread, streaming each line into the output as it arrives.
     *
     * <p>Minutes, not seconds: the decide pass shells to git in eleven repositories and the gates run Maven.
     * Both buttons are disabled meanwhile and the status line says what is running, because a window that
     * simply froze for that would read as broken. Cancel stops it ({@link Io.Task}): the command it is in is
     * killed, every later one answers at once, and nothing is armed.
     */
    private void preview() {
        if (umbrella == null || watcher.watching() || previewing != null) {
            return;
        }
        Path root = umbrella;
        ReleaseSpec spec = spec();
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
        AtomicReference<Io.Task<Previewed>> self = new AtomicReference<>();
        // A line from a cancelled preview is dropped: its output would run on under the next one's.
        Consumer<String> line = text -> Platform.runLater(() -> {
            if (previewing == self.get()) {
                output.appendText(text + "\n");
            }
        });
        Io.Task<Previewed> task = Io.cancellable(() -> {
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
        });
        self.set(task);
        previewing = task;
        showCancel(true);
        refreshCommandLine();
        task.future().whenComplete((previewed, error) -> Platform.runLater(() -> {
            if (previewing != task) {
                return;
            }
            previewing = null;
            showCancel(false);
            if (Io.wasCancelled(error)) {
                disarm();
                say("Preview cancelled — nothing is armed. Press Preview to read the checkout again.");
                refreshCommandLine();
                return;
            }
            if (!root.equals(umbrella)) {
                // The operator switched checkout while this ran: its plan is another tree's, and
                // setUmbrella has already cleared the screen for the new one.
                say("A preview of " + root + " finished after the checkout changed — it was dropped. "
                        + "Press Preview to read " + umbrella + ".");
                refreshCommandLine();
                return;
            }
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
            show(root, spec, previewed.run());
        }));
    }

    private void cancelPreview() {
        if (previewing != null) {
            say("Cancelling the preview …");
            previewing.cancel();
        }
    }

    private void showCancel(boolean shown) {
        cancel.setVisible(shown);
        cancel.setManaged(shown);
    }

    /** Stop is there while a release is watched, and dead once it was pressed for that job. */
    private void showStop(boolean shown) {
        stop.setVisible(shown);
        stop.setManaged(shown);
        stop.setDisable(!shown || watcher.job().map(ReleaseLauncher.Job::stopRequested).orElse(true));
    }

    /**
     * Asks the release process to stop before its next step.
     *
     * <p>No confirmation: stopping is the safe direction. It never cuts a git command short — a tag half
     * pushed is worse than one more tag — so the process ends at the next module or at once in a JitPack
     * wait, writes the log and commits the tagged pointers locally, as a failed module does.
     */
    private void stopRelease() {
        Optional<ReleaseLauncher.Job> job = watcher.job();
        if (job.isEmpty()) {
            return;
        }
        try {
            backend.stop(job.get());
            stop.setDisable(true);
            say("Stop asked. A release still tagging ends before its next module, or at once in a JitPack "
                    + "wait; a git command already running finishes first. Once every tag is out, the release "
                    + "still records and pushes what it tagged.");
        } catch (IOException e) {
            say("Could not ask the release to stop: " + e.getMessage());
        }
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
    private void show(Path root, ReleaseSpec spec, ReleaseRun run) {
        run.plan().ifPresent(table::mergeVerdicts);
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
            armedRoot = root;
            say(plan.releasing().size() + " of " + plan.decisions().size()
                    + " would release · Execute is armed for these flags.");
        }
        refreshCommandLine();
        loadLatest();
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

    private void loadLatest() {
        if (umbrella != null) {
            table.loadLatest(umbrella, backend, () -> umbrella);
        }
    }

    private void say(String text) {
        status.setText(text);
    }

    // ---- for tests --------------------------------------------------------------------------------------

    Button previewButton() {
        return preview;
    }

    Button cancelButton() {
        return cancel;
    }

    Button executeButton() {
        return execute;
    }

    VBox banner() {
        return banner;
    }

    List<ReleaseRowTable.Row> rows() {
        return table.rows();
    }

    TableView<ReleaseRowTable.Row> table() {
        return table;
    }
}
