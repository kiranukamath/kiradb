package io.kiradb.server.metrics;

/**
 * Immutable snapshot of one command's execution metrics, as reported by
 * {@link CommandMetrics#snapshot()}.
 *
 * <p>Latency fields are in microseconds. Percentiles are approximate — see the
 * reservoir-sampling disclosure on {@link CommandMetrics}.
 *
 * @param name        the upper-cased command name (e.g. {@code "SET"})
 * @param count       total number of executions
 * @param errors      number of executions that returned an error response
 * @param avgMicros   mean latency over all executions
 * @param p50Micros   approximate median latency
 * @param p95Micros   approximate 95th-percentile latency
 * @param p99Micros   approximate 99th-percentile latency
 */
public record CommandMetric(
        String name,
        long count,
        long errors,
        long avgMicros,
        long p50Micros,
        long p95Micros,
        long p99Micros) { }
