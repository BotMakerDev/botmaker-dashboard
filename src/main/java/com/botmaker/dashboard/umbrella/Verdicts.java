package com.botmaker.dashboard.umbrella;

import com.botmaker.cli.release.Actions;
import com.botmaker.cli.release.CleanRoom;
import com.botmaker.cli.release.Jitpack;
import com.botmaker.cli.release.Module;
import com.botmaker.cli.release.Runner;
import com.botmaker.cli.release.Version;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The three questions the Releases tab asks about one tag, each answered by the release library's own reader.
 *
 * <p><b>Two JitPack questions, labelled so they cannot be confused.</b> {@link #jitpackHead} asks whether the
 * pom is downloadable — {@code Jitpack.pomUrl}, the URL the release's own wait polls — and answers
 * {@code published (pom HEAD)}. It never says {@code ok}, because a published pom can still name a dependency
 * nobody can resolve, which is the bug that shipped in every SDK up to v1.0.24. {@link #deepCheck} is the
 * release's {@code CleanRoom.resolve} and says {@code ok (resolves clean)} or {@code BROKEN}, in the log's own
 * words; it costs about forty seconds a module, so it runs only when asked.
 *
 * <p>Actions is {@code Actions.poll}, the reader the release log uses, excerpt and all.
 */
public final class Verdicts {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Verdicts() {
    }

    /** A {@code .pom} HEAD: {@code published (pom HEAD)}, {@code missing (pom HEAD)} or {@code unknown (…)}. */
    public static String jitpackHead(Module module, Version version) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(Jitpack.pomUrl(module, version)))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(20))
                    .build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status == 200 ? "published (pom HEAD)"
                    : status == 404 ? "missing (pom HEAD)"
                    : "unknown (pom HEAD answered " + status + ")";
        } catch (java.io.IOException e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown (interrupted)";
        }
    }

    /** A clean-room answer: the log's verdict word and the resolution errors behind it. */
    public record Deep(String verdict, String error) {
    }

    /**
     * The clean-room resolve, as the release ran it. It writes nothing through the runner — it resolves into
     * a throwaway repository of its own — so the runner is only where its one "no mvn" line would go.
     */
    public static Deep deepCheck(Module module, Version version) {
        Optional<String> broken = CleanRoom.resolve(new Runner(false, line -> {
        }), module, version);
        return broken.map(error -> new Deep("BROKEN", error)).orElse(new Deep("ok (resolves clean)", ""));
    }

    public static Actions.Poll actions(Module module, Version version) {
        return Actions.poll(module, version);
    }
}
