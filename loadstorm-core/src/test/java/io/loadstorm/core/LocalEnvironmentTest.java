package io.loadstorm.core;

import io.loadstorm.api.environment.EnvironmentConfig;
import io.loadstorm.api.environment.LoadTestRun;
import io.loadstorm.api.log.TestResult;
import io.loadstorm.core.client.DefaultLoadClient;
import io.loadstorm.core.environment.LocalEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class LocalEnvironmentTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStartAndCompleteLoadTest() throws Exception {
        AtomicInteger executionCount = new AtomicInteger(0);

        var client = DefaultLoadClient.blocking();
        client.execute("test-action", session -> {
            executionCount.incrementAndGet();
            Thread.sleep(10);
        });

        var config = EnvironmentConfig.create()
                .numberOfUsers(5)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(3))
                .logFilePath(tempDir.resolve("test-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);

        assertThat(run.isRunning()).isTrue();

        TestResult result = run.result().get(10, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(result.configuredUsers()).isEqualTo(5);
        assertThat(executionCount.get()).isGreaterThan(0);
        assertThat(run.state()).isEqualTo(LoadTestRun.TestState.COMPLETED);
    }

    @Test
    void shouldExecuteChainedActionsInOrder() throws Exception {
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();

        var client = DefaultLoadClient.blocking();
        client.execute("step-1", session -> {
            order.add("step-1:" + session.sessionId());
            session.put("token", "abc123");
            Thread.sleep(10);
        });
        client.execute("step-2", session -> {
            var token = session.get("token").orElse("missing");
            order.add("step-2:" + session.sessionId() + ":" + token);
            Thread.sleep(10);
        });

        var config = EnvironmentConfig.create()
                .numberOfUsers(3)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(3))
                .logFilePath(tempDir.resolve("chain-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);
        run.result().get(10, TimeUnit.SECONDS);

        assertThat(order).isNotEmpty();
        long step2WithToken = order.stream()
                .filter(s -> s.startsWith("step-2:") && s.endsWith(":abc123"))
                .count();
        assertThat(step2WithToken).isGreaterThan(0);
    }

    @Test
    void shouldUseVirtualThreadsForBlockingClient() throws Exception {
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();

        var client = DefaultLoadClient.blocking();
        client.execute("vthread-test", session -> {
            threadNames.add(Thread.currentThread().toString());
            Thread.sleep(10);
        });

        var config = EnvironmentConfig.create()
                .numberOfUsers(3)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(2))
                .logFilePath(tempDir.resolve("vthread-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);
        run.result().get(10, TimeUnit.SECONDS);

        assertThat(threadNames).isNotEmpty();
        assertThat(threadNames.stream().anyMatch(n -> n.contains("VirtualThread"))).isTrue();
    }

    @Test
    void shouldStopTestGracefully() throws Exception {
        var client = DefaultLoadClient.blocking();
        client.execute("long-action", session -> Thread.sleep(100));

        var config = EnvironmentConfig.create()
                .numberOfUsers(5)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofMinutes(5))
                .logFilePath(tempDir.resolve("stop-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);

        Thread.sleep(2000);
        run.stop();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !run.isRunning());
        assertThat(run.state()).isEqualTo(LoadTestRun.TestState.COMPLETED);
    }

    @Test
    void shouldCollectMetrics() throws Exception {
        var client = DefaultLoadClient.blocking();
        client.execute("metrics-action", session -> Thread.sleep(20));

        var config = EnvironmentConfig.create()
                .numberOfUsers(5)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(3))
                .metricsInterval(Duration.ofMillis(500))
                .logFilePath(tempDir.resolve("metrics-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);
        TestResult result = run.result().get(10, TimeUnit.SECONDS);

        assertThat(result.timeSeriesSnapshots()).isNotEmpty();
        assertThat(result.actionSummaries()).hasSize(1);
        assertThat(result.actionSummaries().getFirst().actionName()).isEqualTo("metrics-action");
    }

    @Test
    void shouldHandleFailingActions() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);

        var client = DefaultLoadClient.blocking();
        client.execute("failing-action", session -> {
            if (attempts.incrementAndGet() % 2 == 0) {
                throw new RuntimeException("Simulated failure");
            }
            Thread.sleep(10);
        });

        var config = EnvironmentConfig.create()
                .numberOfUsers(5)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(3))
                .logFilePath(tempDir.resolve("fail-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);
        TestResult result = run.result().get(10, TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        assertThat(run.state()).isEqualTo(LoadTestRun.TestState.COMPLETED);
    }

    @Test
    void shouldOverrideVirtualThreadsInConfig() throws Exception {
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();

        var client = DefaultLoadClient.blocking(); // normally uses virtual threads

        client.execute("override-test", session -> {
            threadNames.add(Thread.currentThread().toString());
            Thread.sleep(10);
        });

        var config = EnvironmentConfig.create()
                .numberOfUsers(3)
                .rampUpTime(Duration.ofSeconds(1))
                .testDuration(Duration.ofSeconds(2))
                .useVirtualThreads(false) // override: force platform threads
                .logFilePath(tempDir.resolve("override-results.json").toString());

        var env = new LocalEnvironment(config);
        LoadTestRun run = env.start(client);
        run.result().get(10, TimeUnit.SECONDS);

        assertThat(threadNames).isNotEmpty();
        // Platform threads should NOT contain "VirtualThread"
        assertThat(threadNames.stream().noneMatch(n -> n.contains("VirtualThread"))).isTrue();
    }
}
