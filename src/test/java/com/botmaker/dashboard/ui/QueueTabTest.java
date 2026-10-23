package com.botmaker.dashboard.ui;

import javafx.scene.control.ButtonBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Queue is read-only: the catalog's check decides and its merge job merges, so no review button exists. */
class QueueTabTest extends FxHeadless {

    @Test
    void itOffersNoReview() {
        interact(() -> show(new QueueTab(null, null)));
        List<String> buttons = lookup(".button").queryAll().stream()
                .filter(ButtonBase.class::isInstance).map(n -> ((ButtonBase) n).getText()).toList();
        assertEquals(List.of("Refresh", "Open on GitHub"), buttons);
    }
}
