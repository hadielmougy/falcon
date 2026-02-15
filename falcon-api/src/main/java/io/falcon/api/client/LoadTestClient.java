package io.falcon.api.client;


import io.falcon.api.action.Action;
import io.falcon.api.action.ActionChain;
import io.falcon.api.scenario.Scenario;

/**
 * The load test client that registers actions to be executed.
 * <p>
 * The client is the local object used to define what the virtual users will do.
 * Actions are registered and then executed by the runtime at the configured rate.
 * <p>
 * Usage:
 * <pre>{@code
 * client.execute("login", s -> {
 *     // perform login action
 * });
 * client.execute("browse", s -> {
 *     // perform browse action
 * });
 * }</pre>
 * <p>
 * Multiple registered actions form a chain (linked list). When the first action
 * finishes for a virtual user, the second one starts.
 */
public interface LoadTestClient {

    /**
     * Register a single action to be executed by virtual users.
     *
     * @param name   descriptive name for the action
     * @param action the action logic
     * @return this client for fluent chaining
     */
    LoadTestClient execute(String name, Action action);

    /**
     * Register a pre-built action chain.
     *
     * @param chain the action chain
     * @return this client for fluent chaining
     */
    LoadTestClient execute(ActionChain chain);

    /**
     * Register a scenario. The scenario is compiled into an action chain.
     */
    LoadTestClient execute(Scenario scenario);

    /**
     * @return the built action chain from all registered actions
     */
    ActionChain actionChain();

    /**
     * @return the client type determining threading model
     */
    ClientType clientType();
}

