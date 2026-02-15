package io.falcon.api.scenario;

import io.falcon.api.action.ActionChain;
import io.falcon.api.action.Action;
import io.falcon.api.action.Session;

import java.util.List;
import java.util.function.Predicate;

/**
 * A single step in a scenario. Steps compile down to actions in an {@link ActionChain}.
 * <p>
 * The sealed hierarchy ensures all step types are known at compile time,
 * allowing the runtime to handle each case explicitly.
 */
public sealed interface ScenarioStep {

    /**
     * Execute an action with a name.
     */
    record Execute(String name, Action action) implements ScenarioStep {}

    /**
     * Pause (think time) between actions.
     */
    record Pause(PauseStrategy strategy) implements ScenarioStep {}

    /**
     * Repeat a group of steps N times.
     */
    record Repeat(int times, String counterKey, List<ScenarioStep> steps) implements ScenarioStep {}

    /**
     * Repeat a group of steps while a condition holds.
     */
    record RepeatWhile(Predicate<Session> condition, String label, List<ScenarioStep> steps) implements ScenarioStep {}

    /**
     * Conditionally execute steps if a predicate is true.
     */
    record IfCondition(Predicate<Session> condition, String label,
                       List<ScenarioStep> thenSteps,
                       List<ScenarioStep> elseSteps) implements ScenarioStep {}

    /**
     * Exit the current iteration of the scenario (skip remaining steps).
     */
    record ExitIf(Predicate<Session> condition) implements ScenarioStep {}

    /**
     * Inject feeder data into the session before subsequent steps.
     */
    record Feed(Feeder feeder) implements ScenarioStep {}

    /**
     * Execute one of several action groups chosen randomly by weight.
     */
    record RandomSwitch(List<WeightedSteps> branches) implements ScenarioStep {

        public record WeightedSteps(double weight, List<ScenarioStep> steps) {}
    }

    /**
     * A group of steps to be treated as a single logical unit for metrics.
     */
    record Group(String name, List<ScenarioStep> steps) implements ScenarioStep {}
}