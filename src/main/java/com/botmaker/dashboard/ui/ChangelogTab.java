package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Tags;
import com.botmaker.dashboard.umbrella.ChangelogEdit;
import com.botmaker.dashboard.umbrella.ClaudeDraft;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/**
 * The Changelog tab: write the {@code ## [Unreleased]} section a release will stamp, and commit it where it
 * lives.
 *
 * <p><b>It exists because the gate that refuses a release cannot be satisfied from this window otherwise.</b>
 * {@code ChangelogGate} refuses any module whose {@code CHANGELOG.md} describes neither the version being cut
 * nor an {@code [Unreleased]} release — which is exactly the state the half-cut release of 2026-09-16 left
 * {@code botmaker-studio} in, its section already stamped to {@code [1.1.0]}. Fixing that meant leaving the
 * window for an editor; now it does not.
 *
 * <p><b>Save writes one section and commits one file, in the submodule, and pushes nothing.</b> Everything
 * else in the file survives byte for byte — see {@link ChangelogEdit}. A push is the release's business.
 *
 * <p><b>Draft with Claude fills the editor and nothing else.</b> It is visible only to the maintainer — both
 * programs on {@code PATH} and the signed-in GitHub login equal to the repository owner — and it is hidden
 * rather than disabled for everybody else, because a disabled button is a promise this window cannot keep for
 * somebody else's account. What it produces is text in a text area, read and edited before it is committed
 * like anything typed here.
 */
public final class ChangelogTab extends BorderPane {

    private final ObservableList<String> modules = FXCollections.observableArrayList();
    private final ListView<String> list = new ListView<>(modules);

    private final Label heading = new Label();
    private final Label state = new Label();
    private final Label status = new Label();

    private final TextArea editor = new TextArea();
    private final TextArea commits = new TextArea();
    private final TextArea style = new TextArea();

    private final Button reload = new Button("Reload");
    private final Button save = new Button("Save and commit");
    private final Button draft = new Button("Draft with Claude");

