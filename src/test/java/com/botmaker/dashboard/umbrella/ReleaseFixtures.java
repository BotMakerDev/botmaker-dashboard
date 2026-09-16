package com.botmaker.dashboard.umbrella;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A release in the states worth drawing: the child's stamped output and the log it keeps, written the way
 * {@code com.botmaker.cli.release} writes them. Three modules — one JitPack builds, one it builds, and Studio,
 * which it does not.
 */
public final class ReleaseFixtures {

    public static final Instant T0 = Instant.parse("2026-09-16T08:20:00Z");

    private ReleaseFixtures() {
    }

    /** Output lines, each {@code seconds after T0} then text. */
    public static List<ReleaseProgress.Line> out(Object... secondsThenText) {
        List<ReleaseProgress.Line> lines = new ArrayList<>();
        for (int i = 0; i < secondsThenText.length; i += 2) {
            lines.add(ReleaseProgress.Line.parse(ReleaseProgress.Line.format(
                    T0.plusSeconds((Integer) secondsThenText[i]), (String) secondsThenText[i + 1])));
        }
        return lines;
    }

    public static Instant at(int seconds) {
        return T0.plusSeconds(seconds);
    }

    public static Optional<ReleaseLog> log(String... rowsThenErrors) {
        StringBuilder text = new StringBuilder("# Release 2026-09-16 10:20\n\n"
                + "| module | version | tag | stage | changelog | jitpack | actions |\n"
                + "|---|---|---|---|---|---|---|\n");
        for (String line : rowsThenErrors) {
            text.append(line).append('\n');
        }
        return Optional.of(ReleaseLog.parse(Path.of("releases/2026-09-16-1020.md"), text.toString()));
    }

    /** The start of every run: the plan, the gates, and the log appearing just before the first tag. */
    public static List<Object> head() {
        return new ArrayList<>(List.of(
                0, "Cutting botmaker release --all minor --execute — process 4242",
                1, "Release plan:",
                60, "Gates:",
                90, "Release log: releases/2026-09-16-1020.md"));
    }

    public static List<Object> apiTaggedAndBuilt() {
        return List.of(
                90, "Releasing botmaker-studio-api v0.1.0",
                91, "    $ git -C /u/botmaker-studio-api commit -am 'release: studio-api v0.1.0'",
                92, "    $ git -C /u/botmaker-studio-api tag v0.1.0",
                92, "    $ git -C /u/botmaker-studio-api push origin HEAD",
                93, "    $ git -C /u/botmaker-studio-api push origin v0.1.0",
                94, "waiting for JitPack to build botmaker-studio-api:v0.1.0 ...",
                200, "JitPack build of botmaker-studio-api:v0.1.0 is ready.");
    }

    public static List<Object> hostWaitingOnJitpack() {
        return List.of(
                200, "Releasing botmaker-plugin-host v0.1.0",
                201, "    $ cat > /u/botmaker-plugin-host/.deps.env <<'DEPS_EOF' … DEPS_EOF",
                202, "    $ git -C /u/botmaker-plugin-host commit -am 'release: plugin-host v0.1.0'",
                203, "    $ git -C /u/botmaker-plugin-host tag v0.1.0",
                203, "    $ git -C /u/botmaker-plugin-host push origin HEAD",
                204, "    $ git -C /u/botmaker-plugin-host push origin v0.1.0",
                205, "waiting for JitPack to build botmaker-plugin-host:v0.1.0 ...");
    }

    @SafeVarargs
    public static List<ReleaseProgress.Line> join(List<Object>... parts) {
        List<Object> all = new ArrayList<>();
        for (List<Object> part : parts) {
            all.addAll(part);
        }
        return out(all.toArray());
    }

    /** Mid-chain: studio-api built, plugin-host waiting on JitPack, Studio not started. */
    public static ReleaseProgress midChain() {
        return ReleaseProgress.of(join(head(), apiTaggedAndBuilt(), hostWaitingOnJitpack()),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |",
                        "| botmaker-plugin-host | 0.1.0 | v0.1.0 | pending | — | pending | pending |",
                        "| botmaker-studio | 1.2.0 | v1.2.0 | pending | — | n/a (not a Maven artifact) | pending |"),
                true, at(500));
    }

    /** The host's push failed: studio-api built, the host FAILED, Studio never reached, the child gone. */
    public static ReleaseProgress crashed() {
        return ReleaseProgress.of(join(head(), apiTaggedAndBuilt(), List.of(
                        200, "Releasing botmaker-plugin-host v0.1.0",
                        202, "    $ git -C /u/botmaker-plugin-host commit -am 'release: plugin-host v0.1.0'",
                        203, "    $ git -C /u/botmaker-plugin-host tag v0.1.0",
                        203, "    $ git -C /u/botmaker-plugin-host push origin HEAD",
                        204, "    $ git -C /u/botmaker-plugin-host push origin v0.1.0",
                        210, "error: botmaker-plugin-host failed at commit, tag and push — the release stopped here. "
                                + "1 of 3 modules were tagged.",
                        211, "release-job: stopped — botmaker-plugin-host: pushing v0.1.0 failed.")),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | pending | pending |",
                        "| botmaker-plugin-host | 0.1.0 | v0.1.0 | FAILED | — | not tagged | not tagged |",
                        "| botmaker-studio | 1.2.0 | v1.2.0 | not reached | — | n/a (not a Maven artifact) | not tagged |",
                        "",
                        "## Errors",
                        "",
                        "**botmaker-plugin-host — release**",
                        "```",
                        "commit, tag and push: botmaker-plugin-host: pushing v0.1.0 failed.",
                        "```"),
                false, at(900));
    }

    /** Finished: every tag out, the host's JitPack build broken and its Actions run failed. */
    public static ReleaseProgress completeWithFailures() {
        return ReleaseProgress.of(join(head(), apiTaggedAndBuilt(), hostWaitingOnJitpack(), List.of(
                        320, "JitPack build of botmaker-plugin-host:v0.1.0 is ready.",
                        320, "Releasing botmaker-studio v1.2.0",
                        321, "    $ git -C /u/botmaker-studio commit -am 'release: studio v1.2.0'",
                        322, "    $ git -C /u/botmaker-studio tag v1.2.0",
                        322, "    $ git -C /u/botmaker-studio push origin HEAD",
                        323, "    $ git -C /u/botmaker-studio push origin v1.2.0",
                        324, "botmaker-studio v1.2.0 tagged — last, so every tag its package matrix checks out is "
                                + "already on origin.",
                        400, "Recording submodule pointers in the umbrella",
                        420, "release-job: done")),
                log("| botmaker-studio-api | 0.1.0 | v0.1.0 | built on jitpack | stamped | ok (resolves clean) | success (1) |",
                        "| botmaker-plugin-host | 0.1.0 | v0.1.0 | built on jitpack | stamped | BROKEN | FAILED — CI |",
                        "| botmaker-studio | 1.2.0 | v1.2.0 | tagged | stamped | n/a (not a Maven artifact) | success (2) |",
                        "",
                        "## Errors",
                        "",
                        "**botmaker-plugin-host — actions**",
                        "```",
                        "CI: failure — https://github.com/LiQiyeDev/botmaker-plugin-host/actions/runs/1",
                        "build: [ERROR] PluginLoaderTest.a_broken_plugin_does_not_cost_the_others:171",
                        "```"),
                false, at(900));
    }
}
