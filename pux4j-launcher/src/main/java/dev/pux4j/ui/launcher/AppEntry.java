// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

/**
 * One runnable app in the launcher's catalogue, whatever its kind. The catalogue is a list of
 * these sharing a single name space; selection and listing work on this type only, so they need
 * neither {@code ServiceLoader} nor JavaFX.
 *
 * @param kind        how the app is run
 * @param name        the name it is selected by on the command line (see {@link AppCatalogue})
 * @param description one-line description, possibly empty
 * @param source      the provider object ({@code SceneContent} or {@code DriverApp})
 * @param originalName the name the app itself reports; differs from {@code name} only when renamed
 */
record AppEntry(Kind kind, String name, String description, Object source, String originalName) {

    AppEntry(Kind kind, String name, String description, Object source) {
        this(kind, name, description, source, name);
    }

    boolean renamed() {
        return !name.equals(originalName);
    }

    AppEntry withName(String newName) {
        return new AppEntry(kind, newName, description, source, originalName);
    }

    /** The kinds of app the launcher can run. */
    enum Kind {
        /** A {@code SceneContent}, run on an {@code EInkBridge}. */
        JAVAFX("JavaFX app"),
        /** A {@code DriverApp}, run directly on the main thread. */
        DRIVER("driver test");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }
}
