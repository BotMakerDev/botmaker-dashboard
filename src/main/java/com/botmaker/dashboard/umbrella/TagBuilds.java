package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Order;
import com.botmaker.cli.release.Version;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether each module's newest tag built on JitPack, and what repairs one that did not.
 *
 * <p><b>A tag JitPack built {@code Error} is broken for good</b>; only its next patch repairs it. On 2026-10-12
 * shared v0.2.4 never built, and SDK v1.4.3, Studio v1.4.3 and dashboard v0.3.1 pinned it; nothing in this window
 * said so, and the next release found out from its gates. This reads the answer JitPack already has.
 *
 * <p>JitPack is asked through the release library's {@link Jitpack.Probe}, the one the release's own wait asks:
 * the same build API, the same reading of {@code Error}. Studio and the dashboard are not JitPack builds; their
 * tag is broken when it pins a tag that is, since their package jobs resolve those pins from JitPack.
 *
 * <p>What a repair cuts is still the release's decision: the banner names the broken modules' flags, and the
 * decide pass adds what they force.
 */
public final class TagBuilds {

    private TagBuilds() {
    }

    /** What JitPack says about one tag. */
    public enum State {
        /** The pom is served. */
        BUILT,
        /** The build ran and failed, or a pin it resolves did; cached per tag, so it stays that way. */
        BROKEN,
        /** Neither yet: queued, building, never asked, or JitPack unreachable. */
        PENDING,
        /** Not a JitPack build, and nothing it pins is broken: the template, the APKs, remote-server. */
        NOT_ON_JITPACK;

        public String label() {
            return switch (this) {
                case BUILT -> "built";
                case BROKEN -> "never built";
                case PENDING -> "not built yet";
                case NOT_ON_JITPACK -> "—";
            };
        }

        /** The Modules tab's cell style for this state. */
        public String styleClass() {
            return switch (this) {
                case BUILT -> "cell--ok";
                case BROKEN -> "cell--broken";
                case PENDING, NOT_ON_JITPACK -> "cell--dim";
            };
        }
    }

    /**
     * One tag's answer.
     *
     * @param detail why it is broken — JitPack's own message, or the pin that never built; empty otherwise
     * @param pinned the broken pin, when that is why it is broken
     */
    public record Build(State state, String detail, Optional<Pins.Pin> pinned) {

        static Build of(State state) {
            return new Build(state, "", Optional.empty());
        }
    }

    /**
     * Asks JitPack about {@code module}'s tag.
     *
     * @param pins that tag's pins ({@link Pins#read}), asked when the module is not a JitPack build itself
     */
    public static Build of(Module module, String tag, List<Pins.Pin> pins, Jitpack.Probe probe) {
        if (module.onJitpack()) {
            return ask(module, tag, probe);
        }
        if (!module.resolvesFromJitpack()) {
            return Build.of(State.NOT_ON_JITPACK);
        }
        for (Pins.Pin pin : pins) {
            Build upstream = ask(pin.upstream(), pin.version(), probe);
            if (upstream.state() == State.BROKEN) {
                return new Build(State.BROKEN, "pins " + pin.upstream().directory() + " " + pin.version()
                        + ", which never built: " + upstream.detail(), Optional.of(pin));
            }
        }
        return Build.of(State.NOT_ON_JITPACK);
    }

    private static Build ask(Module module, String tag, Jitpack.Probe probe) {
        Optional<Version> version = Version.parse(tag);
        if (version.isEmpty()) {
            return Build.of(State.PENDING);
        }
        Optional<String> failure = probe.failure(Jitpack.buildUrl(module, version.get()));
        if (failure.isPresent()) {
            return new Build(State.BROKEN, failure.get(), Optional.empty());
        }
        return Build.of(probe.built(Jitpack.pomUrl(module, version.get())) ? State.BUILT : State.PENDING);
    }

    /**
     * The banner over the Modules tab: each module whose newest tag is broken, and the flags that cut its next
     * patch. Empty when none is.
     *
     * @param tags  each module directory's newest tag
     * @param built each module directory's answer so far; a module not answered yet is left out
     */
    public static Optional<Banner> banner(Map<String, String> tags, Map<String, Build> built) {
        List<Module> broken = Order.toTag(built.entrySet().stream()
                .filter(e -> e.getValue().state() == State.BROKEN)
                .flatMap(e -> Module.byDirectory(e.getKey()).stream())
                .toList());
        if (broken.isEmpty()) {
            return Optional.empty();
        }
        List<String> lines = broken.stream()
                .map(m -> m.directory() + " " + tags.getOrDefault(m.directory(), "?") + ": "
                        + built.get(m.directory()).detail())
                .toList();
        String flags = String.join(" ", broken.stream().map(Module::flag).toList());
        return Optional.of(new Banner(
                broken.size() == 1 ? "A tag never built on JitPack" : broken.size() + " tags never built on JitPack",
                lines, "./release.sh " + flags));
    }

    /**
     * @param command the release that cuts each broken module's next patch; its decide pass adds what they force,
     *                and each needs a changelog entry first (the Changelog tab's <i>Never built</i>)
     */
    public record Banner(String title, List<String> lines, String command) {
    }

    /**
     * The changelog bullet a repair release carries, in the shape the 2026-10-12 repairs were written.
     *
     * @param tag the tag that never built, {@code v1.4.3}
     */
    public static String entry(String tag, Build build) {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String why = build.pinned()
                .map(pin -> "Its pom pinned " + pin.upstream().directory() + " " + pin.version()
                        + ", a tag JitPack never built")
                .orElse("JitPack's build of it failed (" + withoutLogPath(build.detail()) + ")");
        return "- **" + version + " never built.** " + why + ", and a tag that built `Error` stays broken. This"
                + " release is " + version + " as it was meant to resolve: everything listed under " + version
                + " is in it.";
    }

    /** JitPack's "See the log: com/github/…/build.log" says nothing to a changelog's reader. */
    private static String withoutLogPath(String detail) {
        Matcher log = LOG.matcher(detail);
        return (log.find() ? log.replaceAll("") : detail).strip().replaceAll("[.\\s]+$", "");
    }

    private static final Pattern LOG = Pattern.compile("\\s*See the log: \\S+");

    /**
     * {@code section} with {@code bullet} added under its {@code ### Fixed} heading, which is added first when
     * the section has none.
     */
    public static String withEntry(String section, String bullet) {
        String body = section.strip();
        Matcher fixed = FIXED.matcher(body);
        if (fixed.find()) {
            String rest = body.substring(fixed.end()).replaceFirst("^\\n+", "");
            // First in the list it joins; a blank line before whatever heading follows when the list was empty.
            return body.substring(0, fixed.end()) + "\n\n" + bullet
                    + (rest.isEmpty() ? "" : rest.startsWith("- ") ? "\n" + rest : "\n\n" + rest);
        }
        return "### Fixed\n\n" + bullet + (body.isEmpty() ? "" : "\n\n" + body);
    }

    private static final Pattern FIXED = Pattern.compile("(?m)^### Fixed[ \\t]*$");
}
