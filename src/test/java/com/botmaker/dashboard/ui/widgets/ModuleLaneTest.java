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
                "https://github.com/LiQiyeDev/botmaker-plugin-host/actions?query=branch%3Av0.1.0",
                "https://jitpack.io/#LiQiyeDev/botmaker-plugin-host/v0.1.0",
                "https://github.com/LiQiyeDev/botmaker-plugin-host/releases/tag/v0.1.0"), opened);
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

    @Test
    void aModuleNeverReachedIsDimmedThroughout() {
        draw(ReleaseFixtures.crashed().lanes().get(2));

        for (Step step : Step.values()) {
            assertTrue(nodeIs(step, NodeState.SKIPPED), step.name());
        }
        assertFalse(lane.details().isVisible());
    }
}
