// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import java.util.List;

/** Formats the {@code --list} output. Pure, so it is testable without a module path. */
final class AppList {

    private AppList() {
    }

    /** One line per app: name padded to a column, the kind, then the description if any. */
    static String format(List<AppEntry> apps) {
        int width = apps.stream().mapToInt(a -> a.name().length()).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (AppEntry app : apps) {
            String line = "  " + pad(app.name(), width) + "  " + app.kind().label();
            if (app.renamed()) {
                line += "  (name '" + app.originalName() + "' clashes)";
            }
            if (!app.description().isEmpty()) {
                line += "  " + app.description();
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /**
     * The footer naming the driver factories on the module path, which records which HAT (or the
     * emulator) a bundle was built for. Names only; nothing is probed or created.
     */
    static String footer(List<String> displayDrivers, List<String> touchDrivers) {
        return "Display drivers: " + names(displayDrivers) + "; touch drivers: " + names(touchDrivers) + "\n";
    }

    private static String names(List<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String pad(String s, int width) {
        return s + " ".repeat(width - s.length());
    }
}
