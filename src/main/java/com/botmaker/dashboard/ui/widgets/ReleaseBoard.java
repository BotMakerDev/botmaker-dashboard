package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Filter;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Lane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A release on one screen: the headline, the four tiles, the timeline and a lane per module.
 *
 * <p>It holds one piece of state of its own — which tile is selected — because that is the operator's choice
 * and not a fact about the release; everything else is redrawn from each {@link ReleaseProgress} it is shown.
 * Lanes are kept per module and reused, so an expanded error or a running pulse does not flicker every second.
 */
public final class ReleaseBoard extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final Consumer<String> open;
    private final Label headline = new Label();
    private final SummaryTiles tiles = new SummaryTiles();
    private final ReleaseTimeline timeline = new ReleaseTimeline();
    private final VBox laneBox = new VBox(8);
    private final Label empty = new Label();
    private final Map<String, ModuleLane> lanes = new LinkedHashMap<>();

    private ReleaseProgress progress;
    private boolean animated = true;

    public ReleaseBoard(Consumer<String> open) {
        super(10);
        this.open = open;
        getStyleClass().add("release-board");
        headline.getStyleClass().add("placeholder-title");
        empty.getStyleClass().add("placeholder-body");
        empty.setWrapText(true);

        tiles.setOnFilter(filter -> redrawLanes());

        ScrollPane scroll = new ScrollPane(laneBox);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(headline, tiles, timeline, empty, scroll);
    }

    public void show(ReleaseProgress progress) {
        this.progress = progress;
        headline.setText(progress.phase().label() + " · started " + TIME.format(progress.started()));
        tiles.show(progress);
        timeline.show(progress.lanes());
        boolean noLanes = progress.lanes().isEmpty();
        empty.setText(noLanes ? switch (progress.phase()) {
            case DECIDING -> "Deciding and running the gates — nothing is tagged yet. The lanes appear when the "
                    + "release log is written, just before the first tag.";
            case REFUSED -> "A gate refused, so nothing was tagged. Its words are in the output below.";
            default -> "No module was tagged. The output below says why.";
        } : "");
        empty.setVisible(noLanes);
        empty.setManaged(noLanes);
        redrawLanes();
    }

    /** Whether running nodes may pulse — off while the tab cannot be seen. */
    public void setAnimated(boolean animated) {
        this.animated = animated;
        lanes.values().forEach(lane -> lane.setAnimated(animated));
    }

    SummaryTiles tiles() {
        return tiles;
    }

    ModuleLane lane(String module) {
        return lanes.get(module);
    }

    /** The lanes currently shown under the selected tile. */
    long visibleLanes() {
        return laneBox.getChildren().stream().filter(node -> node.isVisible()).count();
    }

    private void redrawLanes() {
        if (progress == null) {
            return;
        }
        Filter filter = tiles.selected();
        laneBox.getChildren().clear();
        for (Lane lane : progress.lanes()) {
            ModuleLane view = lanes.computeIfAbsent(lane.module(), module -> {
                ModuleLane created = new ModuleLane(open);
                created.setAnimated(animated);
                return created;
            });
            view.show(lane);
            boolean shown = lane.shownUnder(filter);
            view.setVisible(shown);
            view.setManaged(shown);
            laneBox.getChildren().add(view);
        }
    }
}
