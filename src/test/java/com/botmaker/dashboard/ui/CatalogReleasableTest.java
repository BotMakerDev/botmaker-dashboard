package com.botmaker.dashboard.ui;

import com.botmaker.cli.release.Module;
import com.botmaker.dashboard.github.Catalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which listed entry the {@code Update template…} button is offered for — a rule, so it is tested without a
 * window.
 */
class CatalogReleasableTest {

    private static Catalog.Entry bot(String id, String repo, boolean template) {
        return new Catalog.Entry(Catalog.Kind.BOT, "bots/" + id.replace('/', '-') + ".json", "sha", id,
                id, "", List.of(), template, "{}", true, repo, null);
    }

    @Test
    void theWorkedBotIsTheOneEntryThatIsAlsoAReleaseModule() {
        assertEquals(Optional.of(Module.GAMEBOT),
                CatalogTab.releasable(bot("BotMakerDev/botmaker-gamebot", "BotMakerDev/botmaker-gamebot",
                        true)));
    }

    @Test
    void somebodyElsesTemplateGetsNoButton() {
        // The `template` tag is a gallery idea any submission can claim, so it is not what is matched: the
        // repository name is, against the release library's own module list.
        assertTrue(CatalogTab.releasable(bot("someone/my-template", "someone/my-template", true)).isEmpty());
    }

    @Test
    void theOwnerIsNotPartOfTheMatchBecauseTheReleaseActsOnTheCheckout() {
        // Deliberate: what the button releases is the `botmaker-gamebot` submodule of the umbrella in use,
        // never the repository the entry names. So the question is "which module of this checkout is this
        // row about", and the owner belongs to the entry rather than to the answer. The project's own
        // repositories do not even agree on one owner — the gallery's entries are BotMakerDev's while the
        // registry is LiQiyeDev's — so an owner in the match would be a second list to keep.
        assertEquals(Optional.of(Module.GAMEBOT),
                CatalogTab.releasable(bot("x/botmaker-gamebot", "x/botmaker-gamebot", true)));
    }

    @Test
    void anOrdinaryBotAndAnUnreadableEntryGetNothing() {
        assertTrue(CatalogTab.releasable(bot("someone/their-bot", "someone/their-bot", false)).isEmpty());
        assertTrue(CatalogTab.releasable(bot("broken", "", false)).isEmpty());
        assertTrue(CatalogTab.releasable(null).isEmpty());
    }
}
