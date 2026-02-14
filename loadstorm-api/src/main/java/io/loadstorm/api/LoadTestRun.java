package io.loadstorm.api;

import java.util.concurrent.CompletableFuture;

/**
 * Handle to a running load test. Allows monitoring status and stopping the test.
 */
public interface LoadTestRun {

    /**
     * @return true if the test is still running
     */
    boolean isRunning();

    /**
     * Stop the test gracefully.
     */
    void stop();

    /**
     * Wait for the test to complete and return the result.
     *
     * @return future that completes when the test finishes
     */
    CompletableFuture<TestResult> result();

    /**
     * @return current number of active virtual users
     */
    int activeUsers();

    /**
     * @return the current state of the test
     */
    TestState state();

    enum TestState {
        RAMPING_UP,
        RUNNING,
        STOPPING,
        COMPLETED,
        FAILED
    }
}
