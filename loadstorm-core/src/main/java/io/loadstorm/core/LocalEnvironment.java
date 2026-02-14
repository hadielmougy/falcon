package io.loadstorm.core;


import io.loadstorm.api.LoadTestClient;
import io.loadstorm.api.Environment;
import io.loadstorm.api.EnvironmentConfig;
import io.loadstorm.api.LoadTestRun;
import io.loadstorm.api.MetricsCollector;
import io.loadstorm.core.JsonLogWriter;
import io.loadstorm.core.MicrometerMetricsCollector;
import io.loadstorm.core.PoolManager;
import io.loadstorm.core.LoadTestRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local environment for running load tests directly from code.
 * Fully testable and designed to be completely covered by tests.
 * <p>
 * Usage:
 * <pre>{@code
 * var client = DefaultLoadTestClient.blocking();
 * client.execute("login", session -> { ... });
 * client.execute("browse", session -> { ... });
 *
 * var config = EnvironmentConfig.create()
 *     .numberOfUsers(100)
 *     .rampUpTime(Duration.ofSeconds(10))
 *     .testDuration(Duration.ofMinutes(1));
 *
 * var env = new LocalEnvironment(config);
 * LoadTestRun run = env.start(client);
 *
 * // Wait for completion
 * TestResult result = run.result().get();
 * }</pre>
 */
public class LocalEnvironment implements Environment {

    private static final Logger log = LoggerFactory.getLogger(LocalEnvironment.class);

    private final EnvironmentConfig config;
    private final MetricsCollector metricsCollector;
    private LoadTestRuntime currentRuntime;

    public LocalEnvironment(EnvironmentConfig config) {
        this(config, new MicrometerMetricsCollector());
    }

    public LocalEnvironment(EnvironmentConfig config, MetricsCollector metricsCollector) {
        this.config = config;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public LoadTestRun start(LoadTestClient client) {
        log.info("Starting local load test environment");

        PoolManager poolManager = new PoolManager(config, client.clientType());
        JsonLogWriter logWriter = new JsonLogWriter(config.logFilePath());

        currentRuntime = new LoadTestRuntime(config, client, poolManager, metricsCollector, logWriter);
        currentRuntime.execute();

        return currentRuntime;
    }

    @Override
    public EnvironmentConfig config() {
        return config;
    }

    @Override
    public MetricsCollector metricsCollector() {
        return metricsCollector;
    }

    @Override
    public void shutdown() {
        if (currentRuntime != null && currentRuntime.isRunning()) {
            currentRuntime.stop();
        }
        log.info("Local environment shut down");
    }
}
