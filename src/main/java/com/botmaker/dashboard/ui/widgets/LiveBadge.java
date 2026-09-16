package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.umbrella.ReleaseProgress;
import javafx.scene.control.Label;

/**
 * {@code ● Releasing — 4/10}, in the Release tab's header, for as long as a release runs.
 *
 * <p>A release takes long enough that the operator will look at another tab meanwhile, and the other tabs have
 * no way to say one is going. So the tab's own header does. It disappears when the run ends; what the run
 * ended as is on the board.
 */
public final class LiveBadge extends Label {

    public LiveBadge() {
        getStyleClass().add("live-badge");
        show(null);
    }

    /** @param progress the latest model, or {@code null} when no release is being watched */
    public void show(ReleaseProgress progress) {
        boolean live = progress != null && progress.phase().running();
        setVisible(live);
        setManaged(live);
        getStyleClass().remove("live-badge--failed");
        if (!live) {
            setText("");
            return;
        }
        ReleaseProgress.Tiles tiles = progress.tiles();
        setText("● " + progress.phase().label()
                + (tiles.total() == 0 ? "" : " — " + tiles.tagged() + "/" + tiles.total()));
        if (progress.failed()) {
            getStyleClass().add("live-badge--failed");
        }
    }
}
