package com.botmaker.dashboard.ui;

import com.botmaker.shared.platform.Os;
import javafx.application.HostServices;
import javafx.application.Platform;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Opens a URL in the operator's browser, best-effort, and <b>never through {@code java.awt.Desktop}</b>.
 *
 * <p><b>Why not AWT.</b> This class called {@code Desktop.getDesktop().browse} until 2026-09-16, on the FX
 * thread. On Linux that initialises the AWT toolkit inside a running JavaFX application, and the result
 * there is a freeze and then a dead window — which is how a release being cut from the Release tab died
 * halfway through its tag chain: the operator clicked <i>Open pull request</i> after an Unpublish, the
 * window froze, and the release was running in the same JVM. {@code UriLauncher} in shared is not reused
 * for the same reason: it tries {@code Desktop.browse} first.
 *
 * <p><b>The order is the platform opener, then {@link HostServices}.</b> {@link Os#openCommand} is the table
 * shared already keeps ({@code xdg-open}, {@code open}, {@code rundll32}); it honours the desktop's default
 * browser and reports failure as an exit code, and it runs on a background thread so a slow opener can
 * never hold the FX thread. {@code HostServices.showDocument} is the fallback because it is JavaFX's own and
 * safe, but on Linux it guesses from a fixed list of browser names and fails <i>silently</i> — so it cannot
 * come first, or nothing would ever tell us the open did not happen.
 *
 * <p><b>Best-effort is still the contract.</b> Everything this window offers to open is also readable in the
 * window itself, so a failure is not an error dialog. What it is, since 2026-09-16, is the URL handed back
 * to the caller's status line, so it can be copied rather than retyped from a table cell.
 */
public final class Browse {

    private static volatile HostServices hostServices;

    private Browse() {
    }

    /** Called once by {@code DashboardApp.start}; before that, only the platform opener is tried. */
    public static void install(HostServices services) {
        hostServices = services;
    }

    public static void open(String url) {
        open(url, sentence -> System.err.println(sentence));
    }

    /**
     * Opens {@code url}; if no opener managed it, calls {@code failed} <b>on the FX thread</b> with a
     * sentence that ends in the URL itself.
     */
    public static void open(String url, Consumer<String> failed) {
        CompletableFuture.supplyAsync(() -> viaPlatformOpener(url)).thenAccept(opened -> {
            if (opened) {
                return;
            }
            Platform.runLater(() -> {
                HostServices services = hostServices;
                if (services != null) {
                    try {
                        services.showDocument(url);
                        return;
                    } catch (RuntimeException e) {
                        // fall through to the sentence
                    }
                }
                failed.accept("Could not open a browser — the link is " + url);
            });
        });
    }

    private static boolean viaPlatformOpener(String url) {
        try {
            Process process = new ProcessBuilder(Os.current().openCommand(url))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // xdg-open returns once the browser has the URL; a browser that was not running may keep it a
            // few seconds. Not finishing in time is not a failure — the opener started, which is the claim.
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                return true;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
