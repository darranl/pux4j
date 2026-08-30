// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.validation;

import java.util.Comparator;
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
     * <p>Named selection: finds the provider whose name matches {@code requestedName},
     * ignoring {@code availabilityCheck}. Throws if none match.
     *
     * <p>Auto-selection: filters by {@code availabilityCheck}, sorts by
     * {@code priorityExtractor} descending, and returns the highest-priority provider.
     * Throws if none are available after filtering.
     *
     * @param type               the service type to load
     * @param nameExtractor      extracts the provider's name for matching and diagnostics
     * @param priorityExtractor  extracts the provider's priority; higher values win
     * @param availabilityCheck  predicate that returns {@code true} if the provider can run
     *                           in the current environment; used only during auto-selection
     * @param requestedName      the name to select, or {@code null} to auto-select
     * @param label              human-readable label used in error messages (e.g. "display driver")
     * @return the selected provider
     * @throws IllegalStateException if no match is found or no provider is available
     */
    static <T> T selectProvider(
            Class<T> type,
            Function<T, String> nameExtractor,
            Function<T, Integer> priorityExtractor,
            Predicate<T> availabilityCheck,
            String requestedName,
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

        List<T> available = all.stream()
                .filter(availabilityCheck)
                .sorted(Comparator.comparingInt(priorityExtractor::apply).reversed())
                .toList();

        if (available.isEmpty()) {
            throw new IllegalStateException(
                    "No available " + label + " found via ServiceLoader. "
                    + "Providers present but none available in this environment: "
                    + all.stream().map(nameExtractor).toList());
        }
        return available.getFirst();
    }
}
