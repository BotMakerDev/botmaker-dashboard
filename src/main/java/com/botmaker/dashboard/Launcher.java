package com.botmaker.dashboard;

/**
 * Plain entry point for the shaded jar and the jpackage app-image — Studio's {@code Launcher}, for this
 * module.
 *
 * <p>A class that extends {@link javafx.application.Application} cannot be the launch main class when the
 * JavaFX modules are on the classpath rather than the module path (the JVM aborts with "JavaFX runtime
 * components are missing"). This wrapper does not extend it and delegates to the real entry point.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        DashboardApp.main(args);
    }
}
