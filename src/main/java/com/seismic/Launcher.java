package com.seismic;

/**
 * Entry point of the runnable jar. A main class that does not extend Application lets the JavaFX classes be
 * loaded from the classpath of a single "fat" jar (the JavaFX launcher otherwise insists on modules).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
