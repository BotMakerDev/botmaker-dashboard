package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.DashboardConfig;
import com.botmaker.dashboard.umbrella.BuiltWith;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The umbrella checkout this window is looking at, and the button that changes it.
 *
 * <p>It is the first control in the top bar because it is the first thing every release view needs: the
 * module list comes from {@code .gitmodules}, the tags come from each submodule's git, the history comes
 * from {@code releases/*.md}, and the plan comes from {@code ./release.sh --dry-run}. None of that is
 * reachable over the network, which is exactly why this app is a desktop window rather than a page.
 *
 * <p>A directory that is not the umbrella is <b>refused at the picker</b> rather than accepted and reported
 * as four empty tabs. {@link DashboardConfig#looksLikeUmbrella} names the two files that decide it.
 *
 * <p>Beside the path, when there is something to say: <i>built with cli vX, checkout at vY</i>. An
 * installed dashboard cuts releases with the cli it was packaged with, and this is the one place a checkout
 * that has moved past it is visible — see {@link BuiltWith}. A development run never shows it.
 */
public final class UmbrellaBar extends HBox {

    private final Window owner;
    private final Label path = new Label();
    private final Label notice = new Label();
    private final Consumer<Path> onChosen;

    private Path current;

    public UmbrellaBar(Window owner, Path initial, Consumer<Path> onChosen) {
        super(8);
        this.owner = owner;
        this.onChosen = onChosen;

        getStyleClass().add("umbrella-bar");
        path.getStyleClass().add("umbrella-path");
        notice.getStyleClass().add("cli-notice");
        notice.setVisible(false);
        notice.setManaged(false);

        Button change = new Button("Change…");
        change.setOnAction(e -> choose());

        getChildren().addAll(new Label("Umbrella:"), path, change, notice);
        set(initial);
    }

    /** The checkout in use, or {@code null} while none has been chosen. */
    public Path current() {
        return current;
    }

    /** The stale-cli notice, for a test to read; hidden when there is nothing to say. */
    public Label notice() {
        return notice;
    }

    private void choose() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Pick the BotMaker umbrella checkout");
        if (current != null) chooser.setInitialDirectory(current.toFile());

        File picked = chooser.showDialog(owner);
        if (picked == null) return;

        Path dir = picked.toPath();
        if (!DashboardConfig.looksLikeUmbrella(dir)) {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    dir + "\n\nis not the umbrella checkout — it has no release.sh and no .gitmodules "
                            + "beside each other. Pick the directory the submodules sit in.");
            alert.setHeaderText("Not the umbrella");
            Themed.dialog(alert, owner);
            alert.showAndWait();
            return;
        }
        set(dir);
        onChosen.accept(dir);
    }

    private void set(Path dir) {
        current = dir;
        path.setText(dir == null ? "not set — pick the checkout the submodules sit in" : dir.toString());
        path.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("unset"), dir == null);
        show(Optional.empty());
        if (dir != null) {
            // git, off the FX thread; the answer lands whenever it lands, for the checkout it was asked of.
            Thread.ofVirtual().name("built-with").start(() -> {
                Optional<String> text = BuiltWith.notice(dir);
                Platform.runLater(() -> {
                    if (dir.equals(current)) {
                        show(text);
                    }
                });
            });
        }
    }

    private void show(Optional<String> text) {
        notice.setText(text.orElse(""));
        notice.setVisible(text.isPresent());
        notice.setManaged(text.isPresent());
    }
}
