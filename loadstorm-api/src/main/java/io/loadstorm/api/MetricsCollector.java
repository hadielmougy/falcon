package io.loadstorm.api;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstraction for metrics collection.
 * Default implementation uses Micrometer, but can be swapped for OpenTelemetry.
 */
public interface MetricsCollector {

    /**
     * Record a successful action execution.
     *
     * @param actionName name of the action
     * @param duration   time taken to execute
     */
    void recordSuccess(String actionName, Duration duration);

    /**
     * Record a failed action execution.
     *
     * @param actionName name of the action
     * @param duration   time taken before failure
     * @param error      the exception that occurred
     */
    void recordFailure(String actionName, Duration duration, Throwable error);

    /**
     * Record the current number of active users for an action.
     */
    void recordActiveUsers(String actionName, int count);

    /**
     * Take a snapshot of all pool metrics.
     *
     * @return list of metric snapshots, one per action
     */
    List<PoolMetricsSnapshot> snapshot();

    /**
     * Register a listener that receives metric snapshots at the configured interval.
     */
    void onSnapshot(Consumer<List<PoolMetricsSnapshot>> listener);

    /**
     * Start periodic metrics collection.
     */
    void start(Duration interval);

    /**
     * Stop metrics collection.
     */
    void stop();
}
