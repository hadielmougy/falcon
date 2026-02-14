package io.loadstorm.core.client;

import io.loadstorm.api.action.Action;
import io.loadstorm.api.action.ActionChain;
import io.loadstorm.api.client.ClientType;
import io.loadstorm.api.client.LoadTestClient;

/**
 * Default implementation of LoadTestClient.
 * Builds an ActionChain from registered actions.
 */
public class DefaultLoadClient implements LoadTestClient {

    private final ClientType clientType;
    private ActionChain.Builder chainBuilder = ActionChain.builder();
    private ActionChain builtChain = null;

    public DefaultLoadClient(ClientType clientType) {
        this.clientType = clientType;
    }

    /**
     * Create a blocking client (will use virtual threads by default).
     */
    public static DefaultLoadClient blocking() {
        return new DefaultLoadClient(ClientType.BLOCKING);
    }

    /**
     * Create a non-blocking client (will use platform threads).
     */
    public static DefaultLoadClient nonBlocking() {
        return new DefaultLoadClient(ClientType.NON_BLOCKING);
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
