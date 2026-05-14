// SPDX-License-Identifier: Apache-2.0
package dev.pux4j.ui.core;

/**
 * Policy that decides whether to upgrade a pending write to a FULL refresh.
 *
 * <p>The driver calls {@link #shouldFullRefresh} before dispatching each
 * {@link EInkDisplayDriver#writeFrame} call. If the policy returns {@code true},
 * the driver executes a FULL refresh instead of the requested mode. The driver
 * chooses <em>which</em> FULL variant to use (slow vs. fast), keeping the policy
 * interface simple and the upgrade decision unambiguous.
 *
 * <p>Policies compose naturally with logical operators:
 * <pre>{@code
 * RefreshPolicy combined = policy1.or(policy2);
 * }</pre>
 *
 * <p>The default implementations are:
 * <ul>
 *   <li>{@link #NEVER} — never upgrade; app controls refresh mode explicitly.
 *   <li>{@link RefreshPolicy#afterPartials(int)} — upgrade after N consecutive
 *       partials to limit pixel-voltage drift.
 * </ul>
 */
@FunctionalInterface
public interface RefreshPolicy {

    /**
     * Return {@code true} to upgrade the pending write to a FULL refresh.
     *
     * @param stats   current refresh counters (resets after every FULL)
     * @param requested the mode the caller asked for
     */
    boolean shouldFullRefresh(RefreshStats stats, RefreshMode requested);

    /** Never upgrades — caller has full control over refresh mode. */
    RefreshPolicy NEVER = (stats, requested) -> false;

    /**
     * Upgrade to FULL after {@code threshold} consecutive PARTIAL refreshes.
     * This limits pixel-voltage drift that accumulates with repeated partials
     * — eInk panels that never see a full waveform cycle can develop ghost
     * images and uneven grey levels over time.
     *
     * @param threshold number of partials before a mandatory FULL refresh
     */
    static RefreshPolicy afterPartials(int threshold) {
        return (stats, requested) ->
            requested == RefreshMode.PARTIAL && stats.partialRefreshCount() >= threshold;
    }

    /** Returns a policy that upgrades if either {@code this} or {@code other} says to. */
    default RefreshPolicy or(RefreshPolicy other) {
        return (stats, requested) ->
            this.shouldFullRefresh(stats, requested) || other.shouldFullRefresh(stats, requested);
    }

    /** Returns a policy that upgrades only if both {@code this} and {@code other} say to. */
    default RefreshPolicy and(RefreshPolicy other) {
        return (stats, requested) ->
            this.shouldFullRefresh(stats, requested) && other.shouldFullRefresh(stats, requested);
    }
}
