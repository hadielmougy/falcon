package io.loadstorm.api.environment;

import io.loadstorm.api.metrics.MetricsCollector;
import io.loadstorm.api.client.LoadTestClient;

/**
 * The Environment starts the load test, sets the number of users,
 * manages ramp-up, and configures internal connection pools.
 * <p>
 * Two types exist: Local (for testing/development) and Web (extends local
 * to accept parameters from a web client).
 */
public interface Environment {

    /**
     * Start the load test with the given client and configuration.
     *
     * @param client the client with registered actions
     * @return a handle to monitor and control the running test
     */
    LoadTestRun start(LoadTestClient client);

    /**
     * @return the environment configuration
     */
    EnvironmentConfig config();

    /**
     * @return the metrics collector used by this environment
     */
    MetricsCollector metricsCollector();

    /**
     * Stop the environment and release all resources.
     */
    void shutdown();
}
