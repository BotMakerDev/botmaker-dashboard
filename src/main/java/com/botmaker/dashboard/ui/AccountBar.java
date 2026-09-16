package com.botmaker.dashboard.ui;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Sign in with GitHub, through the OAuth <b>device flow</b> — the operator authorizes in a browser and
 * never pastes a token.
 *
 * <p>This is a second implementation of the *control*, not of the *flow*: the handshake, the poll, the
 * token file and its {@code 0600} are {@link GitHubAuth} in {@code botmaker-shared}, which is why that
 * package left Studio. What is here is the four buttons and the alert, and it is deliberately not shared
 * with Studio's own account bar — that one is themed by Studio's {@code BlockTheme}, hangs off Studio's
 * dialogs and answers to Studio's window. A widget with two owners is how this module would acquire a
 * dependency on an application.
 *
 * <p>{@code onAuthChanged} fires on every transition, because the admin probe has to be re-run for the new
 * account and must never be answered from a cached verdict about the old one.
 */
public final class AccountBar extends HBox {

    private final Window owner;
    private final GitHubAuth auth;
    private final GitHubClient client;
    private final Runnable onAuthChanged;

    private final Label who = new Label();
    private final Button action = new Button();

    public AccountBar(Window owner, GitHubAuth auth, GitHubClient client, Runnable onAuthChanged) {
        super(8);
        this.owner = owner;
        this.auth = auth;
        this.client = client;
        this.onAuthChanged = onAuthChanged;

        getStyleClass().add("account-bar");
        who.getStyleClass().add("account-login");

        getChildren().addAll(who, action);
        render();
    }

    private void render() {
        if (!auth.isConfigured()) {
            who.setText("sign-in not configured");
            action.setDisable(true);
            return;
        }
        action.setDisable(false);
        if (auth.isAuthenticated()) {
            who.setText("…");
            auth.login(client).thenAccept(login -> Platform.runLater(() ->
                    who.setText(login.isBlank() ? "signed in" : "@" + login)));
            action.setText("Sign out");
            action.setOnAction(e -> {
                auth.signOut();
                render();
                onAuthChanged.run();
            });
        } else {
            who.setText("not signed in");
            action.setText("Sign in");
            action.setOnAction(e -> signIn());
        }
    }

    /**
     * Request a device code, show it, open the browser, then poll.
     *
     * <p>The alert stays up while the poll runs and is closed by the poll's own completion — a device code
     * expires, so a dialog the user has to dismiss themselves would routinely be showing a code that has
     * stopped working. The poll is a background thread by construction ({@code pollForToken} supplies its
     * own), so nothing here may touch the scene without {@link Platform#runLater}.
     */
    private void signIn() {
        action.setDisable(true);
        auth.requestDeviceCode()
                .thenAccept(code -> Platform.runLater(() -> {
                    Alert waiting = showCode(code);
                    auth.pollForToken(code)
                            .whenComplete((token, error) -> Platform.runLater(() -> {
                                waiting.close();
                                action.setDisable(false);
                                if (error != null) {
                                    failed(error);
                                    return;
                                }
                                render();
                                onAuthChanged.run();
                            }));
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> {
                        action.setDisable(false);
                        failed(error);
                    });
                    return null;
                });
    }

    private Alert showCode(GitHubAuth.DeviceCode code) {
        TextArea userCode = new TextArea(code.userCode());
        userCode.setEditable(false);
        userCode.setPrefRowCount(1);
        userCode.getStyleClass().add("device-code");

        Label where = new Label("Open " + code.verificationUri() + " and enter this code:");
        where.setWrapText(true);

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Sign in with GitHub");
        alert.setHeaderText("Waiting for GitHub…");
        alert.getDialogPane().setContent(new VBox(8, where, userCode));
        alert.getButtonTypes().setAll(ButtonType.CANCEL);
        Themed.dialog(alert, owner);
        alert.show();

        // Best-effort: the code is on screen either way, so a headless or restricted desktop costs nothing.
        Browse.open(code.verificationUri());
        return alert;
    }

    private void failed(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        Alert alert = new Alert(Alert.AlertType.ERROR, cause.getMessage());
        alert.setHeaderText("Sign-in failed");
        Themed.dialog(alert, owner);
        alert.showAndWait();
    }
}
