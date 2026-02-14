package io.loadstorm.api.client;

/**
 * Determines the threading model for the load test client.
 * <p>
 * If the client is blocking (e.g., JDBC, blocking HTTP), virtual threads
 * can be used to efficiently handle many concurrent users.
 * Non-blocking clients (e.g., reactive WebClient) use platform threads.
 * <p>
 * This can be overridden in the Environment configuration.
 */
public enum ClientType {

    /**
     * Blocking I/O client - virtual threads will be used by default.
     */
    BLOCKING,

    /**
     * Non-blocking/async client - platform threads used.
     */
    NON_BLOCKING
}
