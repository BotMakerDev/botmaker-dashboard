package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Which {@code botmaker-cli} this build carries, against which one the checkout is at.
 *
 * <p><b>The installed dashboard decides by the rules it was built with.</b> The Release tab calls
 * {@code com.botmaker.cli.release} in-process, so a packaged dashboard previews and cuts with the cli that
 * was on its classpath at package time — not the one in the umbrella checkout it is looking at. The release
 * closes the usual direction ({@code --cli} forces {@code --dashboard}, so a cli tag never ships without a
 * dashboard tag), but a checkout can still be <i>ahead</i> of the installed build: a cli change committed
 * this morning is in the checkout and not in the app until the next package. That is not an error, but it
 * is a fact the operator must see before trusting a preview, so it is a notice in the top bar.
 *
 * <p>The build's own cli is read from {@code META-INF/botmaker/built-with.properties}, which the {@code dist}
 * profile filters from the pom's {@code botmaker.cli.version} at package time — on a tag, the cli release
 * the release commit pinned. It was baked from a {@code .deps.env} until 2026-10-06. A development run (the
 * reactor, {@code javafx:run}) has no such resource, and rightly says nothing: there the cli on the
 * classpath <i>is</i> the checkout's.
 */
public final class BuiltWith {

    /** Where the {@code dist} profile puts the filtered properties inside the jar. */
    public static final String RESOURCE = "/META-INF/botmaker/built-with.properties";

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(10);

    private BuiltWith() {
    }

    /** The cli ref this build was packaged against, or empty for a development build. */
    public static Optional<String> cliTag() {
        try (InputStream in = BuiltWith.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            return cliTag(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The {@code cli.version} in the baked properties, as {@code git describe} spells a tag
     * ({@code 0.2.1} → {@code v0.2.1}); a {@code -SNAPSHOT} (a dispatch build from a branch) as it is. Empty
     * when the file carries none or it was never filtered.
     */
    public static Optional<String> cliTag(String properties) {
        Properties read = new Properties();
        try {
            read.load(new StringReader(properties));
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.ofNullable(read.getProperty("cli.version"))
                .map(String::strip)
                .filter(version -> !version.isEmpty() && !version.contains("${"))
                .map(version -> Version.parse(version).map(Version::tag).orElse(version));
    }

    /**
     * What the checkout's {@code botmaker-cli} is at, as {@code git describe --tags} spells it: the tag
     * itself when HEAD is tagged or past it by the release's own commits only, {@code v0.0.13-3-g5af261c} when
     * it has moved past one. Empty when git
     * cannot say (no submodule, no tag yet). Never call on the FX thread.
     */
    public static Optional<String> checkoutCli(Path umbrella) {
        Path cli = umbrella.resolve("botmaker-cli");
        if (!cli.toFile().isDirectory()) {
            return Optional.empty();
        }
        Proc p = Proc.run(cli, GIT_TIMEOUT, "git", "describe", "--tags", "--always");
        if (!p.ok() || p.firstLine().isEmpty()) {
            return Optional.empty();
        }
        // Every release lands its back-to-snapshot commit right after the tag, so HEAD is never on it: a
        // checkout whose only commits since the tag are the release's own is at that tag.
        Proc tag = Proc.run(cli, GIT_TIMEOUT, "git", "describe", "--tags", "--abbrev=0");
        if (tag.ok() && !tag.firstLine().isEmpty()) {
            List<String> count = new ArrayList<>(List.of("git", "rev-list", "--count", tag.firstLine() + "..HEAD"));
            count.addAll(ChangelogEdit.notBookkeeping());
            Proc since = Proc.run(cli, GIT_TIMEOUT, count);
            if (since.ok() && since.firstLine().equals("0")) {
                return Optional.of(tag.firstLine());
            }
        }
        return Optional.of(p.firstLine());
    }

    /**
     * The notice, or empty when there is nothing to say: a development build, an unreadable checkout, or
     * a checkout at exactly the cli this build carries.
     */
    public static Optional<String> notice(Optional<String> built, Optional<String> checkout) {
        if (built.isEmpty() || checkout.isEmpty() || built.get().equals(checkout.get())) {
            return Optional.empty();
        }
        return Optional.of("built with cli " + built.get() + ", checkout at " + checkout.get()
                + " — preview may follow older rules");
    }

    /** {@link #notice(Optional, Optional)} over this build and that checkout. Never call on the FX thread. */
    public static Optional<String> notice(Path umbrella) {
        return notice(cliTag(), checkoutCli(umbrella));
    }
}
