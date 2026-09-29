package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.umbrella.ChangelogEdit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small rules the tabs decide by, without a window: unsaved text, and stale CI answers. */
class TabRulesTest {

    private static ChangelogEdit.Doc doc(String unreleased) {
        return new ChangelogEdit.Doc(Path.of("CHANGELOG.md"), "", true, unreleased, true, false);
    }

    @Test
    void textTheFileDoesNotHoldIsUnsaved() {
        assertFalse(ChangelogTab.unsaved(doc("### Added\n- a\n"), "### Added\n- a"), "stripped, as shown");
        assertTrue(ChangelogTab.unsaved(doc("### Added\n- a\n"), "### Added\n- a\n- b"));
        assertFalse(ChangelogTab.unsaved(null, "anything"), "nothing opened yet");
        assertFalse(ChangelogTab.unsaved(new ChangelogEdit.Doc(Path.of("x"), "", false, "", false, false), "x"),
                "a module with no CHANGELOG.md has nothing to lose");
    }

    @Test
    void aCiAnswerFromAnOlderScanIsDropped() {
        assertTrue(ModulesTab.current(3, 3));
        assertFalse(ModulesTab.current(2, 3));
    }
}
