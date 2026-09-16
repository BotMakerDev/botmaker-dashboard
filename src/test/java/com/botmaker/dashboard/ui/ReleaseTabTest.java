package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Plan;
import com.botmaker.cli.release.Version;
import com.botmaker.dashboard.umbrella.ReleaseLauncher;
import com.botmaker.dashboard.umbrella.ReleaseRun;
import com.botmaker.dashboard.umbrella.ReleaseSpec;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two Release-tab bugs of 2026-09-16, as clicks: a level picked on an unticked row changed nothing while
 * Execute stayed armed, and a refusal was one count in the status line.
 */
class ReleaseTabTest extends FxHeadless {

    @TempDir
    Path umbrella;

    private ReleaseTab tab;
    private List<String> refusals = List.of();

    private final class Fake implements ReleaseTab.Backend {
        @Override
        public ReleaseRun preview(Path root, ReleaseSpec spec, Consumer<String> line) {
            line.accept("Release plan:");
            // No module requested of the library, so it reads no git: an empty, clean plan.
            Plan plan = Plan.decide(root, Map.of(), false);
            return new ReleaseRun(false, "Release plan:\n", Optional.of(plan), refusals, Optional.empty(), true);
        }

        @Override
        public Optional<Version> latest(Path root, String module) {
            return Optional.of(new Version(1, 1, 0));
        }

        @Override
        public ReleaseLauncher.Launched launch(Path root, ReleaseSpec spec) throws IOException {
            throw new IOException("no release in a test");
        }

        @Override
        public Optional<ReleaseLauncher.Job> latestJob(Path root) {
            return Optional.empty();
        }
    }

    private void open() {
        interact(() -> {
            tab = new ReleaseTab(umbrella, new Fake());
            show(tab);
        });
    }

    private void previewAndWait() throws Exception {
        clickOn(tab.previewButton());
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> !tab.previewButton().isDisabled());
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The segment for {@code level} in the row of {@code module}. */
    private Node segment(String module, String level) {
        return lookup(".segment--" + level).queryAll().stream()
                .filter(node -> {
                    Node at = node;
                    while (at != null && !(at instanceof TableCell<?, ?>)) {
                        at = at.getParent();
                    }
                    return at instanceof TableCell<?, ?> cell && cell.getTableRow() != null
                            && cell.getTableRow().getItem() instanceof ReleaseTab.Row row
                            && row.getModule().equals(module);
                })
                .findFirst().orElseThrow(() -> new AssertionError("no " + level + " segment for " + module));
    }

    private ReleaseTab.Row row(String module) {
        return tab.rows().stream().filter(r -> r.getModule().equals(module)).findFirst().orElseThrow();
    }

    @Test
    void everyModuleHasARowBeforeAnyPreview() {
        open();

        assertEquals(11, tab.rows().size());
        assertEquals("botmaker-pilot", tab.rows().getFirst().getModule(), "tag order: the pilot first");
        assertEquals("botmaker-studio", tab.rows().getLast().getModule(), "and Studio last");
        assertTrue(tab.executeButton().isDisabled());
    }

    @Test
    void pickingALevelOnAnUntickedRowTicksItAndDisarmsExecute() throws Exception {
        open();
        previewAndWait();
        assertFalse(tab.executeButton().isDisabled(), "a clean preview of these flags arms Execute");
        assertFalse(row("botmaker-sdk").selectedProperty().get());

        clickOn(segment("botmaker-sdk", "minor"));

        assertTrue(row("botmaker-sdk").selectedProperty().get(), "choosing a level asks for the row");
        assertTrue(tab.executeButton().isDisabled(), "the flags differ from the preview's");
        String command = lookup(".command-line").queryAs(TextField.class).getText();
        assertTrue(command.endsWith("--all patch --sdk minor"), command);
        assertEquals("1.1.0 → 1.2.0", row("botmaker-sdk").targetProperty().get());
    }

    @Test
    void clickingTheLevelAlreadyChosenStillTicksTheRow() throws Exception {
        open();
        clickOn(segment("botmaker-cli", "patch"));

        assertTrue(row("botmaker-cli").selectedProperty().get());
    }

    @Test
    void aRefusalIsABannerInTheGatesOwnWords() throws Exception {
        refusals = List.of("botmaker-sdk: the newest finished CI run on main is failure (da3222e).",
                "botmaker-studio: CHANGELOG.md has no section for v1.2.0");
        open();
        previewAndWait();

        assertTrue(tab.banner().isVisible());
        List<String> words = tab.banner().getChildren().stream().map(n -> ((Label) n).getText()).toList();
        assertEquals("2 gate(s) refused — Execute stays dead", words.getFirst());
        assertEquals(refusals, words.subList(1, words.size()));
        assertTrue(tab.executeButton().isDisabled());

        // The next preview clears it.
        refusals = List.of();
        previewAndWait();
        assertFalse(tab.banner().isVisible());
        assertFalse(tab.executeButton().isDisabled());
    }
}
