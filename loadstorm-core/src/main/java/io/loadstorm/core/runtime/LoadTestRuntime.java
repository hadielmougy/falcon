package io.loadstorm.core.runtime;

import io.loadstorm.api.action.ActionChain;
import io.loadstorm.api.action.ActionDefinition;
import io.loadstorm.api.client.LoadTestClient;
import io.loadstorm.api.environment.EnvironmentConfig;
import io.loadstorm.api.environment.LoadTestRun;
import io.loadstorm.api.runtime.TestResult;
import io.loadstorm.api.metrics.MetricsCollector;
import io.loadstorm.api.pool.ActionPool;
import io.loadstorm.api.pool.PoolMetricsSnapshot;
import io.loadstorm.core.client.DefaultSession;
import io.loadstorm.core.pool.PoolManager;
import io.loadstorm.core.report.CsvReportGenerator;
import io.loadstorm.core.report.HtmlReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The runtime engine that executes the load test.
 * <p>
 * Maintains a sustained number of concurrent virtual users. Each user loops
 * through the action chain continuously: when a user completes the chain,
 * it immediately restarts from the first action. During ramp-up, users are
 * added gradually until the target count is reached.
 * <p>
 * This ensures that if you configure 100 users, there are always ~100 users
 * actively executing actions at any point in time.
 */
public class LoadTestRuntime implements LoadTestRun {

    private static final Logger log = LoggerFactory.getLogger(LoadTestRuntime.class);

    private final EnvironmentConfig config;
    private final LoadTestClient client;
    private final PoolManager poolManager;
    private final MetricsCollector metricsCollector;
    private final ActionChain actionChain;

    private final AtomicReference<TestState> state = new AtomicReference<>(TestState.RAMPING_UP);
    private final AtomicInteger activeUserCount = new AtomicInteger(0);
    private final AtomicInteger spawnedUserCount = new AtomicInteger(0);
    private final CompletableFuture<TestResult> resultFuture = new CompletableFuture<>();
    private final List<PoolMetricsSnapshot> allSnapshots = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;
    private Instant startTime;

    public LoadTestRuntime(EnvironmentConfig config, LoadTestClient client,
                           PoolManager poolManager, MetricsCollector metricsCollector) {
        this.config = config;
        this.client = client;
        this.poolManager = poolManager;
        this.metricsCollector = metricsCollector;
        this.actionChain = client.actionChain();
    }

    /**
     * Start the load test execution.
     */
    public void execute() {
        startTime = Instant.now();
        log.info("Starting load test: {} users, ramp-up: {}s, duration: {}s, actions: {}",
                config.numberOfUsers(), config.rampUpTime().toSeconds(),
                config.testDuration().toSeconds(), actionChain.size());

        // Initialize pools for each action
        poolManager.initialize(actionChain);

        // Start metrics collection
        metricsCollector.onSnapshot(snapshots -> allSnapshots.addAll(snapshots));
        metricsCollector.start(config.metricsInterval());

        // Scheduler for ramp-up, metrics updates, and test completion
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "loadstorm-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduleRampUp();
        scheduleMetricsUpdate();
        scheduleTestCompletion();
    }

