package io.kiradb.client;

/**
 * Per-cohort impression and conversion metrics for one feature flag
 * ({@code FLAG.STATS} reply).
 *
 * @param enabledImpressions     evaluations that returned enabled
 * @param disabledImpressions    evaluations that returned disabled
 * @param enabledConversions     conversions attributed to the enabled cohort
 * @param disabledConversions    conversions attributed to the disabled cohort
 * @param enabledConversionRate  conversions / impressions for the enabled cohort;
 *                               {@code -1.0} when there are no impressions yet
 * @param disabledConversionRate conversions / impressions for the disabled cohort;
 *                               {@code -1.0} when there are no impressions yet
 */
public record FlagStats(
        long enabledImpressions,
        long disabledImpressions,
        long enabledConversions,
        long disabledConversions,
        double enabledConversionRate,
        double disabledConversionRate) {
}
