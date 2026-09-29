package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Plan;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.umbrella.Io;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseRun;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The fast path to {@code botmaker release --gamebot} — a shortcut into the release library, not a second
 * thing that tags a repository. The Catalog tab's <i>Update template…</i>, in its own file since 2026-09-29.
 *
 * <p><b>It is in the Catalog rather than the Release tab</b> because a template is published as a <i>bot</i>,
 * listed on that row beside the vetted and community ones; a row in the module chain would put an admin-owned
 * template in the middle of a dependency order it is not part of. What it reaches is {@link ReleaseRun#go}
 * with one module ticked, exactly as the Release tab does, so the plan on screen is produced by the code that
 * would do the work.
 *
 * <p><b>It previews, then it can cut, under the Release tab's guards rather than beside them.</b> Release
 * it… is dead until a preview of <i>this exact version, in this session</i> has come back with no refusal, and
 * editing the version kills it again — arming by value, the same rule and the same reason: the plan on screen
 * would otherwise describe a release nobody read. Then the same typed {@link ReleaseConfirm}, and the same
 * {@link ReleaseLauncher} child, so closing the window does not stop a release — and the launcher refuses
 * while another release is running in the checkout.
 *
 * <p><b>The preview streams</b>, line by line as the library prints them, and can be cancelled; it showed
 * nothing until the whole pass had returned until 2026-09-29, with its lines gathered into a buffer nobody read.
 *
 * <p><b>And {@code Vet…} is still what moves {@code vettedVersion}.</b> Releasing the template publishes a
 * tag; deciding that Studio should offer it is a separate act, a pull request a human merges.
 */
final class TemplateReleaseDialog {

    private final Path umbrella;
    private final Window owner;
    private final Consumer<String> status;
    private final Consumer<ReleaseLauncher.Job> started;

    /**
     * @param status  the Catalog tab's status line
     * @param started where a release this starts is handed, so the operator sees it running
     */
    TemplateReleaseDialog(Path umbrella, Window owner, Consumer<String> status,
                          Consumer<ReleaseLauncher.Job> started) {
        this.umbrella = umbrella;
        this.owner = owner;
        this.status = status;
        this.started = started;
    }

    void show(Catalog.Entry entry, Module module) {
        TextField version = new TextField("patch");
        version.setPromptText("x.y.z, or patch|minor|major");
        TextArea output = new TextArea();
        output.setEditable(false);
        output.getStyleClass().add("output-text");
        output.setPrefRowCount(18);
        output.setPrefColumnCount(100);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Update " + entry.id());
        dialog.setHeaderText("Previews " + String.join(" ", spec(module, "…").command(false))
                + " in " + umbrella + ".\nRelease it… stays dead until a preview of that exact version comes"
                + " back clean."
                + (entry.vetted() == null ? ""
                        : "\nVetted now at " + entry.vetted().record().vettedVersion()
                                + " — releasing does not move that; Vet… does."));
        VBox body = new VBox(8, version, output);
        VBox.setVgrow(output, Priority.ALWAYS);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        ButtonType previewIt = new ButtonType("Preview", ButtonBar.ButtonData.OTHER);
        ButtonType releaseIt = new ButtonType("Release it…", ButtonBar.ButtonData.OTHER);
        pane.getButtonTypes().setAll(previewIt, releaseIt, ButtonType.CLOSE);
        Button previewButton = (Button) pane.lookupButton(previewIt);
        Button releaseButton = (Button) pane.lookupButton(releaseIt);
        releaseButton.getStyleClass().add("danger");
        releaseButton.setDisable(true);

        // What a clean preview armed, as a value: the spec that produced the plan on screen, and the plan
        // itself for the confirmation's list. Editing the version clears both, because the text then
        // describes a release nobody previewed — the Release tab's rule, for its reason.
        Armed armed = new Armed();
        version.textProperty().addListener((o, was, is) -> {
            armed.clear();
            releaseButton.setDisable(true);
        });

        // Consumed, so the dialog stays open with the plan in it — the whole point of previewing here. While a
        // preview runs the same button cancels it.
        previewButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (armed.running != null) {
                armed.running.cancel();
                return;
            }
            String typed = version.getText().trim();
            armed.clear();
            releaseButton.setDisable(true);
            preview(module, typed, output, previewButton, armed, run -> {
                boolean clean = run != null && run.decided() && !run.stopped();
                armed.spec = clean ? spec(module, typed) : null;
                armed.plan = clean ? run.plan().orElse(null) : null;
                releaseButton.setDisable(armed.plan == null);
            });
        });
        releaseButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (armed.spec != null && armed.plan != null && confirm(armed.spec, armed.plan)) {
                launch(armed.spec);
                dialog.setResult(ButtonType.CLOSE);
                dialog.close();
            }
        });
        // Closing the window stops a preview nobody will read.
        dialog.setOnHidden(e -> {
            if (armed.running != null) {
                armed.running.cancel();
            }
        });
        Themed.dialog(dialog, owner);
        dialog.showAndWait();
    }

    /** The dialog's state: what a clean preview armed, and the preview running. */
    private static final class Armed {
        ReleaseSpec spec;
        Plan plan;
        Io.Task<ReleaseRun> running;

        void clear() {
            spec = null;
            plan = null;
        }
    }

    /**
     * Runs the preview off the FX thread — the decide pass shells to git and the gates run Maven — and
     * streams its lines into {@code output} as they come.
     *
     * @param done called on the FX thread with the finished run, or {@code null} when it did not finish —
     *             which is what decides whether Release it… wakes up
     */
    private void preview(Module module, String version, TextArea output, Button button, Armed armed,
                         Consumer<ReleaseRun> done) {
        if (!ReleaseSpec.wellFormed(version)) {
            output.setText("want x.y.z or patch|minor|major, not " + version);
            done.accept(null);
            return;
        }
        output.clear();
        Path root = umbrella;
        Io.Task<ReleaseRun> task = Io.cancellable(() -> ReleaseRun.go(root, spec(module, version), false,
                line -> Platform.runLater(() -> {
                    if (armed.running != null && !armed.running.cancelled()) {
                        output.appendText(line + "\n");
                    }
                })));
        armed.running = task;
        button.setText("Cancel preview");
        task.future().whenComplete((run, error) -> Platform.runLater(() -> {
            if (armed.running != task) {
                return;
            }
            armed.running = null;
            button.setText("Preview");
            if (Io.wasCancelled(error)) {
                output.appendText("\n(cancelled — nothing is armed)\n");
                done.accept(null);
                return;
            }
            if (error != null) {
                output.appendText("\n" + CatalogTab.message(error) + "\n");
            }
            output.positionCaret(output.getLength());
            done.accept(error != null ? null : run);
        }));
    }

    /**
     * The same confirmation the Release tab puts in front of Execute: what will be tagged, why it cannot be
     * undone, and a word to type.
     *
     * <p>It lists the plan rather than the flag, because a release cuts what the <i>decide pass</i> decided —
     * a forced module would be in that list and is not in the command line.
     */
    private boolean confirm(ReleaseSpec spec, Plan plan) {
        List<String> tags = ReleaseConfirm.tags(plan);
        if (tags.isEmpty()) {
            status.accept("The preview decided to release nothing — there is no tag to cut.");
            return false;
        }
        return ReleaseConfirm.ask(owner, String.join(" ", spec.command(true)), tags,
                tags.size() + " tag(s) will be pushed, and a pushed tag cannot be edited or recalled.\n\nThe"
                        + " release runs as a process of its own: closing this window does not stop it, and the"
                        + " Release tab shows it.\n\nThis publishes the template. It does not change what Studio"
                        + " offers — Vet… is what moves vettedVersion.");
    }

    /**
     * Starts the release in a process of its own — {@link ReleaseLauncher}, as the Release tab does — then
     * hands the job over and says so.
     *
     * <p>This line claimed the Release tab was watching it and nothing made that true: that tab reattaches on
     * construction and on a change of checkout, and it filters for a job still alive. A template release
     * finishes in about ten seconds, so by the time the operator had switched tabs there was nothing left to
     * find and the board stayed empty — a release with no visible sign it had run.
     */
    private void launch(ReleaseSpec spec) {
        try {
            ReleaseLauncher.Launched launched = ReleaseLauncher.launch(umbrella, spec);
            status.accept("Released " + String.join(" ", spec.command(true)) + " — started "
                    + launched.how() + ". The Release tab is watching it.");
            started.accept(launched.job());
        } catch (IOException e) {
            status.accept("The release process did not start, and nothing was run: " + e.getMessage());
        }
    }

    /** One module, one spec — what the flag would be on the command line. */
    private static ReleaseSpec spec(Module module, String version) {
        return new ReleaseSpec(Optional.empty(), Map.of(module, version), false, false);
    }
}
