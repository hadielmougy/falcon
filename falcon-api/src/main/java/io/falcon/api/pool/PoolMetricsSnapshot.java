package io.falcon.api.pool;

import java.time.Instant;

/**
 * Snapshot of pool metrics at a point in time.
 * Collected every second and used for dashboard display.
 */
public record PoolMetricsSnapshot(
        String actionName,
        int activeCount,
        int maxSize,
        int waitingCount,
        long completedCount,
        long failedCount,
        double averageResponseTimeMs,
        double p99ResponseTimeMs,
        double requestsPerSecond,
        Instant timestamp
) {}
