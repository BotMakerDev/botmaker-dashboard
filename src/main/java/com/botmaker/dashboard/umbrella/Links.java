package com.botmaker.dashboard.umbrella;

import com.botmaker.shared.github.GitHubConfig;

/**
 * Where to look at a released tag: its GitHub Release, its JitPack build, and the workflow runs it fired.
 *
 * <p>Three destinations because a release has three places it can be wrong, and the release log's own
 * columns are exactly those: a tag can be pushed with the GitHub Release never cut (a workflow that died on
 * a missing secret), or with the Release cut and the JitPack build failed, or with both green. A row that
 * says {@code NOT PUBLISHED} is only useful next to the page that says why.
 *
 * <p>The owner comes from {@link GitHubConfig} rather than being typed here — the whole constellation is one
 * org, shared already states its name, and a second copy is a second thing to edit if it ever moves.
 */
public final class Links {

    private static final String OWNER = GitHubConfig.REGISTRY_OWNER;

    private Links() {
    }

    /** The GitHub Release for a tag — the notes each module's own JReleaser step publishes. */
    public static String release(String module, String tag) {
        return "https://github.com/" + OWNER + "/" + module + "/releases/tag/" + tag;
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
        return "https://github.com/" + OWNER + "/" + module + "/actions?query=branch%3A" + tag;
    }
}
