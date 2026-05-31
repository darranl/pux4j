// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Utility for selecting a single ServiceLoader provider with consistent error reporting.
 */
class ServiceLoaderUtil {

    private ServiceLoaderUtil() {}

    /**
     * Loads all providers of {@code type} and selects one by name or auto-selection.
     *
     * @param type           the service type to load
     * @param nameExtractor  extracts the provider's name for matching and diagnostics
     * @param requestedName  the name to select, or {@code null} to auto-select
     * @param excludeFilter  predicate to exclude providers during auto-selection; {@code null} includes all
     * @param label          human-readable label used in error messages (e.g. "display driver")
     * @return the selected provider
     * @throws IllegalStateException if no match is found or auto-selection is ambiguous
     */
    static <T> T selectProvider(
            Class<T> type,
            Function<T, String> nameExtractor,
            String requestedName,
            Predicate<T> excludeFilter,
            String label) {

        List<T> all = ServiceLoader.load(type)
                .stream()
                .map(ServiceLoader.Provider::get)
                .toList();

        if (requestedName != null) {
            return all.stream()
                    .filter(p -> nameExtractor.apply(p).equals(requestedName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No " + label + " named '" + requestedName + "' found via ServiceLoader. "
                            + "Available: " + all.stream().map(nameExtractor).toList()));
        }

        List<T> candidates = (excludeFilter != null)
                ? all.stream().filter(excludeFilter.negate()).toList()
                : all;

        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalStateException(
                "No " + label + " name given; expected 1 provider but found " + candidates.size()
                + ": " + candidates.stream().map(nameExtractor).toList()
                + ". Specify one explicitly.");
    }
}
