package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.PomVersions;
import com.botmaker.cli.release.Version;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The upstream versions a module's latest release was cut against, read off that tag's pom.
 *
 * <p>The release commit sets every {@code botmaker.<key>.version} to the upstream's released version before
 * it is tagged (umbrella {@code docs/refactor/43-real-versions.md}), so the tag's pom is self-describing:
 * {@code git show v1.3.1:pom.xml} says which shared, session, contract and loader it was built with. A
 * {@code .deps.env} beside the pom said the same until 2026-10-06.
 *
 * <p>What this window adds is the one question the pom cannot answer about itself: <b>is a pin still the
 * newest tag upstream?</b> A stale pin is not an error — it is exactly what a module looks like between its
 * upstream's release and its own — but it is the thing a maintainer is trying to see before deciding what to
 * cut. Which upstreams a module pins is {@link Module#upstreams()}, never a table here.
 */
public final class Pins {

    private Pins() {
    }

    /**
     * One pin.
     *
     * @param upstream the module pinned
     * @param version  the version the tag's pom names; a tag cut before 2026-10-06 names its cosmetic
     *                 {@code 0.0.0-SNAPSHOT}, which is shown and never judged
     * @param latest   that upstream's newest tag as this checkout sees it, empty when unknown
     */
    public record Pin(Module upstream, String version, Optional<String> latest) {

        /** True when the pinned release is not the newest tag upstream — a fact to show, never a refusal. */
        public boolean stale() {
            Optional<Version> pinned = Version.parse(version);
            Optional<Version> newest = latest.flatMap(Version::parse);
            return pinned.isPresent() && newest.isPresent() && !pinned.equals(newest);
        }

        @Override
        public String toString() {
            return upstream.shortName() + " " + version + (stale() ? " (upstream " + latest.orElseThrow() + ")" : "");
        }
    }

    /**
     * The pins of {@code module} in {@code pom}, in {@link Module#upstreams()} order; an upstream the pom does
     * not name is left out.
     *
     * @param latestTags each module directory's newest tag, used only to mark a pin stale; a module missing
     *                   from the map leaves its pin unjudged rather than reported as current
     */
    public static List<Pin> read(Module module, String pom, Map<String, String> latestTags) {
        return module.upstreams().stream()
                .flatMap(upstream -> PomVersions.property(pom, upstream).stream()
                        .map(version -> new Pin(upstream, version,
                                Optional.ofNullable(latestTags.get(upstream.directory())))))
                .toList();
    }
}
