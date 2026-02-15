package io.falcon.web.environment;

import io.falcon.api.client.LoadTestClient;
import io.falcon.api.environment.EnvironmentConfig;
import io.falcon.api.environment.LoadTestRun;
import io.falcon.api.metrics.MetricsCollector;
import io.falcon.api.pool.PoolMetricsSnapshot;
import io.falcon.api.runtime.ShutdownListener;
import io.falcon.core.environment.EnvironmentBase;
import io.falcon.web.server.FalconWebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Web environment that extends the local environment.
 * Accepts parameters from the web client and starts an embedded Tomcat server
 * that sends metrics via Server-Sent Events every second.
 * <p>
 * The web environment belongs to the web module and adds:
 * - Embedded Tomcat for the dashboard
 * - SSE endpoint for real-time metrics
 * - REST API to configure and start tests from the web UI
 */
public class WebEnvironment extends EnvironmentBase implements ShutdownListener {

    private static final Logger log = LoggerFactory.getLogger(WebEnvironment.class);

    private final int port;
    private FalconWebServer webServer;
    private LoadTestClient registeredClient;

    public WebEnvironment(EnvironmentConfig config, int port) {
        super(config);
        this.port = port;
    }

    public WebEnvironment(EnvironmentConfig config, MetricsCollector metricsCollector, int port) {
        super(config, metricsCollector);
        this.port = port;
    }

    /**
     * Register a client that can be started via the web UI.
     */
    public WebEnvironment registerClient(LoadTestClient client) {
        this.registeredClient = client;
        return this;
    }

    /**
     * Start the web server. The load test can be started via the web UI
     * or programmatically via {@link #start(LoadTestClient)}.
     */
    public void startServer() {
        webServer = new FalconWebServer(this, port);
        webServer.start();
        log.info("Falcon web dashboard available at http://localhost:{}", port);
    }

    @Override
    public LoadTestRun start(LoadTestClient client) {
        LoadTestRun run = super.start(client);

        // Register SSE listener for real-time metrics
        metricsCollector().onSnapshot(this::broadcastMetrics);
        super.registerShutdownListener(this);

        return run;
    }

    /**
     * Start the test using the pre-registered client.
     */
    public LoadTestRun startRegistered() {
        if (registeredClient == null) {
            throw new IllegalStateException("No client registered. Call registerClient() first.");
        }
        return start(registeredClient);
    }

    /**
     * Apply new configuration from the web client.
     */
    public void applyWebConfig(EnvironmentConfig webConfig) {
        // The web UI can send updated config; this applies it for the next test run.
        // Since EnvironmentConfig is mutable, the web handler can modify it directly.
        log.info("Configuration updated from web client: {} users, {}s ramp-up",
                webConfig.numberOfUsers(), webConfig.rampUpTime().toSeconds());
    }

    private void broadcastMetrics(List<PoolMetricsSnapshot> snapshots) {
        if (webServer != null) {
            webServer.broadcastMetrics(snapshots);
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        if (webServer != null) {
            webServer.stop();
        }
        log.info("Web environment shut down");
    }

    public int port() {
        return port;
    }

    @Override
    public void stop() {
        if (webServer != null) {
            webServer.stop();
        }
        log.info("Server stop");
    }
}
