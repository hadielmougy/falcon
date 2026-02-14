package io.loadstorm.api.action;

import java.util.Map;
import java.util.Optional;

/**
 * Session context available to actions during execution.
 * Each virtual user gets its own session instance.
 */
public interface Session {

    /**
     * @return unique identifier for this virtual user session
     */
    String sessionId();

    /**
     * Store a value in the session for use by subsequent actions.
     */
    void put(String key, Object value);

    /**
     * Retrieve a value from the session.
     */
    Optional<Object> get(String key);

    /**
     * @return all session attributes
     */
    Map<String, Object> attributes();
}