    /**
     * Gradually spawn users during ramp-up, then maintain the target count.
     * Each tick calculates how many users should be active and spawns the deficit.
     */
    private void scheduleRampUp() {
        long rampUpMs = config.rampUpTime().toMillis();
        int totalUsers = config.numberOfUsers();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (state.get() == TestState.STOPPING || state.get() == TestState.COMPLETED) {
                    return;
                }

                long elapsedMs = Duration.between(startTime, Instant.now()).toMillis();
                int targetUsers;

                if (elapsedMs < rampUpMs) {
                    // Ramp-up: linearly increase target
                    double progress = (double) elapsedMs / rampUpMs;
                    targetUsers = (int) Math.ceil(totalUsers * progress);
                } else {
                    targetUsers = totalUsers;
                    state.compareAndSet(TestState.RAMPING_UP, TestState.RUNNING);
                }

                // Spawn deficit: only add users we haven't spawned yet
                int currentlySpawned = spawnedUserCount.get();
                int toSpawn = targetUsers - currentlySpawned;

                for (int i = 0; i < toSpawn; i++) {
                    spawnedUserCount.incrementAndGet();
                    spawnUser();
                }

            } catch (Exception e) {
                log.error("Error in ramp-up scheduler", e);
            }
        }, 0, 200, TimeUnit.MILLISECONDS); // check every 200ms for smoother ramp-up
    }

    /**
     * Spawn a single virtual user that loops through the action chain continuously.
     * When the chain completes (or fails), the user restarts from the first action.
     */
    private void spawnUser() {
        DefaultSession session = new DefaultSession();
        activeUserCount.incrementAndGet();
        executeActionChain(session, 0);
    }

    /**
     * Execute the action chain for a user, moving from one pool to the next.
     * When action at index N completes, action at index N+1 starts.
     * When the chain is complete, the user loops back to action 0 (continuous load).
     */
    private void executeActionChain(DefaultSession session, int actionIndex) {
        // Chain complete → loop back to start with a fresh session
        if (actionIndex >= actionChain.size()) {
            if (isRunning()) {
                executeActionChain(new DefaultSession(), 0);
            } else {
                activeUserCount.decrementAndGet();
            }
            return;
        }

        if (!isRunning()) {
            activeUserCount.decrementAndGet();
            return;
        }

        ActionDefinition actionDef = actionChain.get(actionIndex);
        ActionPool pool = poolManager.pool(actionDef.name());

        pool.submit(() -> {
            Instant actionStart = Instant.now();
            try {
                actionDef.action().execute(session);
                Duration duration = Duration.between(actionStart, Instant.now());
                metricsCollector.recordSuccess(actionDef.name(), duration);

                // Move to the next action in the chain
                executeActionChain(session, actionIndex + 1);

            } catch (Exception e) {
                Duration duration = Duration.between(actionStart, Instant.now());
                metricsCollector.recordFailure(actionDef.name(), duration, e);

                log.debug("Action '{}' failed for session {}: {}", actionDef.name(), session.sessionId(), e.getMessage());

                // On failure, restart the chain from the beginning with a fresh session
                if (isRunning()) {
                    executeActionChain(new DefaultSession(), 0);
                } else {
                    activeUserCount.decrementAndGet();
                }
            }
        });
    }

    /**
     * Periodically update active user metrics from the actual pool counts.
     */
    private void scheduleMetricsUpdate() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (ActionPool pool : poolManager.allPools()) {
                    metricsCollector.recordActiveUsers(pool.actionName(), pool.activeCount());
                }
            } catch (Exception e) {
                log.debug("Error updating pool metrics", e);
            }
        }, 500, 1000, TimeUnit.MILLISECONDS);
    }

    private void scheduleTestCompletion() {
        scheduler.schedule(() -> {
            log.info("Test duration reached, stopping...");
            stop();
        }, config.testDuration().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isRunning() {
        TestState s = state.get();
        return s == TestState.RAMPING_UP || s == TestState.RUNNING;
    }

    @Override
    public void stop() {
        if (state.compareAndSet(TestState.RAMPING_UP, TestState.STOPPING) ||
                state.compareAndSet(TestState.RUNNING, TestState.STOPPING)) {

            log.info("Stopping load test...");

            scheduler.shutdown();
            poolManager.shutdown();
            metricsCollector.stop();

            Instant endTime = Instant.now();
            TestResult result = buildResult(endTime);

            // Generate reports if configured
            generateReports(result);

            state.set(TestState.COMPLETED);
            resultFuture.complete(result);

            log.info("Load test completed. Duration: {}s", Duration.between(startTime, endTime).toSeconds());
        }
    }

    private void generateReports(TestResult result) {
        String reportPath = config.reportPath();
        if (reportPath == null || reportPath.isBlank()) {
            return;
        }

        try {
            log.info("Generating load test reports...");
            // HTML report
            Path htmlPath = Path.of(reportPath.endsWith(".html") ? reportPath : reportPath + ".html");
            new HtmlReportGenerator().generate(result, htmlPath);

            // CSV report alongside
            Path csvPath = Path.of(reportPath.replaceFirst("\\.[^.]+$", "") + ".csv");
            new CsvReportGenerator().generate(result, csvPath);
            log.info("Generated load test reports...");
        } catch (Exception e) {
            log.error("Failed to generate reports", e);
        }
    }

    private TestResult buildResult(Instant endTime) {
        Map<String, TestResult.ActionSummary> summaries = new LinkedHashMap<>();

        for (PoolMetricsSnapshot snapshot : allSnapshots) {
            summaries.put(snapshot.actionName(), new TestResult.ActionSummary(
                    snapshot.actionName(),
                    snapshot.completedCount() + snapshot.failedCount(),
                    snapshot.completedCount(),
                    snapshot.failedCount(),
                    snapshot.averageResponseTimeMs(),
                    0, 0,
                    snapshot.p99ResponseTimeMs(),
                    0,
                    snapshot.requestsPerSecond()
            ));
        }

        return new TestResult(
                startTime,
                endTime,
                Duration.between(startTime, endTime),
                config.numberOfUsers(),
                new ArrayList<>(summaries.values()),
                new ArrayList<>(allSnapshots)
        );
    }

    @Override
    public CompletableFuture<TestResult> result() {
        return resultFuture;
    }

    @Override
    public int activeUsers() {
        return activeUserCount.get();
    }

    @Override
    public TestState state() {
        return state.get();
    }
}