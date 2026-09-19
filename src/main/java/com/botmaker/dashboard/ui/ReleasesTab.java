package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Actions;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.ui.widgets.ReleaseBoard;
import com.botmaker.dashboard.umbrella.ReleaseHistory;
import com.botmaker.dashboard.umbrella.ReleaseLog;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
     * <p><b>Held rather than computed in the cell.</b> {@code ReleaseProgress.past} rebuilds every lane of a
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
        writeBack.setOnAction(e -> writeBack());
        refresh.setDisable(true);
        deep.setDisable(true);
        writeBack.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, reload, refresh, deep, writeBack, status, spacer);
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
        CompletableFuture.supplyAsync(() -> {
            List<ReleaseHistory.TagRow> tags = backend.tags(root, false);
            return new Read(backend.cache(), tags, ReleaseHistory.releases(tags, backend.logs(root)));
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
        CompletableFuture.supplyAsync(() -> {
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
        board.show(ReleaseProgress.past(release, cache, Instant.now()));
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
                key -> ReleaseProgress.past(release, cache, Instant.now()).health());
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
        polls.submit(() -> {
            int asked = 0;
            for (ReleaseHistory.TagRow tag : release.tags()) {
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
                polling.put(tag.module(), tag.tag(), entry);
                redraw(release);
            }
            polling.save();
            int count = asked;
            Platform.runLater(() -> {
                if (Objects.equals(list.getSelectionModel().getSelectedItem(), release)) {
                    say(count == 0 ? "Every verdict is cached and current." : "Polled " + count + " tag(s).");
                }
            });
        });
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
        polls.submit(() -> {
            for (ReleaseHistory.TagRow tag : release.tags()) {
                Optional<Module> module = Module.byDirectory(tag.module());
                Optional<Version> version = Version.parse(tag.tag());
                if (module.isEmpty() || version.isEmpty()
                        || !com.botmaker.cli.release.ReleaseLog.onJitpack(module.get())) {
                    continue;
                }
                Platform.runLater(() -> say("Deep check: resolving " + tag.module() + ":" + tag.tag()
                        + " in a clean repository (about 40 s)…"));
                Verdicts.Deep answer = backend.deepCheck(module.get(), version.get());
                polling.put(tag.module(), tag.tag(),
                        polling.get(tag.module(), tag.tag()).withJitpack(answer.verdict(), answer.error(), Instant.now()));
                redraw(release);
            }
            polling.save();
            Platform.runLater(() -> {
                deep.setDisable(false);
                say("Deep check done.");
            });
        });
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
        CompletableFuture
                .supplyAsync(() -> backend.repoll(root, file, line -> Platform.runLater(() -> say(line.strip()))))
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
