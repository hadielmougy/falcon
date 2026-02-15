package io.falcon.core.pool;

import io.falcon.api.action.ActionChain;
import io.falcon.api.action.ActionDefinition;
import io.falcon.api.client.ClientType;
import io.falcon.api.environment.EnvironmentConfig;
import io.falcon.api.pool.ActionPool;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

/**
 * Manages one pool per action type.
 * Pools are created based on the environment parameters (connection pool size, threading model).
 */
public class PoolManager {

    private final Map<String, ActionPool> pools = new ConcurrentHashMap<>();
    private final EnvironmentConfig config;
    private final ClientType clientType;

    public PoolManager(EnvironmentConfig config, ClientType clientType) {
        this.config = config;
        this.clientType = clientType;
    }

    /**
     * Initialize pools for all actions in the chain.
     * Each action gets a distinct pool.
     */
    public void initialize(ActionChain chain) {
        boolean useVirtualThreads = config.shouldUseVirtualThreads(clientType);
        ThreadFactory customFactory = config.threadFactory();

        for (ActionDefinition action : chain) {
            ActionPool pool;
            if (customFactory != null) {
                pool = new DefaultActionPool(action.name(), config.connectionPoolSize(), customFactory);
            } else {
                pool = new DefaultActionPool(action.name(), config.connectionPoolSize(), useVirtualThreads);
            }
            pools.put(action.name(), pool);
        }
    }

    /**
     * Get the pool for a specific action.
     */
    public ActionPool pool(String actionName) {
        ActionPool pool = pools.get(actionName);
        if (pool == null) {
            throw new IllegalArgumentException("No pool for action: " + actionName);
        }
        return pool;
    }

    /**
     * @return all pools (for metrics collection)
     */
    public Collection<ActionPool> allPools() {
        return Collections.unmodifiableCollection(pools.values());
    }

    /**
     * Shut down all pools.
     */
    public void shutdown() {
        pools.values().forEach(ActionPool::shutdown);
        pools.clear();
    }
}
