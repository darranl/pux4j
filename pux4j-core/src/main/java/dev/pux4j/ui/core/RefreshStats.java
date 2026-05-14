// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Snapshot of a driver's refresh activity counters.
 * Returned by {@link EInkDisplayDriver#getRefreshStats()} and also passed to
 * {@link RefreshPolicy#shouldFullRefresh} so policies can make decisions based on
 * how many partials have accumulated since the last full refresh.
 *
 * <p>Counters reset to zero whenever a FULL refresh completes.
 */
public record RefreshStats(
    /** Number of PARTIAL refreshes since the last FULL refresh. */
    int partialRefreshCount,
    /** Number of FAST refreshes since the last FULL refresh. */
    int fastRefreshCount,
    /** Elapsed milliseconds since the last FULL refresh (or driver init if never). */
    long millisSinceLastFullRefresh
) {}
