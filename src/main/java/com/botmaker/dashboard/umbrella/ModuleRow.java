package com.botmaker.dashboard.umbrella;

import java.util.List;
import java.util.Optional;

/**
 * One module, as the Modules tab shows it: where its tag is, whether HEAD has moved past it, what
 * {@code release.sh} would decide about that movement, where its pins sit, and whether its changelog is
 * ready.
 *
 * <p>Every field is either a fact of the checkout (git, a file's contents) or the script's own verdict
 * quoted back. Nothing here is computed from a rule this module owns — see {@link ReleasePlan}.
 */
public record ModuleRow(
        String name,
        Optional<String> latestTag,
        int ahead,
        boolean dirty,
        Optional<ReleasePlan.Verdict> plan,
        List<DepsEnv.Pin> pins,
        ChangelogState changelog) {

    /** Whether this module's {@code CHANGELOG.md} is ready for a release to stamp a version onto. */
    public enum ChangelogState {
        /** A {@code ## [Unreleased]} section exists — the shape {@code check_changelog} wants. */
        UNRELEASED,
        /** A changelog with no unreleased section. The gate refuses a release of this module. */
        NONE,
        /** No {@code CHANGELOG.md} at all — {@code botmaker-pilot}, which the gate exempts. */
        ABSENT;

        public String label() {
            return switch (this) {
                case UNRELEASED -> "[Unreleased]";
                case NONE -> "no [Unreleased]";
                case ABSENT -> "—";
            };
        }
    }

    /** {@code v1.0.37 +3} — the tag and how far HEAD has moved past it. */
    public String tagLabel() {
        String tag = latestTag.orElse("never released");
        if (ahead > 0) {
            return tag + "  +" + ahead;
        }
        return tag;
    }

    /**
     * What the release would do, in the script's own words.
     *
     * <p>A module the script never named is not "up to date" — it is a module the script does not release at
     * all, and saying so is the honest answer for {@code botmaker-gallery}, {@code botmaker-plugin-registry}
     * and this window's own repository.
     */
    public String planLabel() {
        return plan.map(ReleasePlan.Verdict::text).orElse("not released by release.sh");
    }

    public boolean releasing() {
        return plan.map(ReleasePlan.Verdict::releasing).orElse(false);
    }

    /** True when any pin names something other than its upstream's newest tag. */
    public boolean anyStalePin() {
        return pins.stream().anyMatch(DepsEnv.Pin::stale);
    }

    public String pinsLabel() {
        if (pins.isEmpty()) {
            return "—";
        }
        return String.join("   ", pins.stream().map(DepsEnv.Pin::toString).toList());
    }
}
