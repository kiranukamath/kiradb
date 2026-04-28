package io.kiradb.services.flags;

/**
 * Snapshot of impression and conversion counts for one flag.
 *
 * <p>Collected by {@link FlagStore} on every {@code FLAG.GET} (impressions) and
 * {@code FLAG.CONVERT} (conversions). Tracked separately for the enabled and
 * disabled cohorts so a future bandit (Phase 14) can compare conversion rates.
 *
 * <p>{@code conversionRate} returns -1 when impressions are zero so callers can
 * distinguish "no data" from "0% conversion rate".
 *
 * @param enabledImpressions  count of {@code FLAG.GET} calls where the user landed in the enabled cohort
 * @param disabledImpressions count of {@code FLAG.GET} calls where the user landed in the disabled cohort
 * @param enabledConversions  count of {@code FLAG.CONVERT} calls for users in the enabled cohort
 * @param disabledConversions count of {@code FLAG.CONVERT} calls for users in the disabled cohort
 */
public record FlagStats(
        long enabledImpressions,
        long disabledImpressions,
        long enabledConversions,
        long disabledConversions) {

    /**
     * @return enabled-cohort conversion rate, or -1.0 if there are no enabled impressions
     */
    public double enabledConversionRate() {
        if (enabledImpressions == 0) {
            return -1.0;
        }
        return (double) enabledConversions / enabledImpressions;
    }

    /**
     * @return disabled-cohort conversion rate, or -1.0 if there are no disabled impressions
     */
    public double disabledConversionRate() {
        if (disabledImpressions == 0) {
            return -1.0;
        }
        return (double) disabledConversions / disabledImpressions;
    }
}
