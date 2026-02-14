package io.loadstorm.core.client;

import io.loadstorm.api.environment.Session;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default session implementation for virtual users.
 */
public class DefaultSession implements Session {

    private final String sessionId;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    public DefaultSession() {
        this.sessionId = UUID.randomUUID().toString();
    }

    public DefaultSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @Override
    public Optional<Object> get(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    @Override
    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }
}
