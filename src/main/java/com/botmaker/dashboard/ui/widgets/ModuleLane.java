package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.Links;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Lane;
import com.botmaker.dashboard.umbrella.ReleaseProgress.NodeState;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Step;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One module's line in a release: its name, its tag, and a stepper — {@code Commit ● — Tag ● — JitPack ● —
 * Actions ●}.
 *
 * <p>A node is hollow while pending, pulses while running, and is filled green with a tick, red with a cross,
 * or dimmed with a dash. The connector after a node fills once that node has settled. A lane with a failed
 * node opens to the error text the release log holds for that module, with the two pages that explain it.
 *
 * <p><b>It draws a {@link Lane} and decides nothing.</b> Every state here was worked out by
 * {@link ReleaseProgress}, over the log's own words, where a test can reach it without a window.
 *
 * <p>The pulse is the one moving part, and it runs only while the lane is running <i>and</i> the owner says the
 * lane can be seen ({@link #setAnimated}) — an animation on a hidden tab still repaints.
 */
public final class ModuleLane extends VBox {

    private final Consumer<String> open;

    private final Label module = new Label();
    private final Hyperlink chip = new Hyperlink();
    private final Label stage = new Label();
    private final Label elapsed = new Label();
    private final Map<Step, StackPane> nodes = new EnumMap<>(Step.class);
    private final Map<Step, Label> marks = new EnumMap<>(Step.class);
    private final Map<Step, Region> rings = new EnumMap<>(Step.class);
    /** The connector <i>after</i> each step but the last. */
    private final Map<Step, Region> connectors = new EnumMap<>(Step.class);

    private final VBox details = new VBox(6);
    private final TextArea errorText = new TextArea();

    private final Timeline pulse = new Timeline();
    private boolean animated = true;
    private Lane lane;

    /** @param open how a link is opened — {@code Browse.open}, or a recorder in a test */
    public ModuleLane(Consumer<String> open) {
        super(6);
        this.open = open;
        getStyleClass().add("module-lane");

        module.getStyleClass().add("lane-module");
        module.setMinWidth(190);
        chip.getStyleClass().add("tag-chip");
        chip.setOnAction(e -> {
            if (lane != null) {
                open.accept(Links.release(lane.module(), lane.tag()));
            }
        });
        stage.getStyleClass().add("lane-stage");
        elapsed.getStyleClass().add("lane-elapsed");

        HBox stepper = new HBox(6);
        stepper.setAlignment(Pos.CENTER_LEFT);
        stepper.getStyleClass().add("stepper");
        Step[] steps = Step.values();
        for (int i = 0; i < steps.length; i++) {
            Step step = steps[i];
            Region ring = new Region();
            ring.getStyleClass().add("step-ring");
            ring.setMouseTransparent(true);
            Label mark = new Label();
            mark.getStyleClass().add("step-mark");
            StackPane node = new StackPane(ring, mark);
            node.getStyleClass().add("step-node");
            node.setMinSize(18, 18);
            node.setMaxSize(18, 18);
            Label name = new Label(step.label());
            name.getStyleClass().add("step-label");
            nodes.put(step, node);
            marks.put(step, mark);
            rings.put(step, ring);
            stepper.getChildren().addAll(name, node);
            if (i + 1 < steps.length) {
                Region connector = new Region();
                connector.getStyleClass().add("step-connector");
                connector.setMinSize(22, 2);
                connector.setMaxSize(22, 2);
                connectors.put(step, connector);
                stepper.getChildren().add(connector);
            }
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, module, chip, stepper, spacer, stage, elapsed);
        header.setAlignment(Pos.CENTER_LEFT);

        errorText.setEditable(false);
        errorText.setWrapText(true);
        errorText.setPrefRowCount(6);
        errorText.getStyleClass().addAll("error-text", "lane-error");
        Button openRun = new Button("Open run");
        openRun.setOnAction(e -> {
            if (lane != null) {
                open.accept(Links.actions(lane.module(), lane.tag()));
            }
        });
        Button openJitpack = new Button("Open JitPack build");
        openJitpack.setOnAction(e -> {
            if (lane != null) {
                open.accept(Links.jitpack(lane.module(), lane.tag()));
            }
        });
        // The error is the thing an operator pastes into an issue or a terminal, and a read-only TextArea
        // that scrolls is the one place it cannot be selected out of comfortably.
        CopyButton copy = new CopyButton("Copy error", () -> lane == null ? "" : copyText());
        details.getChildren().addAll(errorText, new HBox(8, openRun, openJitpack, copy));
        details.getStyleClass().add("lane-details");
        showDetails(false);

        getChildren().addAll(header, details);

        pulse.setCycleCount(Animation.INDEFINITE);
    }

    public void show(Lane lane) {
        this.lane = lane;
        module.setText(lane.module());
        chip.setText(lane.tag());
        stage.setText(lane.stage());
        elapsed.setText(lane.elapsed().map(ReleaseProgress::clock).orElse(""));

        Step[] steps = Step.values();
        for (Step step : steps) {
            NodeState state = lane.state(step);
            StackPane node = nodes.get(step);
            for (NodeState any : NodeState.values()) {
                node.getStyleClass().remove(any.styleClass());
            }
            node.getStyleClass().add(state.styleClass());
            marks.get(step).setText(switch (state) {
                case OK -> "✓";
                case FAILED -> "✕";
                case SKIPPED -> "–";
                case PENDING, RUNNING -> "";
            });
            Region connector = connectors.get(step);
            if (connector != null) {
                connector.getStyleClass().remove("step-connector--filled");
                if (state.settled()) {
                    connector.getStyleClass().add("step-connector--filled");
                }
            }
        }

        getStyleClass().remove("module-lane--failed");
        if (lane.failed()) {
            getStyleClass().add("module-lane--failed");
        }
        List<String> errors = lane.errors();
        errorText.setText(errors.isEmpty() ? "Failed — the release log holds no error text for this module."
                : String.join("\n\n", errors));
        showDetails(lane.failed());
        restartPulse();
    }

    /**
     * What *Copy error* puts on the clipboard: the module, its tag, its stage, then the error text.
     *
     * <p>The heading matters as much as the text — an error pasted somewhere else has to say which module
     * and which tag it belongs to, and the lane says that on screen where the clipboard could not.
     */
    private String copyText() {
        return lane.module() + " " + lane.tag() + " — " + lane.stage() + "\n\n" + errorText.getText();
    }

    /** Whether the running node may pulse. The owner turns it off while the lane cannot be seen. */
    public void setAnimated(boolean animated) {
        this.animated = animated;
        restartPulse();
    }

    boolean pulsing() {
        return pulse.getStatus() == Animation.Status.RUNNING;
    }

    StackPane node(Step step) {
        return nodes.get(step);
    }

    Region connectorAfter(Step step) {
        return connectors.get(step);
    }

    Hyperlink chip() {
        return chip;
    }

    VBox details() {
        return details;
    }

    TextArea errorText() {
        return errorText;
    }

    private void showDetails(boolean show) {
        details.setVisible(show);
        details.setManaged(show);
    }

    private void restartPulse() {
        pulse.stop();
        pulse.getKeyFrames().clear();
        for (Region ring : rings.values()) {
            ring.setScaleX(1);
            ring.setScaleY(1);
            ring.setOpacity(1);
        }
        if (!animated || lane == null) {
            return;
        }
        for (Step step : Step.values()) {
            if (lane.state(step) == NodeState.RUNNING) {
                Region ring = rings.get(step);
                pulse.getKeyFrames().addAll(
                        new KeyFrame(Duration.ZERO,
                                new KeyValue(ring.scaleXProperty(), 1),
                                new KeyValue(ring.scaleYProperty(), 1),
                                new KeyValue(ring.opacityProperty(), 0.9)),
                        new KeyFrame(Duration.millis(1100),
                                new KeyValue(ring.scaleXProperty(), 1.7),
                                new KeyValue(ring.scaleYProperty(), 1.7),
                                new KeyValue(ring.opacityProperty(), 0)));
            }
        }
        if (!pulse.getKeyFrames().isEmpty()) {
            pulse.play();
        }
    }
}
