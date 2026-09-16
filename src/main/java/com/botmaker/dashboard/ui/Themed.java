package com.botmaker.dashboard.ui;

import com.botmaker.dashboard.Theme;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Window;

import java.util.List;

/**
 * The palette, applied to <b>every window</b> — the main one, each dialog, and every popup.
 *
 * <p><b>Why every window and not the scene.</b> A {@link Dialog}, a context menu, a tooltip and a combo box's
 * list are each their own {@link Window} with their own {@link Scene}, and a stylesheet added to the main
 * scene reaches none of the first kind. That was the black-on-white dialog: Modena's defaults, with this
 * stylesheet's light text rules applied only where they happened to reach. So {@link #install} listens to
 * {@link Window#getWindows()} and gives each window, as it appears, the stylesheet and the theme's class on
 * its scene root — which is where {@code dashboard.css} defines the {@code -bm-*} tokens every rule reads.
 *
 * <p>{@link #dialog} remains the call site's one line. The listener would theme a dialog anyway; what the
 * helper adds is the owner, which three dialogs in this module did not set, and which decides whether a
 * dialog opens over the window or somewhere else on another monitor.
 */
public final class Themed {

    private static final String STYLESHEET =
            Themed.class.getResource("/css/dashboard.css").toExternalForm();

    private static Theme current = Theme.DARK;

    private Themed() {
    }

    /** The preference, or the desktop's own scheme when there is none. */
    public static Theme resolve(Theme preference) {
        if (preference != null) {
            return preference;
        }
        try {
            return Platform.getPreferences().getColorScheme() == ColorScheme.LIGHT ? Theme.LIGHT : Theme.DARK;
        } catch (RuntimeException | LinkageError e) {
            return Theme.DARK;
        }
    }

    /** Once, from {@code DashboardApp.start}, before the first window is shown. */
    public static void install(Theme theme) {
        current = theme;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(Themed::decorate);
            }
        });
    }

    public static Theme current() {
        return current;
    }

    /** Switches every open window, then every window opened afterwards. */
    public static void set(Theme theme) {
        current = theme;
        List.copyOf(Window.getWindows()).forEach(Themed::restyle);
    }

    /** Sets the owner and themes the dialog; returns it, so a call site stays one expression. */
    public static <D extends Dialog<?>> D dialog(D dialog, Window owner) {
        if (owner != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
        }
        // The tokens arrive with the window, on its scene root; the stylesheet is added here as well so the
        // pane is sized with this file's paddings and fonts before it is first shown.
        if (!dialog.getDialogPane().getStylesheets().contains(STYLESHEET)) {
            dialog.getDialogPane().getStylesheets().add(STYLESHEET);
        }
        return dialog;
    }

    /** The main scene: the stylesheet, and the class on its root. */
    public static void scene(Scene scene) {
        if (!scene.getStylesheets().contains(STYLESHEET)) {
            scene.getStylesheets().add(STYLESHEET);
        }
        if (scene.getRoot() != null) {
            mark(scene.getRoot());
        }
    }

    private static void decorate(Window window) {
        restyle(window);
        // A window's scene may be set after it is listed.
        window.sceneProperty().addListener((obs, was, now) -> restyle(window));
    }

    private static void restyle(Window window) {
        if (window.getScene() != null) {
            scene(window.getScene());
        }
    }

    private static void mark(Parent node) {
        for (Theme theme : Theme.values()) {
            node.getStyleClass().remove(theme.styleClass());
        }
        node.getStyleClass().add(current.styleClass());
    }
}
