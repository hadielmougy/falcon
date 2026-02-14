package io.loadstorm.core;

import io.loadstorm.api.Action;
import io.loadstorm.api.ActionChain;
import io.loadstorm.api.ClientType;
import io.loadstorm.api.LoadTestClient;

/**
 * Default implementation of LoadTestClient.
 * Builds an ActionChain from registered actions.
 */
public class DefaultLoadTestClient implements LoadTestClient {

    private final ClientType clientType;
    private ActionChain.Builder chainBuilder = ActionChain.builder();
    private ActionChain builtChain = null;

    public DefaultLoadTestClient(ClientType clientType) {
        this.clientType = clientType;
    }

    /**
     * Create a blocking client (will use virtual threads by default).
     */
    public static DefaultLoadTestClient blocking() {
        return new DefaultLoadTestClient(ClientType.BLOCKING);
    }

    /**
     * Create a non-blocking client (will use platform threads).
     */
    public static DefaultLoadTestClient nonBlocking() {
        return new DefaultLoadTestClient(ClientType.NON_BLOCKING);
    }

    @Override
    public LoadTestClient execute(String name, Action action) {
        if (builtChain != null) {
            throw new IllegalStateException("Cannot add actions after chain has been built");
        }
        chainBuilder.then(name, action);
        return this;
    }

    @Override
    public LoadTestClient execute(ActionChain chain) {
        this.builtChain = chain;
        return this;
    }

    @Override
    public ActionChain actionChain() {
        if (builtChain == null) {
            builtChain = chainBuilder.build();
        }
        return builtChain;
    }

    @Override
    public ClientType clientType() {
        return clientType;
    }
}
