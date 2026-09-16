package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Actions;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.ReleaseHistory;
import com.botmaker.dashboard.umbrella.ReleaseLog;
import com.botmaker.dashboard.umbrella.VerdictCache;
import com.botmaker.dashboard.umbrella.Verdicts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Releases tab over a checkout with tags and no {@code releases/} directory at all. */
class ReleasesTabTest extends FxHeadless {

    @TempDir
    Path umbrella;
    @TempDir
    Path cacheDir;

    private ReleasesTab tab;
    private final AtomicInteger actionsPolls = new AtomicInteger();

    private static ReleaseHistory.TagRow tag(String module, String tag, String date) {
        return new ReleaseHistory.TagRow(module, tag, OffsetDateTime.parse(date).toInstant());
    }

    private final class Fake implements ReleasesTab.Backend {
        private final VerdictCache cache = VerdictCache.load(cacheDir.resolve("cache.json"));

        @Override
        public List<ReleaseHistory.TagRow> tags(Path root, boolean fetch) {
            return List.of(
                    tag("botmaker-studio", "v1.0.37", "2026-09-05T12:12:33+02:00"),
                    tag("botmaker-studio", "v1.1.0", "2026-09-16T12:23:18+02:00"),
                    tag("botmaker-studio-api", "v0.1.0", "2026-09-16T12:23:24+02:00"),
                    tag("botmaker-plugin-toolkit", "v0.1.0", "2026-09-16T12:24:33+02:00"),
                    tag("botmaker-plugin-host", "v0.1.0", "2026-09-16T12:25:23+02:00"));
        }

        @Override
        public List<ReleaseLog> logs(Path root) {
            return List.of();
        }

        @Override
        public VerdictCache cache() {
            return cache;
        }

        @Override
        public String jitpackHead(Module module, Version version) {
            return "published (pom HEAD)";
        }

        @Override
        public Actions.Poll actions(Module module, Version version) {
            actionsPolls.incrementAndGet();
            return module == Module.PLUGIN_HOST || module == Module.STUDIO
                    ? new Actions.Poll("FAILED — CI", "CI: failure — https://github.com/LiQiyeDev/" + module.directory())
                    : new Actions.Poll("success (1)", "");
        }

        @Override
        public Verdicts.Deep deepCheck(Module module, Version version) {
            return new Verdicts.Deep("ok (resolves clean)", "");
        }

        @Override
        public ReleaseLog.Repoll repoll(Path root, Path file, Consumer<String> line) {
            throw new AssertionError("no log to write back to");
        }
    }

    private void open() throws Exception {
        interact(() -> {
            tab = new ReleasesTab(umbrella, new Fake());
            show(tab);
        });
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> tab.list().getItems().size() == 2);
    }

    @Test
    void theHalfCutReleaseIsListedFromItsTagsAndItsVerdictsArePolled() throws Exception {
        assertFalse(Files.exists(umbrella.resolve("releases")));
        open();

        ReleaseHistory.Release newest = tab.list().getItems().getFirst();
        assertEquals(4, newest.tags().size());
        assertEquals(newest, tab.list().getSelectionModel().getSelectedItem());
        assertTrue(tab.heading().getText().startsWith("No release log — read from tags alone"));

        // Every tag of the selected release is asked about once, and the host's failure reaches its lane.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> actionsPolls.get() == 4);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(2, lookup(".module-lane--failed").queryAll().size(), "studio and the host failed");
        assertEquals(4, lookup(".module-lane").queryAll().size());
    }

    @Test
    void eachReleaseKeepsOneDotRatherThanOnePerRepaint() throws Exception {
        open();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> actionsPolls.get() == 4);
        WaitForAsyncUtils.waitForFxEvents();

        // One dot per row, each carrying exactly one state class: a cell recomputing its own health on
        // every repaint is what made them flicker while a poll answered one tag at a time.
        List<javafx.scene.Node> dots = List.copyOf(lookup(".health-dot").queryAll());
        assertEquals(2, dots.size());
        for (javafx.scene.Node dot : dots) {
            assertEquals(1, dot.getStyleClass().stream().filter(c -> c.startsWith("health-dot--")).count(),
                    dot.getStyleClass().toString());
        }
        assertTrue(dots.getFirst().getStyleClass().contains("health-dot--broken"),
                "the half-cut release failed on Actions");

        // Repainting the list does not change what a dot says.
        interact(() -> tab.list().refresh());
        WaitForAsyncUtils.waitForFxEvents();
        assertTrue(lookup(".health-dot").queryAll().stream()
                .anyMatch(dot -> dot.getStyleClass().contains("health-dot--broken")));
    }

    @Test
    void aCachedSettledVerdictIsNotAskedAgain() throws Exception {
        open();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> actionsPolls.get() == 4);

        // Selecting the other release and back: the four answers are settled and cached.
        interact(() -> tab.list().getSelectionModel().select(1));
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> actionsPolls.get() == 5);
        interact(() -> tab.list().getSelectionModel().select(0));
        Thread.sleep(300);
        WaitForAsyncUtils.waitForFxEvents();
        assertEquals(5, actionsPolls.get());
    }
}
