package io.loadstorm.core;

import io.loadstorm.api.ActionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread pool for a specific action type.
 * Each action in the chain gets its own pool so users move from one pool to another
 * as they progress through the action chain.
 * <p>
 * This ensures that there's always space in the first pool for the next round of users
 * because users are moving forward from one pool to another.
 */
public class DefaultActionPool implements ActionPool {

    private static final Logger log = LoggerFactory.getLogger(DefaultActionPool.class);

    private final String actionName;
    private final int maxSize;
    private final ExecutorService executor;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger waitingCount = new AtomicInteger(0);
    private final AtomicLong completedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    private final Semaphore semaphore;

    public DefaultActionPool(String actionName, int maxSize, boolean useVirtualThreads) {
        this.actionName = actionName;
        this.maxSize = maxSize;
        this.semaphore = new Semaphore(maxSize);

        if (useVirtualThreads) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            log.info("Created virtual thread pool for action '{}' with max {} users", actionName, maxSize);
        } else {
            this.executor = new ThreadPoolExecutor(
                    maxSize / 2,
                    maxSize,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(maxSize * 2),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
            log.info("Created platform thread pool for action '{}' with max {} threads", actionName, maxSize);
        }
    }

    public DefaultActionPool(String actionName, int maxSize, ThreadFactory threadFactory) {
        this.actionName = actionName;
        this.maxSize = maxSize;
        this.semaphore = new Semaphore(maxSize);

        this.executor = new ThreadPoolExecutor(
                maxSize / 2, maxSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(maxSize * 2),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("Created custom thread pool for action '{}' with max {} threads", actionName, maxSize);
    }

    @Override
    public String actionName() {
        return actionName;
    }

    @Override
    public int activeCount() {
        return activeCount.get();
    }

    @Override
    public int maxSize() {
        return maxSize;
    }

    @Override
    public int waitingCount() {
        return waitingCount.get();
    }

    @Override
    public long completedCount() {
        return completedCount.get();
    }

    @Override
    public long failedCount() {
        return failedCount.get();
    }

    @Override
    public void submit(Runnable task) {
        waitingCount.incrementAndGet();
        executor.submit(() -> {
            waitingCount.decrementAndGet();
            try {
                semaphore.acquire();
                activeCount.incrementAndGet();
                task.run();
                completedCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedCount.incrementAndGet();
            } catch (Exception e) {
                failedCount.incrementAndGet();
                log.debug("Task failed in pool '{}': {}", actionName, e.getMessage());
            } finally {
                activeCount.decrementAndGet();
                semaphore.release();
            }
        });
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Pool '{}' shut down. Completed: {}, Failed: {}", actionName, completedCount.get(), failedCount.get());
    }
}
