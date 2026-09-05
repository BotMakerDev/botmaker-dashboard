package com.botmaker.dashboard.umbrella;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module's {@code .deps.env} — the upstream refs its latest release was cut against.
 *
 * <p>The file is written by {@code release.sh} into a module's release commit and read by that module's
 * {@code jitpack.yml} (or, for Studio, by its {@code package} job). It makes a tag self-describing: checking
 * out {@code v1.0.37} tells you which shared, session, contract and loader it was built with.
 *
 * <p>What this window adds is the one question the file cannot answer about itself: <b>is a pin still the
 * newest tag upstream?</b> A stale pin is not an error — it is exactly what a module looks like between its
 * upstream's release and its own — but it is the thing a maintainer is trying to see before deciding what to
 * cut, and it is invisible in a file that names a ref and nothing else.
 */
public final class DepsEnv {

    /**
     * Which repository each key names. This is a naming table, not a decision: it says how
     * {@code SHARED_TAG} is spelled, never whether it should be released. An unrecognised key still shows,
     * with the key itself as its label, because a key nobody has classified is a new upstream and hiding it
     * would be the wrong direction to be wrong in.
     */
    private static final Map<String, String> MODULE_OF_KEY = Map.of(
            "SHARED_TAG", "botmaker-shared",
            "SESSION_TAG", "botmaker-session",
            "SDK_TAG", "botmaker-sdk",
            "STUDIO_API_TAG", "botmaker-studio-api",
            "PLUGIN_HOST_TAG", "botmaker-plugin-host",
            "PLUGIN_TOOLKIT_TAG", "botmaker-plugin-toolkit");

    private DepsEnv() {
    }

    /**
     * One pin.
     *
     * @param key      the variable as the file spells it, e.g. {@code SHARED_TAG}
     * @param ref      the git ref it names — usually a tag, but any ref is legal (the file's own header says
     *                 a branch or a SHA is how you test an unreleased upstream locally)
     * @param module   the repository the key names, empty for a key this version does not recognise
     * @param upstream that repository's newest tag as this checkout sees it, empty when unknown
     */
    public record Pin(String key, String ref, Optional<String> module, Optional<String> upstream) {

        /** True when the pinned ref is not the newest tag upstream — a fact to show, never a refusal. */
        public boolean stale() {
            return upstream.isPresent() && !upstream.get().equals(ref);
        }

        @Override
        public String toString() {
            return key + "=" + ref + (stale() ? " (upstream " + upstream.orElseThrow() + ")" : "");
        }
    }

    /**
     * Parses the {@code KEY=value} lines, ignoring comments and blanks.
     *
     * @param latestTags each module's newest tag, used only to mark a pin stale; a module missing from the
     *                   map leaves its pin unjudged rather than reported as current
     */
    public static List<Pin> parse(String text, Map<String, String> latestTags) {
        List<Pin> pins = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String ref = line.substring(eq + 1).trim();
            if (ref.isEmpty()) {
                continue;
            }
            Optional<String> module = Optional.ofNullable(MODULE_OF_KEY.get(key));
            Optional<String> upstream = module.map(latestTags::get);
            pins.add(new Pin(key, ref, module, upstream));
        }
        return List.copyOf(pins);
    }
}
