package io.falcon.core;

import io.falcon.api.action.ActionChain;
import io.falcon.api.client.ClientType;
import io.falcon.api.environment.EnvironmentConfig;
import io.falcon.core.pool.DefaultActionPool;
import io.falcon.core.pool.PoolManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoolManagerTest {

    @Test
    void shouldCreateDistinctPoolPerAction() {
        var chain = ActionChain.builder()
                .then("login", s -> {})
                .then("browse", s -> {})
                .then("checkout", s -> {})
                .build();

        var config = EnvironmentConfig.create().numberOfUsers(10);
        var manager = new PoolManager(config, ClientType.BLOCKING);
        manager.initialize(chain);

        assertThat(manager.allPools()).hasSize(3);
        assertThat(manager.pool("login").actionName()).isEqualTo("login");
        assertThat(manager.pool("browse").actionName()).isEqualTo("browse");
        assertThat(manager.pool("checkout").actionName()).isEqualTo("checkout");

        manager.shutdown();
    }

    @Test
    void shouldThrowForUnknownPool() {
        var chain = ActionChain.builder().then("login", s -> {}).build();
        var config = EnvironmentConfig.create().numberOfUsers(10);
        var manager = new PoolManager(config, ClientType.BLOCKING);
        manager.initialize(chain);

        assertThatThrownBy(() -> manager.pool("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class);

        manager.shutdown();
    }

    @Test
    void shouldTrackActiveCountInPool() throws Exception {
        var pool = new DefaultActionPool("test", 10, true);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) {}
            });
        }

        started.await(5, TimeUnit.SECONDS);
        // small delay for counter updates
        Thread.sleep(100);
        assertThat(pool.activeCount()).isGreaterThanOrEqualTo(1);

        release.countDown();
        Thread.sleep(200);
        pool.shutdown();
    }

    @Test
    void shouldRespectPoolCapacity() throws Exception {
        int maxSize = 2;
        var pool = new DefaultActionPool("bounded", maxSize, true);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        AtomicInteger current = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(5);

        for (int i = 0; i < 5; i++) {
            pool.submit(() -> {
                int c = current.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, c));
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                current.decrementAndGet();
                done.countDown();
            });
        }

        done.await(10, TimeUnit.SECONDS);
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(maxSize);
        pool.shutdown();
    }

    @Test
    void shouldTrackCompletedAndFailedCounts() throws Exception {
        var pool = new DefaultActionPool("counting", 10, true);
        CountDownLatch done = new CountDownLatch(4);

        // 2 successful
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                done.countDown();
            });
        }

        // 2 failing
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                done.countDown();
                throw new RuntimeException("fail");
            });
        }

        done.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(pool.completedCount() + pool.failedCount()).isGreaterThanOrEqualTo(4);
        pool.shutdown();
    }
}
