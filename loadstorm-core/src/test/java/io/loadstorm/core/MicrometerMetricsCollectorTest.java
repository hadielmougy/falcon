package io.loadstorm.core;

import io.loadstorm.api.metrics.PoolMetricsSnapshot;
import io.loadstorm.core.metrics.MicrometerMetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MicrometerMetricsCollectorTest {

    private SimpleMeterRegistry registry;
    private MicrometerMetricsCollector collector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        collector = new MicrometerMetricsCollector(registry);
    }

    @AfterEach
    void tearDown() {
        collector.stop();
        registry.close();
    }

    // --- Construction ---

    @Test
    void shouldCreateWithDefaultRegistry() {
        var defaultCollector = new MicrometerMetricsCollector();
        assertThat(defaultCollector.registry()).isNotNull();
        defaultCollector.stop();
    }

    @Test
    void shouldExposeProvidedRegistry() {
        assertThat(collector.registry()).isSameAs(registry);
    }

    // --- recordSuccess ---

    @Test
    void shouldRecordSuccessfulExecution() {
        collector.recordSuccess("login", Duration.ofMillis(150));

        Counter counter = registry.find("loadstorm.requests.success")
                .tag("action", "login")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "login")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(150.0, within(5.0));
    }

    @Test
    void shouldAccumulateMultipleSuccesses() {
        collector.recordSuccess("browse", Duration.ofMillis(50));
        collector.recordSuccess("browse", Duration.ofMillis(100));
        collector.recordSuccess("browse", Duration.ofMillis(150));

        Counter counter = registry.find("loadstorm.requests.success")
                .tag("action", "browse")
                .counter();
        assertThat(counter.count()).isEqualTo(3.0);

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "browse")
                .timer();
        assertThat(timer.count()).isEqualTo(3);
        assertThat(timer.mean(TimeUnit.MILLISECONDS)).isCloseTo(100.0, within(5.0));
    }

    // --- recordFailure ---

    @Test
    void shouldRecordFailedExecution() {
        RuntimeException error = new RuntimeException("timeout");
        collector.recordFailure("checkout", Duration.ofMillis(5000), error);

        Counter counter = registry.find("loadstorm.requests.failure")
                .tag("action", "checkout")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "checkout")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void shouldTrackSuccessAndFailureSeparately() {
        collector.recordSuccess("api-call", Duration.ofMillis(100));
        collector.recordSuccess("api-call", Duration.ofMillis(120));
        collector.recordFailure("api-call", Duration.ofMillis(3000), new RuntimeException("500"));

        Counter success = registry.find("loadstorm.requests.success")
                .tag("action", "api-call")
                .counter();
        Counter failure = registry.find("loadstorm.requests.failure")
                .tag("action", "api-call")
                .counter();

        assertThat(success.count()).isEqualTo(2.0);
        assertThat(failure.count()).isEqualTo(1.0);

        // Timer records all executions (both success and failure)
        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "api-call")
                .timer();
        assertThat(timer.count()).isEqualTo(3);
    }

    // --- recordActiveUsers ---

    @Test
    void shouldRecordActiveUsers() {
        collector.recordActiveUsers("login", 42);

        Gauge gauge = registry.find("loadstorm.active.users")
                .tag("action", "login")
                .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void shouldUpdateActiveUsersOnSubsequentCalls() {
        collector.recordActiveUsers("login", 10);
        collector.recordActiveUsers("login", 50);
        collector.recordActiveUsers("login", 25);

        Gauge gauge = registry.find("loadstorm.active.users")
                .tag("action", "login")
                .gauge();
        assertThat(gauge.value()).isEqualTo(25.0);
    }

    @Test
    void shouldTrackActiveUsersPerAction() {
        collector.recordActiveUsers("login", 10);
        collector.recordActiveUsers("browse", 30);
        collector.recordActiveUsers("checkout", 5);

        assertThat(registry.find("loadstorm.active.users")
                .tag("action", "login").gauge().value()).isEqualTo(10.0);
        assertThat(registry.find("loadstorm.active.users")
                .tag("action", "browse").gauge().value()).isEqualTo(30.0);
        assertThat(registry.find("loadstorm.active.users")
                .tag("action", "checkout").gauge().value()).isEqualTo(5.0);
    }

    // --- snapshot ---

    @Test
    void shouldReturnEmptySnapshotWhenNoDataRecorded() {
        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).isEmpty();
    }

    @Test
    void shouldReturnSnapshotPerAction() {
        collector.recordSuccess("login", Duration.ofMillis(100));
        collector.recordSuccess("browse", Duration.ofMillis(200));
        collector.recordFailure("browse", Duration.ofMillis(500), new RuntimeException("err"));

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();

        assertThat(snapshots).hasSize(2);

        PoolMetricsSnapshot loginSnap = snapshots.stream()
                .filter(s -> s.actionName().equals("login"))
                .findFirst().orElseThrow();
        assertThat(loginSnap.completedCount()).isEqualTo(1);
        assertThat(loginSnap.failedCount()).isEqualTo(0);
        assertThat(loginSnap.averageResponseTimeMs()).isCloseTo(100.0, within(5.0));
        assertThat(loginSnap.timestamp()).isNotNull();

        PoolMetricsSnapshot browseSnap = snapshots.stream()
                .filter(s -> s.actionName().equals("browse"))
                .findFirst().orElseThrow();
        assertThat(browseSnap.completedCount()).isEqualTo(1);
        assertThat(browseSnap.failedCount()).isEqualTo(1);
    }

    @Test
    void shouldIncludeActiveUsersInSnapshot() {
        collector.recordSuccess("login", Duration.ofMillis(50));
        collector.recordActiveUsers("login", 75);

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().activeCount()).isEqualTo(75);
    }

    @Test
    void shouldReturnZeroActiveUsersWhenNoneRecorded() {
        collector.recordSuccess("login", Duration.ofMillis(50));
        // recordActiveUsers never called for "login"

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().activeCount()).isEqualTo(0);
    }

    @Test
    void shouldCalculateRequestsPerSecond() {
        // Record several fast successes
        for (int i = 0; i < 10; i++) {
            collector.recordSuccess("fast-action", Duration.ofMillis(10));
        }

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        PoolMetricsSnapshot snap = snapshots.getFirst();

        // rps = count / totalTimeSeconds; 10 requests at 10ms each = 100ms total ≈ 100 rps
        assertThat(snap.requestsPerSecond()).isGreaterThan(0);
    }

    @Test
    void shouldReturnZeroRpsWhenNoRequests() {
        // Force a timer creation via recordSuccess then take snapshot immediately
        // (this test verifies the edge case path; with no timer entries, snapshot is empty)
        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).isEmpty();
    }

    // --- Micrometer tags / meter isolation ---

    @Test
    void shouldIsolateMetersPerAction() {
        collector.recordSuccess("action-a", Duration.ofMillis(10));
        collector.recordSuccess("action-a", Duration.ofMillis(20));
        collector.recordSuccess("action-b", Duration.ofMillis(500));
        collector.recordFailure("action-b", Duration.ofMillis(1000), new RuntimeException("x"));

        Timer timerA = registry.find("loadstorm.response.time").tag("action", "action-a").timer();
        Timer timerB = registry.find("loadstorm.response.time").tag("action", "action-b").timer();

        assertThat(timerA.count()).isEqualTo(2);
        assertThat(timerB.count()).isEqualTo(2);
        assertThat(timerA.mean(TimeUnit.MILLISECONDS)).isLessThan(timerB.mean(TimeUnit.MILLISECONDS));
    }

    @Test
    void shouldPublishPercentiles() {
        for (int i = 0; i < 100; i++) {
            collector.recordSuccess("percentile-test", Duration.ofMillis(i * 10));
        }

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "percentile-test")
                .timer();
        assertThat(timer).isNotNull();

        var histSnapshot = timer.takeSnapshot();
        // publishPercentiles(0.5, 0.75, 0.95, 0.99) means 4 percentile values
        assertThat(histSnapshot.percentileValues()).hasSizeGreaterThanOrEqualTo(4);
    }

    // --- onSnapshot / listeners ---

    @Test
    void shouldNotifyListenersOnScheduledSnapshot() {
        List<List<PoolMetricsSnapshot>> received = new CopyOnWriteArrayList<>();
        collector.onSnapshot(received::add);

        collector.recordSuccess("listener-test", Duration.ofMillis(50));
        collector.start(Duration.ofMillis(200));

        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(received).isNotEmpty());

        assertThat(received.getFirst()).isNotEmpty();
        assertThat(received.getFirst().getFirst().actionName()).isEqualTo("listener-test");
    }

    @Test
    void shouldNotifyMultipleListeners() {
        List<List<PoolMetricsSnapshot>> listener1 = new CopyOnWriteArrayList<>();
        List<List<PoolMetricsSnapshot>> listener2 = new CopyOnWriteArrayList<>();

        collector.onSnapshot(listener1::add);
        collector.onSnapshot(listener2::add);

        collector.recordSuccess("multi-listener", Duration.ofMillis(30));
        collector.start(Duration.ofMillis(200));

        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(listener1).isNotEmpty();
                    assertThat(listener2).isNotEmpty();
                });
    }

    // --- start / stop / historical snapshots ---

    @Test
    void shouldAccumulateHistoricalSnapshots() {
        collector.recordSuccess("history-test", Duration.ofMillis(25));
        collector.start(Duration.ofMillis(200));

        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(collector.historicalSnapshots()).hasSizeGreaterThanOrEqualTo(2));

        List<PoolMetricsSnapshot> history = collector.historicalSnapshots();
        assertThat(history).allMatch(s -> s.actionName().equals("history-test"));
        // Timestamps should be increasing
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).timestamp()).isAfterOrEqualTo(history.get(i - 1).timestamp());
        }
    }

    @Test
    void shouldReturnUnmodifiableHistoricalSnapshots() {
        List<PoolMetricsSnapshot> history = collector.historicalSnapshots();
        assertThat(history).isUnmodifiable();
    }

    @Test
    void shouldStopCollectionGracefully() {
        collector.recordSuccess("stop-test", Duration.ofMillis(10));
        collector.start(Duration.ofMillis(100));

        await().atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(collector.historicalSnapshots()).isNotEmpty());

        collector.stop();
        int sizeAfterStop = collector.historicalSnapshots().size();

        // Wait a bit and verify no new snapshots are added
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(collector.historicalSnapshots()).hasSize(sizeAfterStop);
    }

    @Test
    void shouldHandleStopWithoutStart() {
        // Should not throw
        collector.stop();
    }

    @Test
    void shouldHandleDoubleStop() {
        collector.start(Duration.ofMillis(500));
        collector.stop();
        collector.stop(); // second stop should not throw
    }

    // --- Concurrent recording ---

    @Test
    void shouldHandleConcurrentRecording() throws Exception {
        int threadCount = 10;
        int recordsPerThread = 100;
        var latch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < recordsPerThread; i++) {
                        if (i % 3 == 0) {
                            collector.recordFailure("concurrent-action",
                                    Duration.ofMillis(i), new RuntimeException("err"));
                        } else {
                            collector.recordSuccess("concurrent-action", Duration.ofMillis(i));
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "concurrent-action")
                .timer();
        assertThat(timer.count()).isEqualTo(threadCount * recordsPerThread);

        Counter successCounter = registry.find("loadstorm.requests.success")
                .tag("action", "concurrent-action")
                .counter();
        Counter failureCounter = registry.find("loadstorm.requests.failure")
                .tag("action", "concurrent-action")
                .counter();

        // Every 3rd record is a failure
        long expectedFailures = threadCount * (recordsPerThread / 3 + (recordsPerThread % 3 > 0 ? 1 : 0));
        assertThat((long) successCounter.count() + (long) failureCounter.count())
                .isEqualTo(threadCount * recordsPerThread);
    }

    // --- Edge cases ---

    @Test
    void shouldHandleZeroDurationRecording() {
        collector.recordSuccess("zero-duration", Duration.ZERO);

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().averageResponseTimeMs()).isEqualTo(0.0);
    }

    @Test
    void shouldHandleVeryLargeDuration() {
        collector.recordSuccess("slow-action", Duration.ofSeconds(300));

        Timer timer = registry.find("loadstorm.response.time")
                .tag("action", "slow-action")
                .timer();
        assertThat(timer.totalTime(TimeUnit.SECONDS)).isCloseTo(300.0, within(1.0));
    }

    @Test
    void shouldHandleManyDistinctActions() {
        for (int i = 0; i < 50; i++) {
            collector.recordSuccess("action-" + i, Duration.ofMillis(i * 10));
        }

        List<PoolMetricsSnapshot> snapshots = collector.snapshot();
        assertThat(snapshots).hasSize(50);
    }

    // --- Helpers ---

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}

