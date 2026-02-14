package io.loadstorm.api;

import java.time.Duration;
import java.time.Instant;

/**
 * A single execution record written to the log file.
 */
public record ExecutionRecord(
        String actionName,
        String sessionId,
        Instant timestamp,
        Duration responseTime,
        boolean success,
        String errorMessage
) {}
