package io.falcon.core.environment;


import io.falcon.api.client.LoadTestClient;
import io.falcon.api.environment.Environment;
import io.falcon.api.environment.EnvironmentConfig;
import io.falcon.api.environment.LoadTestRun;
import io.falcon.api.metrics.MetricsCollector;
import io.falcon.api.runtime.ShutdownListener;
import io.falcon.core.metrics.MicrometerMetricsCollector;
import io.falcon.core.pool.PoolManager;
import io.falcon.core.runtime.LoadTestRuntime;
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
public class EnvironmentBase implements Environment {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentBase.class);

    private final EnvironmentConfig config;
    private final MetricsCollector metricsCollector;
    private LoadTestRuntime currentRuntime;

    public EnvironmentBase(EnvironmentConfig config) {
        this(config, new MicrometerMetricsCollector());
    }

    public EnvironmentBase(EnvironmentConfig config, MetricsCollector metricsCollector) {
        this.config = config;
        this.metricsCollector = metricsCollector;
    }

    @Override
    public LoadTestRun start(LoadTestClient client) {
        log.info("Starting local load test environment");

        PoolManager poolManager = new PoolManager(config, client.clientType());

        currentRuntime = new LoadTestRuntime(config, client, poolManager, metricsCollector);
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

    protected void registerShutdownListener(ShutdownListener listener) {
        currentRuntime.onShutdown(listener);
    }
}
