package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Module;
import com.botmaker.shared.github.GitHubConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every page this window links to, built in one place.
 *
 * <p><b>Two kinds of destination.</b> A <i>released tag</i> has three places it can be wrong — its GitHub
 * Release, its JitPack build and the workflow runs it fired — and the release log's own columns are exactly
 * those: a tag can be pushed with the GitHub Release never cut (a workflow that died on a missing secret), or
 * with the Release cut and the JitPack build failed, or with both green. A row that says {@code NOT
 * PUBLISHED} is only useful next to the page that says why. A <i>repository</i> — a module, or the plugin or
 * bot a catalog entry names — has the same pages without a tag: its code, its runs on {@code main}, its
 * JitPack project and its releases.
 *
 * <p>The owner comes from {@link GitHubConfig} rather than being typed here — the whole constellation is one
 * org, shared already states its name, and a second copy is a second thing to edit if it ever moves.
 *
 * <p>The lists ({@link #forModule}, {@link #forRepository}) are what a tab draws, as buttons and as a context
 * menu from the same value, so the two cannot offer different pages.
 */
public final class Links {

    private static final String OWNER = GitHubConfig.REGISTRY_OWNER;
    private static final String GITHUB = "https://github.com/";

    /** One destination: what the button says and where it goes. */
    public record Link(String label, String url) {
    }

    private Links() {
    }

    /** The GitHub Release for a tag — the notes each module's own JReleaser step publishes. */
    public static String release(String module, String tag) {
        return GITHUB + OWNER + "/" + module + "/releases/tag/" + tag;
    }

    /**
     * The JitPack build log for a tag.
     *
     * <p>The one page that answers "the tag is pushed and there is no .pom": JitPack builds a tag on demand
     * and caches the result, so a failed build is permanent and only a new tag repairs it.
     */
    public static String jitpack(String module, String tag) {
        return "https://jitpack.io/#" + OWNER + "/" + module + "/" + tag;
    }

    /**
     * The workflow runs a tag fired.
     *
     * <p>Filtered by {@code branch}, which is not a mistake: a tag-triggered run records the tag in
     * {@code headBranch}, and it is the same filter {@code poll_actions} uses to isolate one release's runs.
     */
    public static String actions(String module, String tag) {
        return GITHUB + OWNER + "/" + module + "/actions?query=branch%3A" + tag;
    }

    /** A module's repository. The directory name is the repository name for every submodule here. */
    public static String repo(String module) {
        return GITHUB + slugOf(module);
    }

    /** The runs on {@code main} — the ones {@code CiGate} reads before a module may be cut. */
    public static String actions(String module) {
        return actionsOn(slugOf(module));
    }

    /** Every GitHub Release a module has published. */
    public static String releases(String module) {
        return releasesOf(slugOf(module));
    }

    /** The JitPack project page: every tag it has built, and whether each build passed. */
    public static String jitpackPage(String module) {
        return jitpackPageOf(slugOf(module));
    }

    /** What changed between two refs — a tag and {@code main}, usually. */
    public static String compare(String module, String from, String to) {
        return GITHUB + slugOf(module) + "/compare/" + from + "..." + to;
    }

    /** A module's four pages, with no comparison — what a row of buttons has the width for. */
    public static List<Link> forModule(String module) {
        return forModule(module, Optional.empty(), 0);
    }

    /**
     * The pages for one module of the checkout.
     *
     * <p>A comparison is offered only when there is something to compare: a tag, and commits past it. A
     * module never released has no tag to start from, and one sitting on its tag would open an empty diff.
     */
    public static List<Link> forModule(String module, Optional<String> latestTag, int ahead) {
        List<Link> links = new ArrayList<>(List.of(
                new Link("GitHub", repo(module)),
                new Link("Actions", actions(module)),
                new Link("Releases", releases(module))));
        if (onJitpack(module)) {
            links.add(2, new Link("JitPack", jitpackPage(module)));
        }
        latestTag.filter(tag -> ahead > 0)
                .ifPresent(tag -> links.add(new Link("Changes since " + tag, compare(module, tag, "main"))));
        return List.copyOf(links);
    }

    /**
     * The pages for a repository somebody else owns, named as {@code owner/name}.
     *
     * <p><b>JitPack only when the thing is resolved as an artifact.</b> A plugin is: its entry carries a
     * Maven coordinate and a host resolves it. A bot is not — JitPack would serve a page for it, since it
     * serves any repository, and that page says nothing about a bot nobody resolves. A link to a page that
     * cannot answer anything is worse than one button fewer.
     */
    public static List<Link> forRepository(String slug, boolean onJitpack) {
        List<Link> links = new ArrayList<>(List.of(
                new Link("GitHub", GITHUB + slug),
                new Link("Actions", actionsOn(slug)),
                new Link("Releases", releasesOf(slug))));
        if (onJitpack) {
            links.add(2, new Link("JitPack", jitpackPageOf(slug)));
        }
        return List.copyOf(links);
    }

    /**
     * Whether a module of this checkout is resolved from JitPack at all.
     *
     * <p>The release library's own answer ({@code ReleaseLog.onJitpack}), not a list kept here: the pilot is
     * an APK, Studio is packaged per OS by its own CI, and the gallery, the registry and this window are not
     * released at all. A button offering a JitPack page for any of them opens a page about nothing.
     */
    private static boolean onJitpack(String module) {
        return Module.byDirectory(module)
                .map(com.botmaker.cli.release.ReleaseLog::onJitpack)
                .orElse(false);
    }

    /**
     * {@code owner/name} out of whatever a registry entry's {@code repo} holds.
     *
     * <p>The gate requires the field, not its spelling, so a full URL, one ending in {@code .git} and the
     * bare slug are all accepted here. Anything that does not reduce to exactly two segments is empty: a
     * guessed repository link is worse than none, because it opens somebody else's page.
     */
    public static Optional<String> slug(String repo) {
        if (repo == null) {
            return Optional.empty();
        }
        String s = repo.strip();
        for (String prefix : List.of("https://github.com/", "http://github.com/", "github.com/")) {
            if (s.startsWith(prefix)) {
                s = s.substring(prefix.length());
                break;
            }
        }
        if (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - ".git".length());
        }
        String[] parts = s.split("/", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    private static String slugOf(String module) {
        return OWNER + "/" + module;
    }

    private static String actionsOn(String slug) {
        return GITHUB + slug + "/actions?query=branch%3Amain";
    }

    private static String releasesOf(String slug) {
        return GITHUB + slug + "/releases";
    }

    private static String jitpackPageOf(String slug) {
        return "https://jitpack.io/#" + slug;
    }
}
