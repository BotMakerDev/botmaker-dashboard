package com.botmaker.dashboard.ui;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.testfx.framework.junit5.ApplicationTest;

/**
 * The base of every test here that has to draw: TestFX over Monocle's Headless platform, so no display is
 * needed — Studio's {@code FxHeadlessTest}, for this module.
 *
 * <p>The properties are set by Surefire; the static block repeats them for a run from an IDE, and they must be
 * in place before the toolkit starts. {@link #show} puts a node in a scene carrying the real stylesheet and a
 * theme class, so a style class asserted on is one the stylesheet actually styles.
 */
public abstract class FxHeadless extends ApplicationTest {

    static {
        System.setProperty("testfx.robot", "glass");
        System.setProperty("testfx.headless", "true");
        System.setProperty("glass.platform", "Monocle");
        System.setProperty("monocle.platform", "Headless");
        System.setProperty("prism.order", "sw");
        System.setProperty("prism.text", "t2k");
        System.setProperty("java.awt.headless", "true");
    }

    protected Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
    }

    /** Shows {@code root} in a themed 1100×720 scene. Call from the FX thread, as {@code interact} does. */
    protected void show(Parent root) {
        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(FxHeadless.class.getResource("/css/dashboard.css").toExternalForm());
        root.getStyleClass().addAll("app-root", "theme-dark");
        stage.setScene(scene);
        stage.show();
    }
}
