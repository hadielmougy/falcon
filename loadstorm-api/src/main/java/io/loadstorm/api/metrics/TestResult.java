package io.loadstorm.api.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Complete result of a load test run.
 * Contains all metric snapshots that can be displayed as a chart.
 */
public record TestResult(
        Instant startTime,
        Instant endTime,
        Duration totalDuration,
        int configuredUsers,
        List<ActionSummary> actionSummaries,
        List<PoolMetricsSnapshot> timeSeriesSnapshots
) {

    /**
     * Summary statistics for a single action type.
     */
    public record ActionSummary(
            String actionName,
            long totalRequests,
            long successCount,
            long failureCount,
            double averageResponseTimeMs,
            double p50ResponseTimeMs,
            double p95ResponseTimeMs,
            double p99ResponseTimeMs,
            double maxResponseTimeMs,
            double requestsPerSecond
    ) {}
}

