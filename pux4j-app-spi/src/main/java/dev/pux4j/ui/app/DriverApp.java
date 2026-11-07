// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.app;

/**
 * A non-JavaFX application that the {@code pux4j} launcher can run by name, for example a
 * hardware test that talks to the drivers directly. Implementations are discovered with
 * {@link java.util.ServiceLoader}, so a module declares
 * {@code provides dev.pux4j.ui.app.DriverApp with ...} and needs a public class with a public
 * no-argument constructor (or a public static {@code provider()} method).
 *
 * <p>The launcher runs a driver app on the main thread. It does not start JavaFX and does not
 * create an {@code EInkBridge}; the app is responsible for creating and closing whatever
 * drivers it uses. Names share one name space with the JavaFX apps the launcher lists, so a
 * name must be unique across all kinds of app on the module path.
 */
public interface DriverApp {

    /**
     * The name the app is selected by on the launcher command line ({@code pux4j <name>}).
     * Must be non-empty, must not start with {@code -}, and must not contain whitespace.
     *
     * @return the unique app name
     */
    String name();

    /**
     * A one-line, human-readable description shown by {@code pux4j --list}.
     *
     * @return the description, possibly empty
     */
    String description();

    /**
     * Runs the app.
     *
     * @param args the command-line arguments that followed the app name, passed through
     *             unchanged (an empty array if there were none)
     * @return the process exit code; {@code 0} means success
     * @throws Exception on failure; the launcher logs it and exits with code 1
     */
    int run(String[] args) throws Exception;
}
