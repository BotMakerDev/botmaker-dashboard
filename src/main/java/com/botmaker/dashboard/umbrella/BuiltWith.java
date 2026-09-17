package com.botmaker.dashboard.umbrella;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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
 * <p>The build's own cli is read from {@code META-INF/botmaker/.deps.env}, which the {@code dist} profile
 * bakes from the module's {@code .deps.env} at package time. A development run (the reactor, {@code
 * javafx:run}) has no such resource, and rightly says nothing: there the cli on the classpath <i>is</i> the
 * checkout's.
 */
public final class BuiltWith {

    /** Where the {@code dist} profile puts the module's {@code .deps.env} inside the jar. */
    public static final String RESOURCE = "/META-INF/botmaker/.deps.env";

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

    /** The {@code CLI_TAG} pin in a {@code .deps.env}, or empty when the file does not carry one. */
    public static Optional<String> cliTag(String depsEnv) {
        return DepsEnv.parse(depsEnv, Map.of()).stream()
                .filter(pin -> pin.key().equals("CLI_TAG"))
                .map(DepsEnv.Pin::ref)
                .findFirst();
    }

    /**
     * What the checkout's {@code botmaker-cli} is at, as {@code git describe --tags} spells it: the tag
     * itself when HEAD is tagged, {@code v0.0.13-3-g5af261c} when it has moved past one. Empty when git
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
