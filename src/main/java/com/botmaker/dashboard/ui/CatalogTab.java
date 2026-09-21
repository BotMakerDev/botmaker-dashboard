package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Plan;
import com.botmaker.dashboard.github.Admin;
import com.botmaker.dashboard.github.Catalog;
import com.botmaker.dashboard.github.EntryFields;
import com.botmaker.dashboard.github.Vetting;
import com.botmaker.dashboard.ui.widgets.LinkBar;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseRun;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Catalog tab: what is <b>published</b> — every merged plugin entry and every published bot, with the
 * templates among them marked.
 *
 * <p><b>It is the other half of the Queue tab and the distinction is the point.</b> Queue lists open pull
 * requests, which is zero most days; that is the truthful answer to "what is waiting on me" and no answer at
 * all to "what can somebody install". This tab answers the second question, and it reads github.com rather
 * than the checked-out data submodules, whose recorded pointers trail their own {@code main} whenever either
 * repository's CI regenerates an index.
 *
 * <p><b>Nothing here judges an entry.</b> Everything listed is already merged, and what let it in was
 * {@code RegistryGate}'s check run on the pull request that added it. A second opinion formed in this window
 * would eventually disagree with the gate, and the operator would have no way to know which was right.
 *
 * <p><b>The two writes are pull requests, and they are gated on {@link Admin#canWrite()}</b> exactly as the
 * Queue tab's are — which is a courtesy and not a boundary, since GitHub answers 403 regardless. What it
 * buys is that the operator learns they cannot do it before typing an edit rather than after. Neither
 * action changes {@code main}: Edit proposes new text, Unpublish proposes removing the file, and a human
 * merges or does not.
 *
 * <p><b>The one button that is not about a data repository is {@code Update template…}</b> (2026-09-21). A
 * template this project maintains — {@code botmaker-gamebot} — is listed here as the bot it is published as,
 * so this is where its release lives: it previews {@code botmaker release --gamebot} through
 * {@link ReleaseRun}, the same library and the same call the Release tab makes, which has no row for a
 * template. It is a shortcut into one implementation, not a second thing that tags a repository: it previews,
 * and then it can cut — under the Release tab's guards rather than beside them, arming by value on the exact
 * version previewed, the same typed word and the same {@link ReleaseLauncher} child.
 * <b>Releasing is not vetting</b>: {@code Vet…} is still what moves {@code vettedVersion}, and the
 * {@code Latest} column beside the tier is what makes a vetting left behind visible at all.
 */
public final class CatalogTab extends BorderPane {

    private final GitHubClient client;
    private final GitHubAuth auth;

    private final ObservableList<Catalog.Entry> entries = FXCollections.observableArrayList();
    private final TableView<Catalog.Entry> table = new TableView<>(entries);

    private final ObservableList<EntryFields.Field> fields = FXCollections.observableArrayList();
    private final TableView<EntryFields.Field> detail = new TableView<>(fields);

    private final Label heading = new Label();
    private final Label where = new Label();
    private final Label status = new Label();

    private final Button refresh = new Button("Refresh");
    private final LinkBar links = new LinkBar(url -> Browse.open(url, status::setText));
    private final Button edit = new Button("Edit…");
    private final Button unpublish = new Button("Unpublish…");
    private final Button vet = new Button("Vet…");
    private final Button revoke = new Button("Revoke vetting…");
    private final Button update = new Button("Update template…");

    /**
     * The newest release GitHub reports per entry path, filled after the listing lands.
     *
     * <p>Keyed by {@link Catalog.Entry#path()} rather than held on the entry, because an entry is the file
     * as it stands on {@code main} and this is not in it. A row whose answer has not arrived — or whose
     * repository has no release — shows nothing rather than a guess.
     */
    private final Map<String, SimpleStringProperty> latest = new HashMap<>();

    private Admin admin = new Admin(false, "not checked yet");
    private Path umbrella;

    /**
     * Where a release started from this tab is handed to, so the operator sees it running.
     *
     * <p><b>A handoff rather than a field of type {@code ReleaseTab}.</b> The board, the lanes and the
     * reattach are that tab's, and this one has no business knowing they exist — what it knows is that it
     * started a job and that somebody else draws jobs. {@code DashboardApp} is where the two are wired,
     * which is also the only place that can select a tab.
     *
     * <p>Does nothing by default, so a test constructing this tab alone starts a release without a window
     * to show it in.
     */
    private Consumer<ReleaseLauncher.Job> onReleaseStarted = job -> { };

    public CatalogTab(Path umbrella, GitHubClient client, GitHubAuth auth) {
        this.umbrella = umbrella;
        this.client = client;
        this.auth = auth;

        heading.getStyleClass().add("placeholder-title");
        status.getStyleClass().add("status-line");
        where.getStyleClass().add("status-line");

        refresh.setOnAction(e -> reload());
        edit.setOnAction(e -> withSelected(this::edit));
        unpublish.setOnAction(e -> withSelected(this::unpublish));
        vet.setOnAction(e -> withSelected(this::vet));
        revoke.setOnAction(e -> withSelected(this::revoke));
        update.setOnAction(e -> withSelected(this::updateTemplate));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, refresh, edit, unpublish, vet, revoke, update, spacer, status);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        buildColumns();
        table.setPlaceholder(new Label("Nothing published yet — or press Refresh."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> show(now));
        table.setRowFactory(t -> {
            TableRow<Catalog.Entry> row = new TableRow<>();
            // Built when the menu is asked for, so a recycled row never offers the previous entry's pages.
            row.setOnContextMenuRequested(e -> {
                if (row.getItem() != null) {
                    LinkBar.menu(row.getItem().links(), url -> Browse.open(url, status::setText))
                            .show(row, e.getScreenX(), e.getScreenY());
                }
            });
            return row;
        });

        detail.setPlaceholder(new Label("Pick an entry above to see what it says."));
        detail.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        detail.getColumns().setAll(
                field("Field", 220, EntryFields.Field::name),
                field("Value", 480, EntryFields.Field::value));

        VBox bottom = new VBox(8, heading, where, links, detail);
        bottom.setPadding(new Insets(12));
        VBox.setVgrow(detail, Priority.ALWAYS);

        SplitPane split = new SplitPane(table, bottom);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.52);

        setTop(bar);
        setCenter(split);

        gateButtons();
        reload();
    }

    /**
     * Both repositories' merged entries, off the FX thread.
     *
     * <p>The status line counts what was found by kind, because the two numbers answer different questions
     * and an operator reading "6 entries" learns neither.
     */
    public void reload() {
        refresh.setDisable(true);
        status.setText("Reading " + String.join(" and ", Catalog.Kind.PLUGIN.repo(),
                Catalog.Kind.BOT.repo()) + " …");
        Catalog.list(client, auth).whenComplete((found, error) -> Platform.runLater(() -> {
            refresh.setDisable(false);
            if (error != null) {
                // Named, not paraphrased: an offline machine, a rate limit and a renamed repository are
                // different problems and only GitHub's own sentence says which.
                status.setText("Could not read the catalog: " + message(error));
                return;
            }
            entries.setAll(found);
            status.setText(summary(found) + readOnly());
            gateButtons();
            loadLatest(found);
        }));
    }

    /**
     * Fills the Latest column, one request per bot, after the listing is on screen.
     *
     * <p><b>Why it is worth a column rather than a glance at the repository.</b> {@code Vetted v0.2.0} reads
     * as healthy whatever {@code main} is doing — on 2026-09-21 the worked bot's vetting pointed at the
     * pre-migration template while three releases had gone out past it, and nothing in this window said so.
     * The two numbers side by side are the whole diagnosis.
     *
     * <p>Only bots are asked. A plugin's {@code verifiedVersion} is a different idea — the release the
     * registry's gate downloaded — and putting a newest-release number beside it would invite reading one as
     * the other.
     */
    private void loadLatest(List<Catalog.Entry> found) {
        latest.keySet().retainAll(found.stream().map(Catalog.Entry::path).toList());
        for (Catalog.Entry entry : found) {
            if (entry.kind() != Catalog.Kind.BOT || entry.repo().isEmpty()) {
                continue;
            }
            SimpleStringProperty cell = latest.computeIfAbsent(entry.path(), p -> new SimpleStringProperty(""));
            Vetting.latestRelease(client, auth, entry).whenComplete((tag, error) -> Platform.runLater(() ->
                    // A repository with no release answers blank, and so does one this token cannot read.
                    // Neither is worth a red cell: the column says what is published, not whether GitHub
                    // answered, and the status line already carries a failure that touched every row.
                    cell.set(error != null || tag == null ? "" : tag)));
        }
    }

    /**
     * One line of counts.
     *
     * <p>Unreadable entries are counted separately rather than folded in. An entry on {@code main} that this
     * window cannot parse is a fact about the repository, not about this window, and burying it in a total
     * is how it stays unnoticed.
     */
    private static String summary(java.util.List<Catalog.Entry> found) {
        long plugins = found.stream().filter(e -> e.kind() == Catalog.Kind.PLUGIN).count();
        long bots = found.stream().filter(e -> e.kind() == Catalog.Kind.BOT).count();
        long templates = found.stream().filter(Catalog.Entry::template).count();
        long vetted = found.stream().filter(e -> e.vetted() != null).count();
        long broken = found.stream().filter(e -> !e.readable()).count();
        return plugins + " plugin" + (plugins == 1 ? "" : "s")
                + " · " + bots + " bot" + (bots == 1 ? "" : "s")
                + " (" + templates + " template" + (templates == 1 ? "" : "s") + ", " + vetted + " vetted)"
                + (broken == 0 ? "" : " — " + broken + " that could not be read");
    }

    /** Why the write buttons are dark, on the one line the operator is already reading. */
    private String readOnly() {
        return admin.canWrite() ? "" : "  ·  read-only: " + admin.reason();
    }

    private void show(Catalog.Entry entry) {
        gateButtons();
        if (entry == null) {
            heading.setText("");
            where.setText("");
            links.show(List.of());
            fields.clear();
            return;
        }
        links.show(entry.links());
        heading.setText(entry.kindLabel() + " · " + entry.label()
                + (entry.name().equals(entry.label()) ? "" : " — " + entry.name()));
        where.setText(entry.kind().repo() + " · " + entry.path()
                + (entry.vetted() == null ? "" : " · vetted " + entry.vetted().record().vettedAt()
                        + " by " + entry.vetted().record().vettedBy()));
        where.getStyleClass().removeAll("cell--broken", "cell--dim");
        where.getStyleClass().add(entry.readable() ? "cell--dim" : "cell--broken");
        // The bytes are already in hand from the listing pass, so there is nothing to fetch and nothing to
        // race: no stale-selection guard is needed here, unlike the Queue tab's.
        fields.setAll(EntryFields.read(entry.json()));
    }

    /**
     * Called whenever the admin probe answers again — sign-in, sign-out, and once at startup.
     *
     * <p>Held rather than asked at click time, for the Queue tab's reason: the buttons have to be disabled
     * <i>before</i> an edit is typed, which is the entire value of reading the permission at all.
     */
    public void setAdmin(Admin admin) {
        this.admin = admin;
        gateButtons();
    }

    /** Called when the operator picks a different checkout — the templates are released out of that one. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        gateButtons();
    }

    /**
     * What to do with a release this tab starts: show it, wherever releases are shown.
     *
     * <p>Set by {@code DashboardApp} to hand the job to the Release tab and select it. See
     * {@link #onReleaseStarted} for why it is a callback and not that tab.
     */
    public void setOnReleaseStarted(Consumer<ReleaseLauncher.Job> onReleaseStarted) {
        this.onReleaseStarted = onReleaseStarted == null ? job -> { } : onReleaseStarted;
    }

    /**
     * Which release module a listed entry <b>is</b>, when it is one this project maintains.
     *
     * <p>Matched on the repository <b>name</b> against {@link Module#directory}, never on the entry's
     * {@code template} tag: that tag is a gallery idea any submission can claim, and this button cuts a tag.
     * The answer is the release library's own list, so a template that stops being a module stops having a
     * button rather than having a broken one.
     *
     * <p><b>The owner is dropped, deliberately.</b> What the button releases is the {@code botmaker-gamebot}
     * submodule of the umbrella in use — never the repository the entry names — so the question is which
     * module of this checkout the row is about. This project's own repositories do not agree on one owner
     * anyway (the gallery's entries are {@code BotMakerDev}'s, the registry is {@code LiQiyeDev}'s), so an
     * owner in the match would be a second list to keep.
     */
    static Optional<Module> releasable(Catalog.Entry entry) {
        if (entry == null || entry.repo().isEmpty()) {
            return Optional.empty();
        }
        String repo = entry.repo().substring(entry.repo().indexOf('/') + 1);
        return java.util.Arrays.stream(Module.values())
                .filter(Module::template)
                .filter(module -> module.directory().equals(repo))
                .findFirst();
    }

    /**
     * Which buttons are live.
     *
     * <p>Unpublish additionally requires a <b>readable</b> entry. Not as a judgement — an unreadable entry
     * is exactly one worth removing — but because the confirmation asks the operator to type the entry's
     * id, and the id of an entry that did not parse is the filename this window guessed. Asking somebody to
     * confirm a removal by typing a guess is not a confirmation.
     */
    private void gateButtons() {
        Catalog.Entry selected = table.getSelectionModel().getSelectedItem();
        boolean row = selected != null;
        edit.setDisable(!row || !admin.canWrite());
        unpublish.setDisable(!row || !admin.canWrite() || !selected.readable());
        // A vetting names owner/repo, which only a readable bot entry states rather than guesses.
        boolean bot = row && selected.kind() == Catalog.Kind.BOT && selected.readable();
        vet.setDisable(!bot || !admin.canWrite());
        revoke.setDisable(!bot || !admin.canWrite() || selected.vetted() == null);
        // Not gated on admin.canWrite(): that probe asks about the two data repositories this tab proposes
        // pull requests against, and a release pushes tags to the module's own repository with git's
        // credentials. A permission answered about the wrong repository is worse than none.
        update.setDisable(umbrella == null || releasable(selected).isEmpty());
    }

    /**
     * The fast path to {@code botmaker release --gamebot} — a shortcut into the release library, not a
     * second thing that tags a repository.
     *
     * <p><b>It is here rather than in the Release tab</b> because a template is published as a <i>bot</i>,
     * listed on this very row beside the vetted and community ones; a row in the module chain would put an
     * admin-owned template in the middle of a dependency order it is not part of. What it reaches is
     * {@link ReleaseRun#go} with one module ticked, exactly as the Release tab does, so the plan on screen
     * is produced by the code that would do the work.
     *
     * <p><b>It previews, then it can cut, under the Release tab's guards rather than beside them.</b> Release
     * it… is dead until a preview of <i>this exact version, in this session</i> has come back with no
     * refusal, and editing the version kills it again — arming by value, the same rule and the same reason:
     * the plan on screen would otherwise describe a release nobody read. Then the same typed
     * {@link ReleaseTab#CONFIRM_WORD}, and the same {@link ReleaseLauncher} child, so closing this window
     * does not stop a release. What is <b>not</b> duplicated is the decision: both buttons are
     * {@link ReleaseRun#go} with one module ticked.
     *
     * <p><b>And {@code Vet…} is still what moves {@code vettedVersion}.</b> Releasing the template publishes
     * a tag; deciding that Studio should offer it is a separate act, a pull request a human merges.
     */
    private void updateTemplate(Catalog.Entry entry) {
        Module module = releasable(entry).orElse(null);
        if (module == null || umbrella == null) {
            return;
        }
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
        ReleaseSpec[] armed = {null};
        Plan[] armedPlan = {null};
        version.textProperty().addListener((o, was, is) -> {
            armed[0] = null;
            armedPlan[0] = null;
            releaseButton.setDisable(true);
        });

        // Consumed, so the dialog stays open with the plan in it — the whole point of previewing here.
        previewButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            preview(module, version.getText().trim(), output, previewButton, run -> {
                boolean clean = run != null && run.decided() && !run.stopped();
                armed[0] = clean ? spec(module, version.getText().trim()) : null;
                armedPlan[0] = clean ? run.plan().orElse(null) : null;
                releaseButton.setDisable(armedPlan[0] == null);
            });
        });
        releaseButton.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            e.consume();
            if (armed[0] != null && armedPlan[0] != null && confirm(armed[0], armedPlan[0])) {
                launch(armed[0]);
                dialog.setResult(ButtonType.CLOSE);
                dialog.close();
            }
        });
        Themed.dialog(dialog, window());
        dialog.showAndWait();
    }

    /**
     * The same confirmation the Release tab puts in front of Execute: what will be tagged, why it cannot be
     * undone, and a word to type.
     *
     * <p>It lists the plan rather than the flag, because a release cuts what the <i>decide pass</i> decided —
     * a forced module would be in that list and is not in the command line.
     */
    private boolean confirm(ReleaseSpec spec, Plan plan) {
        List<String> tags = plan.releasing().entrySet().stream()
                .map(cut -> "    " + cut.getKey().directory() + "  " + cut.getValue().tag())
                .toList();
        if (tags.isEmpty()) {
            status.setText("The preview decided to release nothing — there is no tag to cut.");
            return false;
        }
        TextArea list = new TextArea(String.join("\n", tags));
        list.setEditable(false);
        list.getStyleClass().add("output-text");
        list.setPrefRowCount(Math.min(8, tags.size() + 1));

        Label warning = new Label(tags.size() + " tag(s) will be pushed, and a pushed tag cannot be edited or"
                + " recalled.\n\nThe release runs as a process of its own: closing this window does not stop"
                + " it, and the Release tab shows it.\n\nThis publishes the template. It does not change what"
                + " Studio offers — Vet… is what moves vettedVersion.\n\nType " + ReleaseTab.CONFIRM_WORD
                + " to enable the button.");
        warning.setWrapText(true);

        TextField typed = new TextField();
        typed.setPromptText(ReleaseTab.CONFIRM_WORD);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Cut this release");
        dialog.setHeaderText(String.join(" ", spec.command(true)));
        ButtonType cut = new ButtonType("Cut the release", ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.CANCEL, cut);
        pane.setContent(new VBox(10, list, warning, typed));
        pane.lookupButton(cut).setDisable(true);
        typed.textProperty().addListener((o, was, is) ->
                pane.lookupButton(cut).setDisable(!ReleaseTab.CONFIRM_WORD.equals(is.strip())));
        Themed.dialog(dialog, window());
        return dialog.showAndWait().filter(cut::equals).isPresent();
    }

    /**
     * Starts the release in a process of its own — {@link ReleaseLauncher}, as the Release tab does.
     *
     * <p><b>Then hands the job over and says so.</b> This line claimed the Release tab was watching it and
     * nothing made that true: that tab reattaches on construction and on a change of checkout, and it
     * filters for a job still {@linkplain ReleaseLauncher.Job#alive alive}. A template release finishes in
     * about ten seconds, so by the time the operator had switched tabs there was nothing left to find and
     * the board stayed empty — a release with no visible sign it had run.
     */
    private void launch(ReleaseSpec spec) {
        try {
            ReleaseLauncher.Launched launched = ReleaseLauncher.launch(umbrella, spec);
            status.setText("Released " + String.join(" ", spec.command(true)) + " — started "
                    + launched.how() + ". The Release tab is watching it.");
            onReleaseStarted.accept(launched.job());
        } catch (IOException e) {
            status.setText("The release process did not start, and nothing was run: " + e.getMessage());
        }
    }

    /** One module, one spec — what the flag would be on the command line. */
    private static ReleaseSpec spec(Module module, String version) {
        return new ReleaseSpec(Optional.empty(), Map.of(module, version), false, false);
    }

    /**
     * Runs the preview off the FX thread: the decide pass shells to git and the gates run Maven.
     *
     * @param armed called on the FX thread with the finished run, or {@code null} when it could not start —
     *              which is what decides whether Release it… wakes up
     */
    private void preview(Module module, String version, TextArea output, Button button,
                         Consumer<ReleaseRun> armed) {
        if (!ReleaseSpec.wellFormed(version)) {
            output.setText("want x.y.z or patch|minor|major, not " + version);
            armed.accept(null);
            return;
        }
        button.setDisable(true);
        output.setText("");
        Path root = umbrella;
        StringBuilder text = new StringBuilder();
        CompletableFuture
                .supplyAsync(() -> ReleaseRun.go(root, spec(module, version), false,
                        line -> text.append(line).append('\n')))
                .whenComplete((run, error) -> Platform.runLater(() -> {
                    button.setDisable(false);
                    output.setText(error != null ? message(error) : run.output());
                    output.positionCaret(output.getLength());
                    armed.accept(error != null ? null : run);
                }));
    }

    /**
     * Proposes vetting the selected bot at one release.
     *
     * <p>The version box starts on the newest release, which is almost always the one just looked at, and is
     * editable because it need not be. Nothing about the release is checked here: the gallery's gate checks
     * that it downloads, on the pull request this opens.
     */
    private void vet(Catalog.Entry entry) {
        TextField version = new TextField(entry.vetted() == null ? "" : entry.vetted().record().vettedVersion());
        version.setPromptText("release tag, e.g. v0.1.0");
        Vetting.latestRelease(client, auth, entry).thenAccept(tag -> Platform.runLater(() -> {
            if (!tag.isBlank() && version.getText().isBlank()) {
                version.setText(tag);
            }
        }));
        TextField why = new TextField();
        why.setPromptText("What you looked at (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Vet " + entry.id());
        dialog.setHeaderText((entry.vetted() == null ? "" : "Vetted now at "
                + entry.vetted().record().vettedVersion() + ".\n")
                + "Opens a pull request writing vetted/ in " + entry.kind().repo() + ". Once merged, Studio shows "
                + entry.id() + " as Vetted and installs exactly this release.\n\nThe release you looked at:");
        VBox body = new VBox(8, version, why);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        pane.getButtonTypes().setAll(open, ButtonType.CANCEL);
        pane.lookupButton(open).disableProperty().bind(version.textProperty().isEmpty());
        Themed.dialog(dialog, window());

        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        String tag = version.getText().trim();
        if (entry.vetted() != null && tag.equals(entry.vetted().record().vettedVersion())) {
            status.setText(entry.id() + " is already vetted at " + tag + ", so no pull request was opened.");
            return;
        }
        propose(entry, "Vet", Vetting.vet(client, auth, entry, tag, why.getText()));
    }

    /** Proposes removing the selected bot's vetting; its listing stays. */
    private void revoke(Catalog.Entry entry) {
        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Revoke vetting of " + entry.id());
        dialog.setHeaderText("Opens a pull request deleting " + entry.vetted().path() + ".\nOnce merged, "
                + entry.id() + " is Community again: still listed, installed at its newest release, and gone from "
                + "the index older Studios read.");
        dialog.getDialogPane().setContent(new VBox(8, why));
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().setAll(open, ButtonType.CANCEL);
        Themed.dialog(dialog, window());
        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        propose(entry, "Revoke", Vetting.revoke(client, auth, entry, why.getText()));
    }

    /**
     * Proposes new text for the selected entry.
     *
     * <p><b>A text area over the JSON, not a form built from a field list.</b> A form can only show the
     * keys it was written to know about, so it would silently drop one an entry carries and this window has
     * never heard of — the same reason {@link EntryFields} reads the file rather than a schema. The dialog
     * says whether the text parses, and does not refuse it: whether an entry is <i>good</i> is
     * {@code RegistryGate}'s answer on the pull request, and a syntax opinion formed here is the first step
     * towards a second gate.
     */
    private void edit(Catalog.Entry entry) {
        TextArea json = new TextArea(entry.json() == null ? "" : entry.json());
        json.getStyleClass().add("output-text");
        json.setPrefRowCount(20);
        json.setPrefColumnCount(90);

        Label parses = new Label();
        parses.getStyleClass().add("status-line");
        json.textProperty().addListener((obs, was, now) -> sayWhetherItParses(parses, now));
        sayWhetherItParses(parses, json.getText());

        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Edit " + entry.id());
        dialog.setHeaderText("Opens a pull request against " + entry.kind().repo()
                + ". Nothing on " + "main" + " changes until somebody merges it.");
        VBox body = new VBox(8, json, parses, why);
        VBox.setVgrow(json, Priority.ALWAYS);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        pane.getButtonTypes().setAll(new ButtonType("Open pull request", ButtonType.OK.getButtonData()),
                ButtonType.CANCEL);
        Themed.dialog(dialog, window());

        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        String text = json.getText();
        if (text.equals(entry.json())) {
            // Not a gate — arithmetic. A pull request that changes nothing is one somebody has to close.
            status.setText("Nothing changed, so no pull request was opened.");
            return;
        }
        propose(entry, "Edit", Catalog.edit(client, auth, entry, text, why.getText()));
    }

    private static void sayWhetherItParses(Label label, String text) {
        try {
            new ObjectMapper().readTree(text);
            label.setText("Parses as JSON.");
            label.getStyleClass().removeAll("cell--broken");
        } catch (Exception e) {
            label.setText("Not valid JSON — the gate will refuse this: " + e.getMessage());
            if (!label.getStyleClass().contains("cell--broken")) {
                label.getStyleClass().add("cell--broken");
            }
        }
    }

    /**
     * Proposes removing the selected entry.
     *
     * <p><b>Typing the id, not clicking Yes.</b> Merging this makes a plugin disappear from every user's
     * Manage Plugins and a bot from the gallery Studio reads — and the id is the one thing that cannot be
     * recovered by re-submitting, because the file name is the claim. A confirmation somebody can dismiss
     * by reflex is not one.
     */
    private void unpublish(Catalog.Entry entry) {
        TextField typed = new TextField();
        typed.setPromptText(entry.id());
        TextField why = new TextField();
        why.setPromptText("Why (optional) — goes in the pull request body");

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Unpublish " + entry.id());
        dialog.setHeaderText("This opens a pull request that deletes " + entry.path() + " from "
                + entry.kind().repo() + ".\nNothing is removed until somebody merges it. Merging it removes "
                + entry.id() + " for everyone on the next index build.\n\nType the id to confirm:");
        VBox body = new VBox(8, typed, why);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(body);
        ButtonType open = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        pane.getButtonTypes().setAll(open, ButtonType.CANCEL);
        pane.lookupButton(open).setDisable(true);
        typed.textProperty().addListener((obs, was, now) ->
                pane.lookupButton(open).setDisable(!entry.id().equals(now.trim())));
        Themed.dialog(dialog, window());

        Optional<ButtonType> chose = dialog.showAndWait();
        if (chose.isEmpty() || chose.get().getButtonData() != ButtonType.OK.getButtonData()) {
            return;
        }
        propose(entry, "Unpublish", Catalog.unpublish(client, auth, entry, why.getText()));
    }

    /**
     * Runs one proposal and says what became of it.
     *
     * <p>The catalog is <b>not</b> reloaded afterwards, and that is the honest thing: nothing about what is
     * published has changed, because a pull request is not a merge. The Queue tab is where the proposal now
     * lives, with the gate's verdict against it.
     */
    private void propose(Catalog.Entry entry, String what, CompletableFuture<Catalog.Proposal> running) {
        setWritesDisabled(true);
        status.setText(what.toLowerCase() + " " + entry.id() + " — opening a pull request …");
        running.whenComplete((proposal, error) -> Platform.runLater(() -> {
            setWritesDisabled(false);
            gateButtons();
            if (error != null) {
                status.setText(what + " failed.");
                failed(error);
                return;
            }
            status.setText(what + " proposed as " + entry.kind().repo() + " #" + proposal.number()
                    + " — it is in the Queue tab now, with the gate's verdict.");
            opened(proposal);
        }));
    }

    /** Offers the pull request rather than opening a browser unasked. */
    private void opened(Catalog.Proposal proposal) {
        ButtonType openIt = new ButtonType("Open pull request", ButtonType.OK.getButtonData());
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText("Opened #" + proposal.number());
        alert.setContentText("Branch " + proposal.branch()
                + ".\nNothing is published or unpublished until it is merged.");
        alert.getButtonTypes().setAll(openIt, ButtonType.CLOSE);
        Themed.dialog(alert, window());
        alert.showAndWait()
                .filter(b -> b == openIt)
                .ifPresent(b -> Browse.open(proposal.url(), status::setText));
    }

    private void setWritesDisabled(boolean disabled) {
        edit.setDisable(disabled);
        unpublish.setDisable(disabled);
        vet.setDisable(disabled);
        revoke.setDisable(disabled);
    }

    /**
     * Shows GitHub's own sentence.
     *
     * <p>A 403 because the token's scope was narrowed, a 409 because {@code main} moved under the blob sha,
     * and a 422 because the branch already exists are three different problems, and paraphrasing them into
     * "could not open a pull request" costs the operator the only line that says which.
     */
    private void failed(Throwable error) {
        TextArea text = new TextArea(message(error));
        text.setEditable(false);
        text.setWrapText(true);
        text.getStyleClass().add("error-text");
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText("GitHub refused it");
        alert.getDialogPane().setContent(text);
        alert.getButtonTypes().setAll(ButtonType.OK);
        Themed.dialog(alert, window());
        alert.showAndWait();
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void withSelected(java.util.function.Consumer<Catalog.Entry> action) {
        Catalog.Entry entry = table.getSelectionModel().getSelectedItem();
        if (entry != null) {
            action.accept(entry);
        }
    }

    private static String message(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private void buildColumns() {
        table.getColumns().setAll(
                kindColumn(),
                column("Entry", 260, Catalog.Entry::label),
                tierColumn(),
                latestColumn(),
                column("Name", 180, Catalog.Entry::name),
                column("Tags", 200, Catalog.Entry::tagLine),
                column("Description", 380, Catalog.Entry::summary));
    }

    private static TableColumn<Catalog.Entry, String> column(String title, double width,
                                                             Function<Catalog.Entry, String> text) {
        TableColumn<Catalog.Entry, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }

    /**
     * Vetted (with its release) or Community, for a bot; blank for a plugin. Double-clicking opens the bot's
     * repository, which is what somebody deciding whether to vet it has to read.
     */
    private TableColumn<Catalog.Entry, String> tierColumn() {
        TableColumn<Catalog.Entry, String> col = column("Tier", 130, Catalog.Entry::tierLabel);
        col.setCellFactory(c -> {
            TableCell<Catalog.Entry, String> cell = new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                    getStyleClass().removeAll("cell--ok", "cell--dim");
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        return;
                    }
                    getStyleClass().add(getTableRow().getItem().vetted() != null ? "cell--ok" : "cell--dim");
                }
            };
            cell.setOnMouseClicked(e -> {
                Catalog.Entry entry = cell.getTableRow() == null ? null : cell.getTableRow().getItem();
                if (e.getClickCount() == 2 && entry != null && !entry.repo().isEmpty()) {
                    Browse.open("https://github.com/" + entry.repo(), status::setText);
                }
            });
            return cell;
        });
        return col;
    }

    /**
     * The repository's newest release, beside the one the vetting pins.
     *
     * <p>Green when they agree, amber when the vetting is behind, plain when there is nothing to compare —
     * an unvetted bot, or a repository that has cut no release. <b>Behind is not an error</b> and does not
     * get the broken style: a vetting deliberately lags while somebody looks at the new release, and that
     * is the tier working rather than failing.
     */
    private TableColumn<Catalog.Entry, String> latestColumn() {
        TableColumn<Catalog.Entry, String> col = new TableColumn<>("Latest");
        col.setPrefWidth(110);
        col.setCellValueFactory(c -> latest.computeIfAbsent(c.getValue().path(),
                p -> new SimpleStringProperty("")));
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || item.isBlank() ? null : item);
                getStyleClass().removeAll("cell--ok", "cell--pending", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                Catalog.Vetted vetted = getTableRow().getItem().vetted();
                if (item == null || item.isBlank() || vetted == null) {
                    getStyleClass().add("cell--dim");
                    return;
                }
                getStyleClass().add(item.equals(vetted.record().vettedVersion())
                        ? "cell--ok" : "cell--pending");
            }
        });
        return col;
    }

    /** Plugin / Bot / Template, red when the entry could not be parsed at all. */
    private static TableColumn<Catalog.Entry, String> kindColumn() {
        TableColumn<Catalog.Entry, String> col = column("Kind", 90, Catalog.Entry::kindLabel);
        col.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("cell--ok", "cell--broken", "cell--dim");
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                Catalog.Entry entry = getTableRow().getItem();
                getStyleClass().add(!entry.readable() ? "cell--broken"
                        : entry.template() ? "cell--ok" : "cell--dim");
            }
        });
        return col;
    }

    private static TableColumn<EntryFields.Field, String> field(String title, double width,
                                                                Function<EntryFields.Field, String> text) {
        TableColumn<EntryFields.Field, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(c -> new SimpleStringProperty(text.apply(c.getValue())));
        return col;
    }
}
