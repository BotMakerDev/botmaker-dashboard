package com.botmaker.dashboard.umbrella;

import java.util.regex.Pattern;

/**
 * The one question this window asks of a {@code CHANGELOG.md}: is there prose for the release that has not
 * been cut yet?
 *
 * <p>A changelog is written under {@code ## [Unreleased]} and the release <i>stamps</i> the number onto it
 * ({@code stamp_changelog}), because the version is not knowable while the prose is being written — it is
 * what the decide pass computes. So a module with commits since its tag and no {@code [Unreleased]} section
 * is a module whose release {@code check_changelog} will refuse, and that is worth seeing here rather than
 * mid-release with other modules already tagged.
 *
 * <p><b>This is a report, not the gate.</b> {@code check_changelog} is {@code release.sh}'s, it exempts
 * {@code botmaker-pilot}, and it also accepts a section already naming the exact version being cut. Nothing
 * here refuses anything; a column that says "no [Unreleased]" is telling the maintainer what the gate will
 * say.
 */
public final class Changelog {

    /** The heading, however it is spaced. Case-insensitive: the gate's own extractor is too. */
    private static final Pattern UNRELEASED =
            Pattern.compile("(?im)^\\s{0,3}##\\s*\\[\\s*unreleased\\s*]");

    private Changelog() {
    }

    public static boolean hasUnreleased(String text) {
        return UNRELEASED.matcher(text).find();
    }
}
