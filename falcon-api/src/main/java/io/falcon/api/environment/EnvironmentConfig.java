package io.falcon.api.environment;

import io.falcon.api.client.ClientType;

import java.time.Duration;
import java.util.concurrent.ThreadFactory;

/**
 * Configuration for a load test environment.
 * Controls user count, ramp-up, connection pools, and threading model.
 */
public final class EnvironmentConfig {

    private int numberOfUsers = 10;
    private Duration rampUpTime = Duration.ofSeconds(10);
    private Duration testDuration = Duration.ofMinutes(1);
    private int connectionPoolSize = 50;
    private Boolean useVirtualThreads = null; // null = auto-detect from ClientType
    private ThreadFactory threadFactory = null;
    private Duration metricsInterval = Duration.ofSeconds(1);
    private String reportPath = null; // null = no report, set to generate

    private EnvironmentConfig() {}

    public static EnvironmentConfig create() {
        return new EnvironmentConfig();
    }

    public EnvironmentConfig numberOfUsers(int numberOfUsers) {
        if (numberOfUsers <= 0) {
            throw new IllegalArgumentException("Number of users must be positive");
        }
        this.numberOfUsers = numberOfUsers;
        this.connectionPoolSize = numberOfUsers;
        return this;
    }

    public EnvironmentConfig rampUpTime(Duration rampUpTime) {
        this.rampUpTime = rampUpTime;
        return this;
    }

    public EnvironmentConfig testDuration(Duration testDuration) {
        this.testDuration = testDuration;
        return this;
    }

    /**
     * Override the threading model. If not set, it is inferred from the client type:
     * BLOCKING → virtual threads, NON_BLOCKING → platform threads.
     */
    public EnvironmentConfig useVirtualThreads(boolean useVirtualThreads) {
        this.useVirtualThreads = useVirtualThreads;
        return this;
    }

    /**
     * Provide a custom thread factory, overriding the default behavior.
     */
    public EnvironmentConfig threadFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
        return this;
    }

    public EnvironmentConfig metricsInterval(Duration metricsInterval) {
        this.metricsInterval = metricsInterval;
        return this;
    }

    /**
     * Set the path for generating an HTML report after test completion.
     * If not set, no report is generated.
     */
    public EnvironmentConfig reportPath(String reportPath) {
        this.reportPath = reportPath;
        return this;
    }


    public int numberOfUsers() { return numberOfUsers; }
    public Duration rampUpTime() { return rampUpTime; }
    public Duration testDuration() { return testDuration; }
    public int connectionPoolSize() { return connectionPoolSize; }
    public Duration metricsInterval() { return metricsInterval; }
    public String reportPath() { return reportPath; }

    /**
     * Determine whether virtual threads should be used.
     * If explicitly set, returns that value. Otherwise infers from client type.
     */
    public boolean shouldUseVirtualThreads(ClientType clientType) {
        if (useVirtualThreads != null) {
            return useVirtualThreads;
        }
        return clientType == ClientType.BLOCKING;
    }

    public ThreadFactory threadFactory() { return threadFactory; }
    public Boolean useVirtualThreadsOverride() { return useVirtualThreads; }
}