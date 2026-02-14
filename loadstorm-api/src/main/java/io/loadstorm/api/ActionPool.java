package io.loadstorm.api;

/**
 * Connection pool for a specific action type.
 * Each action in the chain gets its own pool. Users move from one pool to another
 * as they progress through the action chain.
 * <p>
 * Pool metrics (active connections, waiting, etc.) are used for dashboard display.
 */
public interface ActionPool {

    /**
     * @return the name of the action this pool serves
     */
    String actionName();

    /**
     * @return current number of active users in this pool
     */
    int activeCount();

    /**
     * @return maximum capacity of this pool
     */
    int maxSize();

    /**
     * @return number of users waiting to enter this pool
     */
    int waitingCount();

    /**
     * @return number of completed executions
     */
    long completedCount();

    /**
     * @return number of failed executions
     */
    long failedCount();

    /**
     * Submit a user task to this pool.
     *
     * @param task the task to execute
     */
    void submit(Runnable task);

    /**
     * Shut down this pool.
     */
    void shutdown();
}
