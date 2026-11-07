// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import java.util.Arrays;
import java.util.List;

/**
 * Picks the app to run from the (collision-resolved, see {@link AppCatalogue}) catalogue and the command line. A pure function of its inputs:
 * no {@code ServiceLoader}, no system properties, no JavaFX.
 *
 * <p>The first argument that does not start with {@code -} is the app name; everything after it
 * is the app's own arguments. With no such argument the name falls back to the
 * {@code pux4j.app} value passed in, and a single available app needs no name at all.
 *
 * @param entry   the chosen app, or {@code null} on failure
 * @param appArgs the arguments for the app, unchanged
 * @param exitCode {@code 0} when an app was chosen, {@code 2} for usage errors
 * @param error   the message to show on failure, otherwise {@code null}
 */
record AppSelection(AppEntry entry, String[] appArgs, int exitCode, String error) {

    static AppSelection select(List<AppEntry> apps, String[] args, String propertyName) {
        int nameIndex = -1;
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("-")) {
                nameIndex = i;
                break;
            }
        }
        String wanted = nameIndex >= 0 ? args[nameIndex] : propertyName;
        String[] appArgs = nameIndex >= 0
                ? Arrays.copyOfRange(args, nameIndex + 1, args.length)
                : args.clone();

        if (wanted != null) {
            for (AppEntry app : apps) {
                if (app.name().equals(wanted)) {
                    return new AppSelection(app, appArgs, 0, null);
                }
            }
            boolean clash = apps.stream().anyMatch(a -> a.renamed() && a.originalName().equals(wanted));
            return failure(2, (clash
                    ? "The name '" + wanted + "' is used by several apps; choose one by its listed name."
                    : "No app named '" + wanted + "'.") + "\n" + available(apps));
        }
        if (apps.size() == 1) {
            return new AppSelection(apps.get(0), appArgs, 0, null);
        }
        if (apps.isEmpty()) {
            return failure(2, "No apps found on the module path.");
        }
        return failure(2, "Several apps are available; choose one by name: pux4j <app> [args...]\n"
                + available(apps));
    }

    private static String available(List<AppEntry> apps) {
        if (apps.isEmpty()) {
            return "No apps found on the module path.";
        }
        return "Available apps:\n" + AppList.format(apps);
    }

    private static AppSelection failure(int code, String message) {
        return new AppSelection(null, new String[0], code, message);
    }
}
