package com.botmaker.dashboard.umbrella;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What modules the checkout holds, read from {@code .gitmodules}.
 *
 * <p><b>There is no list of module names in this repository, and there must not be one.</b> The umbrella's
 * {@code .gitmodules} is the only statement of what a checkout contains, and a copy here would be a second
 * one — silently short by exactly one module the day an eleventh is added, which is the day somebody most
 * needs the window to show it. (This module itself was that eleventh, three days after the tenth.)
 *
 * <p>Nor is there a list of which modules are <i>releasable</i>. That question is answered by whether
 * {@code release.sh --all --dry-run} names the module in its decide pass: {@code botmaker-gallery} and
 * {@code botmaker-plugin-registry} are data-only and never appear, and neither does this one. See
 * {@link ReleasePlan}.
 */
public final class Umbrella {

    /** {@code path = botmaker-shared}, with any leading whitespace the file happens to use. */
    private static final Pattern PATH_LINE = Pattern.compile("(?m)^\\s*path\\s*=\\s*(\\S+)\\s*$");

    private Umbrella() {
    }

    /**
     * The submodule paths named by a {@code .gitmodules} file, in the order the file lists them.
     *
     * <p>The order is the file's rather than the reactor's on purpose: this is a checkout inventory, and the
     * reactor order is {@code pom.xml}'s business.
     */
    public static List<String> modules(String gitmodules) {
        List<String> paths = new ArrayList<>();
        Matcher m = PATH_LINE.matcher(gitmodules);
        while (m.find()) {
            String path = m.group(1);
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }
}
