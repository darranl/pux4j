// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves name collisions in the catalogue. Pure: no {@code ServiceLoader}, no JavaFX.
 *
 * <p>Apps whose {@code name()} is unique keep it. When two or more apps (of either kind) report
 * the same name, each of them is selected by the simple name of its implementation class
 * instead; if those still collide, the colliding apps use their fully qualified class names.
 * This is resolved per collision group, not globally.
 */
final class AppCatalogue {

    private AppCatalogue() {
    }

    /** Returns the catalogue with colliding apps renamed to their usable command-line names. */
    static List<AppEntry> resolve(List<AppEntry> apps) {
        Map<String, List<AppEntry>> byName = new LinkedHashMap<>();
        for (AppEntry app : apps) {
            byName.computeIfAbsent(app.name(), k -> new ArrayList<>()).add(app);
        }
        Map<AppEntry, String> renamed = new java.util.IdentityHashMap<>();
        for (List<AppEntry> group : byName.values()) {
            if (group.size() < 2) {
                continue;
            }
            Map<String, List<AppEntry>> bySimple = new LinkedHashMap<>();
            for (AppEntry app : group) {
                bySimple.computeIfAbsent(simpleName(app), k -> new ArrayList<>()).add(app);
            }
            for (Map.Entry<String, List<AppEntry>> e : bySimple.entrySet()) {
                for (AppEntry app : e.getValue()) {
                    renamed.put(app, e.getValue().size() > 1 ? app.source().getClass().getName() : e.getKey());
                }
            }
        }
        List<AppEntry> result = new ArrayList<>();
        for (AppEntry app : apps) {
            String name = renamed.get(app);
            result.add(name == null ? app : app.withName(name));
        }
        return result;
    }

    private static String simpleName(AppEntry app) {
        String simple = app.source().getClass().getSimpleName();
        return simple.isEmpty() ? app.source().getClass().getName() : simple;
    }
}
