package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.ui.FxHeadless;
import com.botmaker.dashboard.umbrella.ReleaseFixtures;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.NodeState;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Step;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One lane drawn for each state a module passes through: the node classes, the connectors, the error. */
class ModuleLaneTest extends FxHeadless {

    private final List<String> opened = new ArrayList<>();
    private ModuleLane lane;

    private void draw(ReleaseProgress.Lane model) {
        interact(() -> {
            lane = new ModuleLane(opened::add);
            show(lane);
            lane.show(model);
        });
    }

    private boolean nodeIs(Step step, NodeState state) {
        StackPane node = lane.node(step);
        return node.getStyleClass().contains(state.styleClass())
                && node.getStyleClass().stream().filter(c -> c.startsWith("step--")).count() == 1;
    }

    @Test
    void aModuleWaitingOnJitpackPulsesItsJitpackNode() {
        draw(ReleaseFixtures.midChain().lanes().get(1));

        assertTrue(nodeIs(Step.COMMIT, NodeState.OK));
        assertTrue(nodeIs(Step.TAG, NodeState.OK));
        assertTrue(nodeIs(Step.JITPACK, NodeState.RUNNING));
        assertTrue(nodeIs(Step.ACTIONS, NodeState.PENDING));
        assertTrue(lane.connectorAfter(Step.COMMIT).getStyleClass().contains("step-connector--filled"));
        assertTrue(lane.connectorAfter(Step.TAG).getStyleClass().contains("step-connector--filled"));
        assertFalse(lane.connectorAfter(Step.JITPACK).getStyleClass().contains("step-connector--filled"));
        assertEquals("v0.1.0", lane.chip().getText());
        assertTrue(lane.pulsing());
        assertFalse(lane.details().isVisible());
    }

    @Test
    void aHiddenLaneDoesNotAnimate() {
        draw(ReleaseFixtures.midChain().lanes().get(1));

        interact(() -> lane.setAnimated(false));
        assertFalse(lane.pulsing());
        interact(() -> lane.setAnimated(true));
        assertTrue(lane.pulsing());
    }

    @Test
    void aFailedLaneOpensToItsErrorAndItsPages() {
        draw(ReleaseFixtures.crashed().lanes().get(1));

        assertTrue(nodeIs(Step.COMMIT, NodeState.OK));
        assertTrue(nodeIs(Step.TAG, NodeState.FAILED));
        assertTrue(nodeIs(Step.JITPACK, NodeState.SKIPPED));
        assertTrue(lane.getStyleClass().contains("module-lane--failed"));
        assertTrue(lane.details().isVisible());
        assertTrue(lane.errorText().getText().contains("pushing v0.1.0 failed"));
        assertFalse(lane.pulsing());

        // The error is what gets pasted into an issue, and a scrolling read-only TextArea is the one place
        // it cannot be selected out of — so it is copied with its module and tag above it.
        clickOn("Copy error");
        java.util.concurrent.atomic.AtomicReference<String> copied = new java.util.concurrent.atomic
                .AtomicReference<>();
        interact(() -> copied.set(javafx.scene.input.Clipboard.getSystemClipboard().getString()));
        assertTrue(copied.get().startsWith("botmaker-plugin-host v0.1.0 — "), copied.get());
        assertTrue(copied.get().contains("pushing v0.1.0 failed"), copied.get());

        clickOn("Open run");
        clickOn("Open JitPack build");
        clickOn(lane.chip());
        assertEquals(List.of(
                "https://github.com/BotMakerDev/botmaker-plugin-host/actions?query=branch%3Av0.1.0",
                // JitPack keeps the old owner: the coordinates did not move with the repositories.
                "https://jitpack.io/#LiQiyeDev/botmaker-plugin-host/v0.1.0",
                "https://github.com/BotMakerDev/botmaker-plugin-host/releases/tag/v0.1.0"), opened);
    }

    @Test
    void aRedrawReplacesTheStateRatherThanAddingOne() {
        draw(ReleaseFixtures.midChain().lanes().get(1));
        interact(() -> lane.show(ReleaseFixtures.completeWithFailures().lanes().get(1)));

        assertTrue(nodeIs(Step.JITPACK, NodeState.FAILED));
        assertTrue(nodeIs(Step.ACTIONS, NodeState.FAILED));
        assertFalse(lane.pulsing());
        assertTrue(lane.errorText().getText().startsWith("actions: CI: failure — https://"));
    }

    /**
     * Every lane with a tag offers its run, not only a failed one.
     *
     * <p>The link lived in a failed lane's details panel, which is the one case where the operator already
     * knows something is wrong. What was missing was the ordinary question — <i>what did this tag's CI
     * do?</i> — on a lane that is green or still running.
     */
    @Test
    void everyTaggedLaneOffersItsActionsRun() {
        draw(ReleaseFixtures.midChain().lanes().get(1));

        assertTrue(lane.actionsChip().isVisible());
        clickOn(lane.actionsChip());
        assertEquals(List.of("https://github.com/BotMakerDev/botmaker-plugin-host/actions?query=branch%3Av0.1.0"),
                opened, "with no polled run, the repository's runs filtered by the tag");
    }

    /** A polled run is one click, not one page away. */
    @Test
    void aLaneThatKnowsItsRunGoesStraightToIt() {
        ReleaseProgress.Lane model = ReleaseFixtures.midChain().lanes().get(1);
        draw(new ReleaseProgress.Lane(model.module(), model.tag(), model.stage(), model.steps(),
                model.errors(), model.elapsed(), "https://example.invalid/runs/7"));

        clickOn(lane.actionsChip());
        assertEquals(List.of("https://example.invalid/runs/7"), opened);
    }

    @Test
    void aModuleNeverReachedIsDimmedThroughout() {
        draw(ReleaseFixtures.crashed().lanes().get(2));

        for (Step step : Step.values()) {
            assertTrue(nodeIs(step, NodeState.SKIPPED), step.name());
        }
        assertFalse(lane.details().isVisible());
    }
}
