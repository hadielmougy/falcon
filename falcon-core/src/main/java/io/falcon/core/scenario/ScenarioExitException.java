package io.falcon.core.scenario;

/**
 * Sentinel exception thrown when a scenario's exit condition is met.
 * The runtime catches this to skip remaining steps and restart the user's iteration.
 * <p>
 * This is not an error — it's a control flow mechanism.
 */
public class ScenarioExitException extends RuntimeException {

    public ScenarioExitException(String message) {
        super(message, null, true, false); // no stack trace for performance
    }
}