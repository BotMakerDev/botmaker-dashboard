package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Lane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;

import java.time.Duration;
import java.util.List;

/**
 * A thin bar under the tiles: one segment per module that has started, as wide as the time it took.
 *
 * <p>What it is for is the one fact a list of lanes hides — <i>where the minutes went</i>. A JitPack wait that
 * ate eight minutes is the widest segment on the bar at a glance, and a module that is still running grows
 * while you watch. A module with no elapsed time yet has no segment; a started one gets a sliver at least, so
 * a quick module is still visible.
 */
public final class ReleaseTimeline extends Region {

    /** The narrowest a started module's segment is drawn, as a share of the whole. */
    private static final double MIN_SHARE = 0.01;

    private List<Lane> lanes = List.of();

    public ReleaseTimeline() {
        getStyleClass().add("release-timeline");
        setMinHeight(8);
        setPrefHeight(8);
        setMaxHeight(8);
    }

    public void show(List<Lane> lanes) {
        this.lanes = lanes.stream().filter(lane -> lane.elapsed().isPresent()).toList();
        getChildren().clear();
        for (Lane lane : this.lanes) {
            Region segment = new Region();
            segment.getStyleClass().addAll("timeline-segment", "timeline-segment--" + (lane.failed() ? "failed"
                    : lane.running() ? "running" : "done"));
            Tooltip.install(segment, new Tooltip(lane.module() + " · "
                    + ReleaseProgress.clock(lane.elapsed().orElse(Duration.ZERO))));
            getChildren().add(segment);
        }
        requestLayout();
    }

    @Override
    protected void layoutChildren() {
        double total = lanes.stream().mapToDouble(ReleaseTimeline::seconds).sum();
        double shares = lanes.stream().mapToDouble(lane -> Math.max(MIN_SHARE, seconds(lane) / Math.max(1, total)))
                .sum();
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double x = snappedLeftInset();
        for (int i = 0; i < lanes.size(); i++) {
            double share = Math.max(MIN_SHARE, seconds(lanes.get(i)) / Math.max(1, total)) / shares;
            double w = Math.max(1, width * share - 1);
            getChildren().get(i).resizeRelocate(x, snappedTopInset(), w, height);
            x += width * share;
        }
    }

    private static double seconds(Lane lane) {
        return lane.elapsed().map(Duration::toMillis).orElse(0L) / 1000.0;
    }
}
