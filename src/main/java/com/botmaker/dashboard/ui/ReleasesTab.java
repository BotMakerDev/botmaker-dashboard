package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Actions;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.ui.widgets.ReleaseBoard;
import com.botmaker.dashboard.umbrella.Io;
import com.botmaker.dashboard.umbrella.PastProgress;
import com.botmaker.dashboard.umbrella.ReleaseHistory;
import com.botmaker.dashboard.umbrella.ReleaseLog;
import com.botmaker.dashboard.umbrella.VerdictCache;
import com.botmaker.dashboard.umbrella.Verdicts;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The Releases tab: every release the tags describe, newest first, each drawn as the board a running release
 * is drawn as.
 *
 * <p><b>It works with no log at all</b> (since 2026-09-16). It listed {@code releases/*.md} until then, and the
 * release cut that day died before writing one, so the tab's newest entry was eleven days old while four tags
 * sat on origin. {@link ReleaseHistory} groups the tags into releases; a log that matches a group is laid over
 * it and names it, and never hides a tag it does not mention.
 *
 * <p><b>Verdicts are polled, not read.</b> JitPack is a {@code .pom} HEAD, shown as {@code published (pom HEAD)}
 * — never as {@code ok}, which is the clean room's word — and <i>Deep check</i> runs the clean-room resolve the
 * release runs. Actions is {@code Actions.poll}. Answers go to a {@link VerdictCache}, so the tab opens from the
 * cache, each verdict shows its age, and only stale unsettled ones are asked again in the background.
 * <i>Refresh verdicts</i> asks every one of the selected release again, and fetches tags.
 *
 * <p><b>Writing back to a log is still {@code ReleaseStatus.repoll}</b>, offered only where a log exists: it
 * rewrites the committed file through the release's own readers, as a reviewable diff.
 */
public final class ReleasesTab extends BorderPane {

    /** What this tab calls outside itself — git, the network, the cache — so a test can hand in answers. */
    interface Backend {
        List<ReleaseHistory.TagRow> tags(Path umbrella, boolean fetch);

        List<ReleaseLog> logs(Path umbrella);

        VerdictCache cache();

        String jitpackHead(Module module, Version version);

        Actions.Poll actions(Module module, Version version);

        Verdicts.Deep deepCheck(Module module, Version version);

        ReleaseLog.Repoll repoll(Path umbrella, Path file, Consumer<String> line);

        Backend REAL = new Backend() {
            @Override
            public List<ReleaseHistory.TagRow> tags(Path umbrella, boolean fetch) {
                return ReleaseHistory.tags(umbrella, fetch);
            }

            @Override
            public List<ReleaseLog> logs(Path umbrella) {
                return ReleaseLog.list(umbrella).stream().flatMap(file -> {
                    try {
                        return java.util.stream.Stream.of(ReleaseLog.read(file));
                    } catch (RuntimeException e) {
                        return java.util.stream.Stream.empty();
                    }
                }).toList();
            }

            @Override
            public VerdictCache cache() {
                return VerdictCache.load();
            }

            @Override
            public String jitpackHead(Module module, Version version) {
                return Verdicts.jitpackHead(module, version);
            }

            @Override
            public Actions.Poll actions(Module module, Version version) {
                return Verdicts.actions(module, version);
            }

            @Override
            public Verdicts.Deep deepCheck(Module module, Version version) {
                return Verdicts.deepCheck(module, version);
            }

            @Override
            public ReleaseLog.Repoll repoll(Path umbrella, Path file, Consumer<String> line) {
                return ReleaseLog.repoll(umbrella, file, line);
            }
        };
    }

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Backend backend;
    private final ObservableList<ReleaseHistory.Release> releases = FXCollections.observableArrayList();
    private final ListView<ReleaseHistory.Release> list = new ListView<>(releases);
    private final ReleaseBoard board = new ReleaseBoard(url -> Browse.open(url, this::say));
    private final Label heading = new Label();
    private final Label status = new Label();
    private final Button refresh = new Button("Refresh verdicts");
    private final Button deep = new Button("Deep check");
    private final Button writeBack = new Button("Write back to the log");

    /**
     * Each release's dot colour, keyed by its start — the one thing the list cell reads.
     *
     * <p><b>Held rather than computed in the cell.</b> {@code PastProgress.of} rebuilds every lane of a
     * release from its log and the cache, and a {@code ListCell} runs on every scroll, every resize and every
     * {@code refresh()} — so a poll that redrew the list after each answered tag recomputed the whole visible
     * history each time, and the dots flickered while it did. Now a dot changes when its release's health
     * actually changes, and the list is refreshed only then.
     */
    private final Map<Instant, String> health = new HashMap<>();

    /** One thread for every poll: a history's worth of {@code gh} calls at once is a rate limit, not speed. */
    private final ExecutorService polls = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "release-verdicts");
        thread.setDaemon(true);
        return thread;
    });

    /** The poll for the release on screen; a new selection cancels it. */
    private Future<?> currentPoll;
    /** What {@link #currentPoll} is asking about, so a poll it already covers does not cancel it. */
    private Instant currentPollStart;
    private boolean currentPollAll;

    /** The deep check running, which only its own Cancel stops — a selection change does not. */
    private Future<?> currentDeep;
    private final java.util.concurrent.atomic.AtomicBoolean deepStarted = new java.util.concurrent.atomic.AtomicBoolean();
    private final Button cancelDeep = new Button("Cancel");

    private Path umbrella;
    private VerdictCache cache;

    public ReleasesTab(Path umbrella) {
        this(umbrella, Backend.REAL);
    }

    ReleasesTab(Path umbrella, Backend backend) {
        this.umbrella = umbrella;
        this.backend = backend;

        heading.getStyleClass().add("placeholder-body");
        heading.setWrapText(true);
        status.getStyleClass().add("status-line");

        Button reload = new Button("Reload");
        reload.setOnAction(e -> reload());
        refresh.setOnAction(e -> refresh());
        deep.setOnAction(e -> deepCheck());
        cancelDeep.setOnAction(e -> cancelDeepCheck());
        showCancel(false);
        writeBack.setOnAction(e -> writeBack());
        refresh.setDisable(true);
        deep.setDisable(true);
        writeBack.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, reload, refresh, deep, cancelDeep, writeBack, status, spacer);
        bar.getStyleClass().add("tab-bar");
        bar.setPadding(new Insets(10, 12, 10, 12));

        list.setCellFactory(v -> new ReleaseCell());
        list.setPlaceholder(new Label("No release tags in this checkout."));
        list.getSelectionModel().selectedItemProperty().addListener((o, was, is) -> select(is));

        VBox right = new VBox(8, heading, board);
        VBox.setVgrow(board, Priority.ALWAYS);
        right.setPadding(new Insets(12));

        SplitPane split = new SplitPane(list, right);
        split.setDividerPositions(0.24);
        setTop(bar);
        setCenter(split);

        reload();
    }

    /** Called when the operator picks a different checkout. */
    public void setUmbrella(Path umbrella) {
        this.umbrella = umbrella;
        reload();
    }

    /**
     * Lists the releases from local tags and the logs at once, then fetches tags and lists again if that found
     * anything new. The cache is read here too, so the list's health dots are there before any poll.
     */
    public void reload() {
        Path root = umbrella;
        if (root == null) {
            releases.clear();
            say("No umbrella checkout chosen — pick one in the top bar.");
            return;
        }
        say("Reading tags and release logs…");
        // One cache for the tab's life: a fresh load per reload left a queued poll saving the old one, and the
        // two saves overwrote each other's answers (2026-09-29). The file is not per checkout, so nothing to drop.
        VerdictCache known = cache;
        Io.async(() -> {
            List<ReleaseHistory.TagRow> tags = backend.tags(root, false);
            return new Read(known != null ? known : backend.cache(), tags,
                    ReleaseHistory.releases(tags, backend.logs(root)));
        }).whenComplete((read, error) -> Platform.runLater(() -> {
            if (error != null || !root.equals(umbrella)) {
                say(error == null ? "" : "Could not read the history: " + error.getMessage());
                return;
            }
            cache = read.cache();
            setReleases(read.releases());
            say(read.releases().size() + " releases from tags · fetching tags from origin…");
            fetchThenRelist(root, read.tags());
        }));
    }

    /** What one off-thread read hands back to the FX thread. */
    private record Read(VerdictCache cache, List<ReleaseHistory.TagRow> tags, List<ReleaseHistory.Release> releases) {
    }

    private void fetchThenRelist(Path root, List<?> before) {
        Io.async(() -> {
            List<ReleaseHistory.TagRow> tags = backend.tags(root, true);
            return tags.equals(before) ? null : ReleaseHistory.releases(tags, backend.logs(root));
        }).whenComplete((found, error) -> Platform.runLater(() -> {
            if (!root.equals(umbrella)) {
                return;
            }
            if (found != null) {
                setReleases(found);
            }
            say(releases.size() + " releases from tags" + (error != null ? " · the tag fetch failed" : ""));
        }));
    }

    /** Replaces the list, keeping the selected release when it is still there (matched by its start). */
    private void setReleases(List<ReleaseHistory.Release> found) {
        Instant selected = Optional.ofNullable(list.getSelectionModel().getSelectedItem())
                .map(ReleaseHistory.Release::start).orElse(null);
        // A relist may have moved a tag from one group to another, so every held dot is a guess now.
        health.clear();
        releases.setAll(found);
        int index = 0;
        for (int i = 0; i < found.size(); i++) {
            if (found.get(i).start().equals(selected)) {
                index = i;
            }
        }
        if (!found.isEmpty()) {
            list.getSelectionModel().select(index);
            // The same release selected again fires no change; its tags or log may have.
            select(found.get(index));
        } else {
            board.setVisible(false);
            heading.setText("");
        }
    }

    private void select(ReleaseHistory.Release release) {
        refresh.setDisable(release == null);
        deep.setDisable(release == null || release.tags().isEmpty());
        writeBack.setDisable(release == null || release.log().isEmpty());
        if (release == null || cache == null) {
            return;
        }
        draw(release);
        poll(release, false);
    }

    private void draw(ReleaseHistory.Release release) {
        board.setVisible(true);
        board.show(PastProgress.of(release, cache, Instant.now()));
        heading.setText(release.log()
                .map(l -> "Log releases/" + l.file().getFileName() + " · ")
                .orElse("No release log — read from tags alone · ")
                + release.tags().size() + " tag(s)"
                + (release.moduleCount() > release.tags().size()
                ? ", " + (release.moduleCount() - release.tags().size()) + " module(s) the log names never tagged"
                : ""));
    }

    /**
     * The dot for one release, computed once and kept.
     *
     * <p>{@code Instant.now()} only decides how old a cached verdict is said to be, which the dot does not
     * show — so a value held across a few minutes says the same thing a fresh one would.
     */
    private String healthOf(ReleaseHistory.Release release) {
        return health.computeIfAbsent(release.start(),
                key -> PastProgress.of(release, cache, Instant.now()).health());
    }

    /** Recomputes one release's dot, and repaints the list only when that dot actually changed. */
    private void healthChanged(ReleaseHistory.Release release) {
        String was = health.remove(release.start());
        if (!Objects.equals(was, healthOf(release))) {
            list.refresh();
        }
    }

    /**
     * Asks JitPack and Actions about each tag of a release, one at a time, redrawing as each answers.
     *
     * @param all every verdict, not only the stale ones — the Refresh button
     */
    private void poll(ReleaseHistory.Release release, boolean all) {
        VerdictCache polling = cache;
        // Arrowing through the history queued a full poll per release passed over; only the one on screen
        // matters. A poll already running stops at its next tag, and what it answered is kept.
        // A running poll of the same release that asks at least as much covers this one. Cancelling it was what
        // Refresh did to itself (2026-10-01): its relist reselected the release, the reselect's poll interrupted
        // the Refresh mid-request, and the interrupted answers read "no run" and "unknown (interrupted)".
        if (currentPoll != null && !currentPoll.isDone()) {
            if (release.start().equals(currentPollStart) && (currentPollAll || !all)) {
                return;
            }
            currentPoll.cancel(true);
        }
        currentPollStart = release.start();
        currentPollAll = all;
        currentPoll = polls.submit(() -> guarded("The poll", () -> {
            int asked = 0;
            try {
                asked = pollTags(release, all, polling);
            } finally {
                polling.save();
            }
            int count = asked;
            Platform.runLater(() -> {
                if (Objects.equals(list.getSelectionModel().getSelectedItem(), release)) {
                    say(count == 0 ? "Every verdict is cached and current." : "Polled " + count + " tag(s).");
                }
            });
        }));
    }

    /** The poll's loop: asks about each due tag, redrawing as each answers. Answers how many were asked. */
    private int pollTags(ReleaseHistory.Release release, boolean all, VerdictCache polling) {
        int asked = 0;
        for (ReleaseHistory.TagRow tag : release.tags()) {
            if (Thread.currentThread().isInterrupted()) {
                return asked;
            }
            Optional<Module> module = Module.byDirectory(tag.module());
            Optional<Version> version = Version.parse(tag.tag());
            if (module.isEmpty() || version.isEmpty()) {
                continue;
            }
            Instant now = Instant.now();
            VerdictCache.Entry entry = polling.get(tag.module(), tag.tag());
            boolean jitpackDue = com.botmaker.cli.release.ReleaseLog.onJitpack(module.get())
                    && (all || entry.jitpackAt() == 0 || entry.jitpackStale(now))
                    // A deep check's answer outranks a HEAD, and a refresh must not downgrade it.
                    && !entry.jitpack().startsWith("ok (resolves") && !entry.jitpack().startsWith("BROKEN");
            boolean actionsDue = all || entry.actionsAt() == 0 || entry.actionsStale(now);
            if (!jitpackDue && !actionsDue) {
                continue;
            }
            asked++;
            Platform.runLater(() -> say("Polling " + tag.module() + " " + tag.tag() + "…"));
            if (jitpackDue) {
                entry = entry.withJitpack(backend.jitpackHead(module.get(), version.get()), "", Instant.now());
            }
            if (actionsDue) {
                Actions.Poll answer = backend.actions(module.get(), version.get());
                entry = entry.withActions(answer.verdict(), answer.error(), answer.url(), Instant.now());
            }
            // A request the cancel cut short answers about the cancel, not the tag: an interrupted gh reads as
            // "no run", which the lane shows as Failed. Nothing it said is kept.
            if (Thread.currentThread().isInterrupted()) {
                return asked;
            }
            polling.put(tag.module(), tag.tag(), entry);
            redraw(release);
        }
        return asked;
    }

    /**
     * Runs one background task and says so when it throws.
     *
     * <p>{@code ExecutorService.submit} keeps a task's exception in a {@code Future} nobody reads, so a
     * failed poll left "Polling …" on screen and a failed deep check left its button disabled for good
     * (2026-09-29). An interrupt is a cancel, not a failure, and says nothing.
     */
    private void guarded(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            if (!Thread.currentThread().isInterrupted()) {
                Platform.runLater(() -> say(what + " failed: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            }
        }
    }

    private void redraw(ReleaseHistory.Release release) {
        Platform.runLater(() -> {
            if (Objects.equals(list.getSelectionModel().getSelectedItem(), release)) {
                draw(release);
            }
            // The board above redraws on every answered tag, because that is the thing being watched. The
            // list does not: one release's dot is all that can have changed, and repainting the history for
            // each of ten tags is what made the dots flicker.
            healthChanged(release);
        });
    }

    private void refresh() {
        ReleaseHistory.Release release = list.getSelectionModel().getSelectedItem();
        if (release == null || umbrella == null) {
            return;
        }
        poll(release, true);
        fetchThenRelist(umbrella, List.of());
    }

    /** The release's own clean-room resolve over each JitPack module: about forty seconds apiece. */
    private void deepCheck() {
        ReleaseHistory.Release release = list.getSelectionModel().getSelectedItem();
        if (release == null) {
            return;
        }
        VerdictCache polling = cache;
        deep.setDisable(true);
        showCancel(true);
        deepStarted.set(false);
        currentDeep = polls.submit(() -> {
            deepStarted.set(true);
            boolean[] done = {false};
            try {
                guarded("The deep check", () -> {
                    for (ReleaseHistory.TagRow tag : release.tags()) {
                        if (Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        Optional<Module> module = Module.byDirectory(tag.module());
                        Optional<Version> version = Version.parse(tag.tag());
                        if (module.isEmpty() || version.isEmpty()
                                || !com.botmaker.cli.release.ReleaseLog.onJitpack(module.get())) {
                            continue;
                        }
                        Platform.runLater(() -> say("Deep check: resolving " + tag.module() + ":" + tag.tag()
                                + " in a clean repository (about 40 s)…"));
                        Verdicts.Deep answer = backend.deepCheck(module.get(), version.get());
                        if (Thread.currentThread().isInterrupted()) {
                            // Cancelled under the resolve: its answer is about a killed Maven, not the tag.
                            return;
                        }
                        polling.put(tag.module(), tag.tag(), polling.get(tag.module(), tag.tag())
                                .withJitpack(answer.verdict(), answer.error(), Instant.now()));
                        redraw(release);
                    }
                    done[0] = true;
                });
            } finally {
                polling.save();
                boolean cancelled = Thread.currentThread().isInterrupted();
                Platform.runLater(() -> {
                    deep.setDisable(false);
                    showCancel(false);
                    if (done[0]) {
                        say("Deep check done.");
                    } else if (cancelled) {
                        say("Deep check cancelled — what it answered before is kept.");
                    }
                });
            }
        });
    }

    /**
     * Stops the deep check: the interrupt kills the clean room's Maven (the release library's {@code Proc}
     * stops on one since 2026-09-29), and the tag it was resolving keeps the verdict it had.
     */
    private void cancelDeepCheck() {
        if (currentDeep != null) {
            currentDeep.cancel(true);
            if (!deepStarted.get()) {
                // Still queued behind a poll: it will never run, so nothing else gives the button back.
                deep.setDisable(false);
                showCancel(false);
                say("Deep check cancelled before it started.");
                return;
            }
            say("Cancelling the deep check …");
        }
    }

    private void showCancel(boolean shown) {
        cancelDeep.setVisible(shown);
        cancelDeep.setManaged(shown);
    }

    /** {@code ReleaseStatus.repoll} over the log this release has, then everything read again. */
    private void writeBack() {
        ReleaseHistory.Release release = list.getSelectionModel().getSelectedItem();
        if (release == null || release.log().isEmpty() || umbrella == null) {
            return;
        }
        Path file = release.log().get().file();
        Path root = umbrella;
        writeBack.setDisable(true);
        say("Re-polling " + file.getFileName() + " — resolving artifacts and polling Actions…");
        Io.async(() -> backend.repoll(root, file, line -> Platform.runLater(() -> say(line.strip()))))
                .whenComplete((polled, error) -> Platform.runLater(() -> {
                    writeBack.setDisable(false);
                    if (error != null) {
                        say("Re-poll failed: " + error.getMessage());
                        return;
                    }
                    say(polled.ok()
                            ? "Re-polled. The log was rewritten in place — commit it as a diff."
                            : "Re-poll stopped: " + polled.error().orElse("no reason given"));
                    reload();
                }));
    }

    private void say(String text) {
        status.setText(text);
    }

    /** Date, module count and a health dot: red on any failure, dim while anything is unanswered. */
    private final class ReleaseCell extends ListCell<ReleaseHistory.Release> {
        private final Region dot = new Region();

        ReleaseCell() {
            dot.getStyleClass().add("health-dot");
            dot.setMinSize(9, 9);
            dot.setMaxSize(9, 9);
        }

        @Override
        protected void updateItem(ReleaseHistory.Release item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(WHEN.format(item.start()) + " · " + item.moduleCount() + " module"
                    + (item.moduleCount() == 1 ? "" : "s") + (item.log().isEmpty() ? " · no log" : ""));
            dot.getStyleClass().removeAll("health-dot--ok", "health-dot--broken", "health-dot--pending");
            if (cache != null) {
                dot.getStyleClass().add("health-dot--" + healthOf(item));
            }
            setGraphic(dot);
        }
    }

    // ---- for tests --------------------------------------------------------------------------------------

    ListView<ReleaseHistory.Release> list() {
        return list;
    }

    ReleaseBoard board() {
        return board;
    }

    Label heading() {
        return heading;
    }
}
