package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Filter;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

import java.util.function.Consumer;

/**
 * Four tiles across the top of a release: tagged, JitPack, Actions, elapsed.
 *
 * <p>Each is a big number, a caption and a thin arc for the fraction done. A tile turns red when anything it
 * counts has failed, and clicking one narrows the lanes below to the modules that step still needs looking
 * at — clicking it again shows every lane. The numbers are {@link ReleaseProgress#tiles()}'s; this class
 * counts nothing.
 */
public final class SummaryTiles extends HBox {

    private final Tile tagged = new Tile("tile--tagged", Filter.TAG);
    private final Tile jitpack = new Tile("tile--jitpack", Filter.JITPACK);
    private final Tile actions = new Tile("tile--actions", Filter.ACTIONS);
    private final Tile elapsed = new Tile("tile--elapsed", Filter.ALL);

    private Consumer<Filter> onFilter = filter -> {
    };
    private Filter selected = Filter.ALL;

    public SummaryTiles() {
        super(10);
        getStyleClass().add("summary-tiles");
        elapsed.arcBox.setVisible(false);
        elapsed.arcBox.setManaged(false);
        getChildren().addAll(tagged, jitpack, actions, elapsed);
    }

    public void setOnFilter(Consumer<Filter> onFilter) {
        this.onFilter = onFilter;
    }

    public Filter selected() {
        return selected;
    }

    public void show(ReleaseProgress progress) {
        ReleaseProgress.Tiles t = progress.tiles();
        tagged.set(t.tagged() + "/" + t.total(), "Tagged", t.total() == 0 ? 0 : (double) t.tagged() / t.total(),
                t.tagFailed());
        jitpack.set(t.jitpackOk() + "/" + t.jitpackTotal(),
                "JitPack · " + t.jitpackWaiting() + " waiting" + failed(t.jitpackFailed()),
                t.jitpackTotal() == 0 ? 0 : (double) t.jitpackOk() / t.jitpackTotal(), t.jitpackFailed() > 0);
        actions.set(t.actionsOk() + "/" + t.actionsTotal(),
                "Actions · " + t.actionsPending() + " pending" + failed(t.actionsFailed()),
                t.actionsTotal() == 0 ? 0 : (double) t.actionsOk() / t.actionsTotal(), t.actionsFailed() > 0);
        elapsed.set(ReleaseProgress.clock(t.elapsed()), progress.phase().label(), 0,
                progress.phase() == ReleaseProgress.Phase.STOPPED || progress.phase() == ReleaseProgress.Phase.DIED);
    }

    /** Selects a filter as a click would, without telling the listener — for a board restoring its state. */
    public void select(Filter filter) {
        selected = filter;
        for (Tile tile : new Tile[] {tagged, jitpack, actions}) {
            tile.getStyleClass().remove("tile--selected");
            if (tile.filter == filter) {
                tile.getStyleClass().add("tile--selected");
            }
        }
    }

    Tile tile(Filter filter) {
        return switch (filter) {
            case TAG -> tagged;
            case JITPACK -> jitpack;
            case ACTIONS -> actions;
            case ALL -> elapsed;
        };
    }

    private static String failed(int count) {
        return count == 0 ? "" : " · " + count + " failed";
    }

    final class Tile extends HBox {
        private final Filter filter;
        final Label number = new Label();
        final Label caption = new Label();
        private final Arc fill = new Arc(16, 16, 13, 13, 90, 0);
        private final StackPane arcBox;

        Tile(String kind, Filter filter) {
            super(10);
            this.filter = filter;
            getStyleClass().addAll("tile", kind);
            setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(this, Priority.ALWAYS);
            setMaxWidth(Double.MAX_VALUE);

            Arc track = new Arc(16, 16, 13, 13, 90, 360);
            track.setType(ArcType.OPEN);
            track.getStyleClass().add("tile-arc-track");
            fill.setType(ArcType.OPEN);
            fill.getStyleClass().add("tile-arc");
            arcBox = new StackPane(track, fill);
            arcBox.setMinSize(32, 32);
            arcBox.setMaxSize(32, 32);

            number.getStyleClass().add("tile-number");
            caption.getStyleClass().add("tile-caption");
            getChildren().addAll(arcBox, new VBox(0, number, caption));

            setOnMouseClicked(e -> {
                Filter next = filter == Filter.ALL || selected == filter ? Filter.ALL : filter;
                select(next);
                onFilter.accept(next);
            });
        }

        void set(String value, String text, double fraction, boolean failed) {
            number.setText(value);
            caption.setText(text);
            // Clockwise from twelve o'clock; JavaFX measures a positive length anticlockwise.
            fill.setLength(-360 * Math.max(0, Math.min(1, fraction)));
            getStyleClass().remove("tile--failed");
            if (failed) {
                getStyleClass().add("tile--failed");
            }
        }
    }
}