    /**
     * One thread, because both things it runs are exclusive: a draft takes minutes and a save commits.
     * Daemon, so a draft nobody is waiting for any more does not hold the window open.
     */
    private final ExecutorService work = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "changelog");
        thread.setDaemon(true);
        return thread;
    });

    private Path umbrella;
    private String selected;
    private ChangelogEdit.Doc opened;
    private boolean owner;

    public ChangelogTab(Path umbrella, GitHubClient client, GitHubAuth auth) {
        this.umbrella = umbrella;

        heading.getStyleClass().add("placeholder-title");
        state.getStyleClass().add("status-line");
        status.getStyleClass().add("status-line");

        editor.getStyleClass().add("output-text");
        editor.setPromptText("### Added\n- …");
        editor.setWrapText(true);

        commits.setEditable(false);
        commits.setWrapText(false);
        commits.getStyleClass().add("output-text");
        style.setEditable(false);
        style.setWrapText(true);
        style.getStyleClass().add("output-text");

        list.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> open(now));
        list.setPrefWidth(220);

        reload.setOnAction(e -> open(selected));
        save.setOnAction(e -> save());
        draft.setOnAction(e -> draftWithClaude());
        save.setDisable(true);
        draft.setDisable(true);
        showDraftButton(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, reload, save, draft, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        TitledPane commitsPane = new TitledPane("Commits since the newest tag", commits);
        commitsPane.setExpanded(true);
        TitledPane stylePane = new TitledPane("The last released section, for tone", style);
        stylePane.setExpanded(false);

        VBox right = new VBox(8, heading, state, editor, commitsPane, stylePane);
        right.setPadding(new Insets(12));
        VBox.setVgrow(editor, Priority.ALWAYS);

        SplitPane split = new SplitPane(list, right);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.2);

        setTop(bar);
        setCenter(split);

        askWhetherOwner(client, auth);
        setUmbrella(umbrella);
    }

    /** Called when the operator picks a different checkout. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        if (umbrella == null) {
            modules.clear();
            status.setText("No umbrella checkout chosen — pick one in the top bar.");
            return;
        }
        // Every module the release cuts, in tag order: what has a CHANGELOG.md a gate will read.
        modules.setAll(Module.values() == null ? List.of() : java.util.Arrays.stream(Module.values())
                .map(Module::directory)
                .filter(directory -> java.nio.file.Files.isDirectory(umbrella.resolve(directory)))
                .toList());
        status.setText(modules.size() + " modules the release cuts. Nothing here is pushed.");
        if (!modules.isEmpty()) {
            list.getSelectionModel().select(selected == null || !modules.contains(selected)
                    ? modules.get(0) : selected);
        }
    }

    /**
     * Reads one module's changelog and the commits behind it.
     *
     * <p>Off the FX thread: it is several {@code git} calls, one of them a log over a whole release span.
     */
    private void open(String module) {
        selected = module;
        if (module == null || umbrella == null) {
            return;
        }
        Path root = umbrella;
        heading.setText(module);
        state.setText("Reading …");
        editor.setDisable(true);
        save.setDisable(true);
        draft.setDisable(true);
        CompletableFuture.supplyAsync(() -> read(root, module), work)
                .whenComplete((read, error) -> Platform.runLater(() -> {
                    if (!module.equals(selected)) {
                        return;                    // the operator moved on while git was running
                    }
                    if (error != null) {
                        state.setText("Could not read it: " + error.getMessage());
                        return;
                    }
                    show(read);
                }));
    }

    private record Read(ChangelogEdit.Doc doc, Optional<String> tag, String commits, String diffStat,
                        Optional<String> lastStamped) {
    }

    private static Read read(Path umbrella, String module) {
        ChangelogEdit.Doc doc = ChangelogEdit.read(umbrella, module);
        // The tag name is asked of the release library, which owns what "the newest tag" means here.
        Optional<String> tag = Module.byDirectory(module)
                .flatMap(m -> Tags.latest(umbrella, m).flatMap(v -> Tags.existingRef(umbrella, m, v)));
        return new Read(doc, tag,
                ChangelogEdit.commitsSince(umbrella, module, tag),
                ChangelogEdit.diffStat(umbrella, module, tag),
                ChangelogEdit.lastStamped(doc.text()));
    }

    private void show(Read read) {
        opened = read.doc();
        editor.setText(read.doc().unreleased().strip());
        commits.setText(read.commits().isBlank() ? "(no commits since the tag)" : read.commits());
        style.setText(read.lastStamped().orElse("(this changelog has no stamped section yet)"));
        editor.setDisable(!read.doc().exists());
        save.setDisable(!read.doc().exists() || read.doc().dirty());
        draft.setDisable(!read.doc().exists());
        state.setText(stateLine(read));
    }

    /**
     * One line about the file itself, which is the context every other control needs.
     *
     * <p>A dirty {@code CHANGELOG.md} is said here rather than discovered at Save: the commit this tab makes
     * carries one file, so an edit somebody had in flight would go into it under a message about something
     * else.
     */
    private static String stateLine(Read read) {
        if (!read.doc().exists()) {
            return "This module has no CHANGELOG.md — botmaker-pilot is the one the gate exempts.";
        }
        String where = read.tag().map(tag -> "newest tag " + tag).orElse("never released");
        String section = read.doc().hasSection()
                ? "has an [Unreleased] section"
                : "has no [Unreleased] section — the release would refuse it, and Save writes one";
        String dirty = read.doc().dirty()
                ? "  ·  uncommitted changes to CHANGELOG.md — commit or discard them, then Reload"
                : "";
        return where + "  ·  " + section + dirty;
    }

    /** Writes the section, commits it in the submodule, and re-reads what git now has. */
    private void save() {
        if (opened == null || selected == null || umbrella == null) {
            return;
        }
        String module = selected;
        Path root = umbrella;
        ChangelogEdit.Doc doc = opened;
        String body = editor.getText();
        setBusy(true, "Writing the section and committing it in " + module + " …");
        CompletableFuture.supplyAsync(() -> ChangelogEdit.save(root, module, doc, body), work)
                .whenComplete((saved, error) -> Platform.runLater(() -> {
                    setBusy(false, error != null
                            ? "Save failed: " + error.getMessage()
                            : saved.message());
                    if (error == null && saved.committed()) {
                        open(module);
                    }
                }));
    }

    /**
     * Asks Claude for the section, through {@code cswap}, and puts it in the editor.
     *
     * <p>Nothing is saved: the draft is text the maintainer then reads and edits. The status line names the
     * account <i>slot</i> it came from and never the address behind it.
     */
    private void draftWithClaude() {
        if (opened == null || selected == null || umbrella == null) {
            return;
        }
        String module = selected;
        Path root = umbrella;
        setBusy(true, "Asking cswap which account to draft with …");
        CompletableFuture
                .supplyAsync(() -> {
                    Read read = read(root, module);
                    ClaudeDraft.Request request = new ClaudeDraft.Request(module,
                            preamble(read.doc().text()), read.lastStamped(), read.commits(),
                            read.diffStat());
                    return ClaudeDraft.draft(root, request,
                            line -> Platform.runLater(() -> status.setText(line)));
                }, work)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        setBusy(false, "The draft failed: " + error.getMessage());
                        return;
                    }
                    if (result.drafted() && module.equals(selected)) {
                        editor.setText(result.text());
                    }
                    setBusy(false, result.message());
                }));
    }

    /** Everything above the first {@code ## } heading: what the module says about itself. */
    private static String preamble(String text) {
        int first = text.indexOf("\n## ");
        return first < 0 ? text : text.substring(0, first);
    }

    private void setBusy(boolean busy, String sentence) {
        reload.setDisable(busy);
        save.setDisable(busy || opened == null || !opened.exists() || opened.dirty());
        draft.setDisable(busy || opened == null || !opened.exists());
        editor.setDisable(busy);
        status.setText(sentence);
    }

    /**
     * Whether the drafter is offered at all.
     *
     * <p>Two conditions, and both have to hold: the programs exist on this machine, and the signed-in
     * account is the repository owner. The second is asked of GitHub rather than assumed from the checkout,
     * because a checkout says who cloned it and nothing about who is at the keyboard.
     */
    private void askWhetherOwner(GitHubClient client, GitHubAuth auth) {
        if (!ClaudeDraft.available()) {
            return;
        }
        auth.login(client).whenComplete((login, error) -> Platform.runLater(() -> {
            owner = error == null && login != null
                    && login.equalsIgnoreCase(GitHubConfig.REGISTRY_OWNER);
            showDraftButton(owner);
        }));
    }

    /** Called by the app when the signed-in account changes. */
    public void signedInChanged(GitHubClient client, GitHubAuth auth) {
        showDraftButton(false);
        askWhetherOwner(client, auth);
    }

    private void showDraftButton(boolean show) {
        draft.setVisible(show);
        draft.setManaged(show);
    }
}
