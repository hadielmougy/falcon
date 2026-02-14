package io.loadstorm.core;

import io.loadstorm.api.ActionChain;
import io.loadstorm.api.ActionDefinition;
import io.loadstorm.api.LoadTestClient;
import io.loadstorm.api.EnvironmentConfig;
import io.loadstorm.api.LoadTestRun;
import io.loadstorm.api.ExecutionRecord;
import io.loadstorm.api.LogWriter;
import io.loadstorm.api.TestResult;
import io.loadstorm.api.MetricsCollector;
import io.loadstorm.api.ActionPool;
import io.loadstorm.api.PoolMetricsSnapshot;
import io.loadstorm.core.DefaultSession;
import io.loadstorm.core.PoolManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The runtime engine that executes the load test.
 * <p>
 * Every second, the configured number of users execute the registered action(s).
 * Actions are chained: when the first finishes for a user, the second starts.
 * The runtime manages ramp-up, steady state, and shutdown.
 */
public class LoadTestRuntime implements LoadTestRun {

    private static final Logger log = LoggerFactory.getLogger(LoadTestRuntime.class);

    private final EnvironmentConfig config;
    private final LoadTestClient client;
    private final PoolManager poolManager;
    private final MetricsCollector metricsCollector;
    private final LogWriter logWriter;
    private final ActionChain actionChain;

    private final AtomicReference<TestState> state = new AtomicReference<>(TestState.RAMPING_UP);
    private final AtomicInteger activeUserCount = new AtomicInteger(0);
    private final CompletableFuture<TestResult> resultFuture = new CompletableFuture<>();
    private final List<PoolMetricsSnapshot> allSnapshots = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;
    private Instant startTime;

    public LoadTestRuntime(EnvironmentConfig config, LoadTestClient client,
                           PoolManager poolManager, MetricsCollector metricsCollector,
                           LogWriter logWriter) {
        this.config = config;
        this.client = client;
        this.poolManager = poolManager;
        this.metricsCollector = metricsCollector;
        this.logWriter = logWriter;
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

        // Schedule user spawning with ramp-up
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "loadstorm-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduleUserSpawning();
        scheduleTestCompletion();
    }

    private void scheduleUserSpawning() {
        long rampUpMs = config.rampUpTime().toMillis();
        int totalUsers = config.numberOfUsers();

        // Calculate how many users to add per second during ramp-up
        long rampUpSeconds = Math.max(1, rampUpMs / 1000);
        int usersPerTick = Math.max(1, totalUsers / (int) rampUpSeconds);

        // Every second, spawn a batch of users
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (state.get() == TestState.STOPPING || state.get() == TestState.COMPLETED) {
                    return;
                }

                int currentTarget;
                long elapsedMs = Duration.between(startTime, Instant.now()).toMillis();

                if (elapsedMs < rampUpMs) {
                    // Ramp-up phase: linearly increase users
                    double progress = (double) elapsedMs / rampUpMs;
                    currentTarget = (int) Math.ceil(totalUsers * progress);
                    state.compareAndSet(TestState.RAMPING_UP, TestState.RAMPING_UP);
                } else {
                    // Steady state
                    currentTarget = totalUsers;
                    state.compareAndSet(TestState.RAMPING_UP, TestState.RUNNING);
                }

                // Spawn users up to the target for this second
                int toSpawn = Math.min(currentTarget, totalUsers);
                spawnUsers(toSpawn);

            } catch (Exception e) {
                log.error("Error in user spawning scheduler", e);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
    }

    private void spawnUsers(int count) {
        for (int i = 0; i < count; i++) {
            DefaultSession session = new DefaultSession();
            executeActionChain(session, 0);
        }
        activeUserCount.set(count);

        // Update metrics for each pool
        for (ActionPool pool : poolManager.allPools()) {
            metricsCollector.recordActiveUsers(pool.actionName(), pool.activeCount());
        }
    }

    /**
     * Execute the action chain for a user, moving from one pool to the next.
     * When action at index N completes, action at index N+1 starts.
     */
    private void executeActionChain(DefaultSession session, int actionIndex) {
        if (actionIndex >= actionChain.size()) {
            return; // Chain complete for this user
        }

        if (state.get() == TestState.STOPPING || state.get() == TestState.COMPLETED) {
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

                logWriter.append(new ExecutionRecord(
                        actionDef.name(), session.sessionId(), Instant.now(),
                        duration, true, null
                ));

                // Move to the next action in the chain
                executeActionChain(session, actionIndex + 1);

            } catch (Exception e) {
                Duration duration = Duration.between(actionStart, Instant.now());
                metricsCollector.recordFailure(actionDef.name(), duration, e);

                logWriter.append(new ExecutionRecord(
                        actionDef.name(), session.sessionId(), Instant.now(),
                        duration, false, e.getMessage()
                ));

                log.debug("Action '{}' failed for session {}: {}", actionDef.name(), session.sessionId(), e.getMessage());
            }
        });
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

            logWriter.write(result);
            logWriter.close();

            state.set(TestState.COMPLETED);
            resultFuture.complete(result);

            log.info("Load test completed. Duration: {}s", Duration.between(startTime, endTime).toSeconds());
        }
    }

    private TestResult buildResult(Instant endTime) {
        Map<String, TestResult.ActionSummary> summaries = new LinkedHashMap<>();

        for (PoolMetricsSnapshot snapshot : allSnapshots) {
            // Use latest snapshot for each action
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
