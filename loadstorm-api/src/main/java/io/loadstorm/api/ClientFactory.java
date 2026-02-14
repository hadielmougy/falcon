package io.loadstorm.api;

/**
 * Factory for creating load test clients.
 * Implement this interface to add new client types to the library in an extensible way.
 * <p>
 * Implementations can be registered via Java ServiceLoader or directly with the Environment.
 */
public interface ClientFactory {

    /**
     * @return unique identifier for this client type
     */
    String type();

    /**
     * Create a new client instance.
     *
     * @return a configured LoadTestClient
     */
    LoadTestClient create();
}
