// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import dev.pux4j.ui.app.DriverApp;
import dev.pux4j.ui.core.DisplayDriverFactory;
import dev.pux4j.ui.core.TouchDriverFactory;
import dev.pux4j.ui.fx.SceneContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Runs an app found on the module path: {@code pux4j <app> [args...]}. Two kinds of app share
 * one name space:
 * <ul>
 *   <li>JavaFX apps ({@link SceneContent}), run on an {@code EInkBridge} with the drivers chosen
 *       by the {@code pux4j.*} system properties; extra arguments are ignored with a warning;</li>
 *   <li>driver apps ({@link DriverApp}), such as the hardware tests, run directly on the main
 *       thread with everything after the name passed to them unchanged. JavaFX is never started
 *       for these.</li>
 * </ul>
 * The app is the first argument not starting with {@code -}, or {@code -Dpux4j.app=<name>};
 * with only one app available no name is needed. {@code --list} prints the apps and the driver
 * factories on the module path, then exits.
 *
 * <p>Apps reporting the same name are selected by their class name instead (see
 * {@link AppCatalogue}). Exit codes: 0 success, 1 failure, 2 usage error.
 *
 * <p>For a JavaFX app the main thread stays alive for the whole run: drivers such as the
 * emulator's tie their window's lifetime to the thread that created them.
 */
public final class EInkLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(EInkLauncher.class);

    private EInkLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args, findApps(), driverFooter(), System.getProperty("pux4j.app"),
                System.out, System.err, EInkLauncher::runFx);
        System.exit(code);
    }

    /** Starts a JavaFX app; returns an exit code (only on a failed start). */
    @FunctionalInterface
    interface FxRunner {
        int run(Object sceneContent) throws Exception;
    }

    /**
     * The launcher proper, with its environment injected so it can be tested without a module
     * path, a toolkit or a process exit.
     *
     * @param rawApps     the catalogue as reported by the apps; colliding names are resolved here
     * @param footer      the driver-factory footer printed after the list
     * @param propertyApp the {@code pux4j.app} property value, or {@code null}
     * @param fx          how to run a JavaFX app (never called for a driver app)
     * @return the process exit code
     */
    static int run(String[] args, List<AppEntry> rawApps, String footer, String propertyApp,
                   PrintStream out, PrintStream err, FxRunner fx) {
        List<AppEntry> apps = AppCatalogue.resolve(rawApps);
        for (AppEntry app : apps) {
            if (app.renamed()) {
                LOG.warn("App name '{}' is used by several apps; this one is selected as '{}'",
                        app.originalName(), app.name());
            }
        }
        if (args.length > 0 && args[0].equals("--list")) {
            out.print(AppList.format(apps));
            out.print(footer);
            return 0;
        }
        AppSelection selection = AppSelection.select(apps, args, propertyApp);
        if (selection.entry() == null) {
            err.println(selection.error());
            return selection.exitCode();
        }
        AppEntry app = selection.entry();
        try {
            if (app.kind() == AppEntry.Kind.DRIVER) {
                return DriverAppRunner.run((DriverApp) app.source(), selection.appArgs());
            }
            if (selection.appArgs().length > 0) {
                LOG.warn("Ignoring arguments for JavaFX app '{}': {}", app.name(),
                        List.of(selection.appArgs()));
            }
            return fx.run(app.source());
        } catch (Throwable t) {
            LOG.error("App '{}' failed", app.name(), t);
            return 1;
        }
    }

    private static int runFx(Object content) throws Exception {
        return FxAppRunner.run((SceneContent) content);
    }

    /**
     * Sets each application-supplied default as a system property unless the user has already set
     * it, so an explicit {@code -D} option always wins. Must run before the bridge is built.
     */
    static void applyDefaultProperties(Map<String, String> defaults) {
        defaults.forEach((key, value) -> {
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
                LOG.info("Application default {}={}", key, value);
            } else {
                LOG.info("Application default {} not applied; already set to {}", key, System.getProperty(key));
            }
        });
    }

    private static List<AppEntry> findApps() {
        List<AppEntry> found = new ArrayList<>();
        for (SceneContent c : ServiceLoader.load(SceneContent.class)) {
            found.add(new AppEntry(AppEntry.Kind.JAVAFX, c.name(), "", c));
        }
        for (DriverApp d : ServiceLoader.load(DriverApp.class)) {
            found.add(new AppEntry(AppEntry.Kind.DRIVER, d.name(), d.description(), d));
        }
        return found;
    }

    private static String driverFooter() {
        return AppList.footer(
                names(DisplayDriverFactory.class, DisplayDriverFactory::name),
                names(TouchDriverFactory.class, TouchDriverFactory::name));
    }

    private static <T> List<String> names(Class<T> type, Function<T, String> name) {
        List<String> names = new ArrayList<>();
        ServiceLoader.load(type).forEach(f -> names.add(name.apply(f)));
        return names.stream().distinct().sorted().toList();
    }
}
