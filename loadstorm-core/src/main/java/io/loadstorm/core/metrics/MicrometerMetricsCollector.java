package io.loadstorm.core.metrics;

import io.loadstorm.api.metrics.MetricsCollector;
import io.loadstorm.api.metrics.PoolMetricsSnapshot;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Default metrics collector using Micrometer.
 * Collects response times, success/failure counts, active users per action.
 * Snapshots are taken every second (configurable) for dashboard display.
 */
public class MicrometerMetricsCollector implements MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MicrometerMetricsCollector.class);

    private final MeterRegistry registry;
    private final Map<String, Timer> responseTimers = new ConcurrentHashMap<>();
    private final Map<String, Counter> successCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> failureCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLongHolder> activeUsers = new ConcurrentHashMap<>();
    private final List<Consumer<List<PoolMetricsSnapshot>>> listeners = new CopyOnWriteArrayList<>();
    private final List<PoolMetricsSnapshot> historicalSnapshots = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;

    public MicrometerMetricsCollector() {
        this(new SimpleMeterRegistry());
    }

    public MicrometerMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordSuccess(String actionName, Duration duration) {
        getTimer(actionName).record(duration);
        getSuccessCounter(actionName).increment();
    }

    @Override
    public void recordFailure(String actionName, Duration duration, Throwable error) {
        getTimer(actionName).record(duration);
        getFailureCounter(actionName).increment();
    }

    @Override
    public void recordActiveUsers(String actionName, int count) {
        activeUsers.computeIfAbsent(actionName, k -> {
            var holder = new AtomicLongHolder();
            Gauge.builder("loadstorm.active.users", holder, AtomicLongHolder::get)
                    .tag("action", actionName)
                    .register(registry);
            return holder;
        }).set(count);
    }

    @Override
    public List<PoolMetricsSnapshot> snapshot() {
        List<PoolMetricsSnapshot> snapshots = new ArrayList<>();
        Instant now = Instant.now();

        for (String actionName : responseTimers.keySet()) {
            Timer timer = responseTimers.get(actionName);
            HistogramSnapshot histSnapshot = timer.takeSnapshot();

            long successTotal = (long) getSuccessCounter(actionName).count();
            long failureTotal = (long) getFailureCounter(actionName).count();
            var activeHolder = activeUsers.get(actionName);
            int active = activeHolder != null ? (int) activeHolder.get() : 0;

            double avgMs = histSnapshot.mean(TimeUnit.MILLISECONDS);
            double p99Ms = histSnapshot.percentileValues().length > 0
                    ? histSnapshot.percentileValues()[histSnapshot.percentileValues().length - 1].value(TimeUnit.MILLISECONDS)
                    : 0.0;

            double rps = timer.count() > 0
                    ? timer.count() / Math.max(1.0, timer.totalTime(TimeUnit.SECONDS))
                    : 0.0;

            snapshots.add(new PoolMetricsSnapshot(
                    actionName,
                    active,
                    0, // max size not tracked at metrics level
                    0, // waiting count not tracked at metrics level
                    successTotal,
                    failureTotal,
                    avgMs,
                    p99Ms,
                    rps,
                    now
            ));
        }

        return snapshots;
    }

    @Override
    public void onSnapshot(Consumer<List<PoolMetricsSnapshot>> listener) {
        listeners.add(listener);
    }

    @Override
    public void start(Duration interval) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "loadstorm-metrics-collector");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<PoolMetricsSnapshot> snap = snapshot();
                historicalSnapshots.addAll(snap);
                listeners.forEach(l -> l.accept(snap));
            } catch (Exception e) {
                log.error("Error collecting metrics snapshot", e);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);

        log.info("Metrics collection started with interval: {}ms", interval.toMillis());
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        log.info("Metrics collection stopped");
    }

    public MeterRegistry registry() {
        return registry;
    }

    public List<PoolMetricsSnapshot> historicalSnapshots() {
        return Collections.unmodifiableList(historicalSnapshots);
    }

    private Timer getTimer(String actionName) {
        return responseTimers.computeIfAbsent(actionName, name ->
                Timer.builder("loadstorm.response.time")
                        .tag("action", name)
                        .publishPercentiles(0.5, 0.75, 0.95, 0.99)
                        .register(registry));
    }

    private Counter getSuccessCounter(String actionName) {
        return successCounters.computeIfAbsent(actionName, name ->
                Counter.builder("loadstorm.requests.success")
                        .tag("action", name)
                        .register(registry));
    }

    private Counter getFailureCounter(String actionName) {
        return failureCounters.computeIfAbsent(actionName, name ->
                Counter.builder("loadstorm.requests.failure")
                        .tag("action", name)
                        .register(registry));
    }

    /**
     * Mutable holder for gauge values.
     */
    private static class AtomicLongHolder {
        private volatile long value;

        void set(long value) { this.value = value; }
        double get() { return value; }
    }
}
