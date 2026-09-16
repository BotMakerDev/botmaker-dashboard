package com.botmaker.dashboard.ui.widgets;

import com.botmaker.dashboard.ui.FxHeadless;
import com.botmaker.dashboard.umbrella.ReleaseFixtures;
import com.botmaker.dashboard.umbrella.ReleaseProgress;
import com.botmaker.dashboard.umbrella.ReleaseProgress.Filter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four tiles' words and colours, and a tile click narrowing the board's lanes. */
class SummaryTilesTest extends FxHeadless {

    private ReleaseBoard board;
    private LiveBadge badge;

    private void draw(ReleaseProgress progress) {
        interact(() -> {
            badge = new LiveBadge();
            board = new ReleaseBoard(url -> new ArrayList<String>().add(url));
            show(board);
            board.show(progress);
            badge.show(progress);
        });
    }

    @Test
    void midChainTheTilesCountWhatTheLanesSay() {
        draw(ReleaseFixtures.midChain());
        SummaryTiles tiles = board.tiles();

        assertEquals("2/3", tiles.tile(Filter.TAG).number.getText());
        assertEquals("1/2", tiles.tile(Filter.JITPACK).number.getText());
        assertEquals("JitPack · 1 waiting", tiles.tile(Filter.JITPACK).caption.getText());
        assertEquals("0/3", tiles.tile(Filter.ACTIONS).number.getText());
        assertEquals("8:20", tiles.tile(Filter.ALL).number.getText());
        assertFalse(tiles.tile(Filter.TAG).getStyleClass().contains("tile--failed"));

        assertTrue(badge.isVisible());
        assertEquals("● Releasing — 2/3", badge.getText());
    }

    @Test
    void aTileTurnsRedOnAnyFailureItCounts() {
        draw(ReleaseFixtures.completeWithFailures());
        SummaryTiles tiles = board.tiles();

        assertTrue(tiles.tile(Filter.JITPACK).getStyleClass().contains("tile--failed"));
        assertTrue(tiles.tile(Filter.ACTIONS).getStyleClass().contains("tile--failed"));
        assertEquals("Actions · 0 pending · 1 failed", tiles.tile(Filter.ACTIONS).caption.getText());
        assertFalse(tiles.tile(Filter.TAG).getStyleClass().contains("tile--failed"));
        assertFalse(badge.isVisible(), "the run is over, so the header says nothing");
    }

    @Test
    void aStoppedRunReddensTheTagTile() {
        draw(ReleaseFixtures.crashed());

        assertTrue(board.tiles().tile(Filter.TAG).getStyleClass().contains("tile--failed"));
        assertTrue(board.tiles().tile(Filter.ALL).getStyleClass().contains("tile--failed"));
    }

    @Test
    void clickingATileFiltersTheLanesAndClickingItAgainShowsThemAll() {
        draw(ReleaseFixtures.midChain());
        assertEquals(3, board.visibleLanes());

        clickOn(board.tiles().tile(Filter.JITPACK));
        assertEquals(Filter.JITPACK, board.tiles().selected());
        assertEquals(1, board.visibleLanes());
        assertTrue(board.lane("botmaker-plugin-host").isVisible());
        assertTrue(board.tiles().tile(Filter.JITPACK).getStyleClass().contains("tile--selected"));

        // A new reading keeps the operator's filter.
        interact(() -> board.show(ReleaseFixtures.midChain()));
        assertEquals(1, board.visibleLanes());

        clickOn(board.tiles().tile(Filter.JITPACK));
        assertEquals(Filter.ALL, board.tiles().selected());
        assertEquals(3, board.visibleLanes());
    }
}
