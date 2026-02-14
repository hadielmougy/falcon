package io.loadstorm.api.scenario;

import io.loadstorm.api.action.Action;
import io.loadstorm.api.action.Session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Fluent DSL for building load test scenarios.
 * <p>
 * A Scenario compiles down to an {@link io.loadstorm.api.action.ActionChain} for execution
 * by the runtime. It adds higher-level constructs: pauses, repeats, conditionals,
 * feeders, random switches, and groups.
 * <p>
 * Usage:
 * <pre>{@code
 * Scenario scenario = Scenario.named("e-commerce")
 *     .feed(csvFeeder("users.csv"))
 *     .execute("login", session -> {
 *         String user = (String) session.get("username").orElseThrow();
 *         // perform login
 *     })
 *     .pause(Duration.ofSeconds(1), Duration.ofSeconds(3))
 *     .repeat(5, "browse", steps -> steps
 *         .execute("view-product", session -> { ... })
 *         .pause(Duration.ofMillis(500), Duration.ofSeconds(2))
 *     )
 *     .exitIf(session -> session.get("error").isPresent())
 *     .group("checkout-flow", steps -> steps
 *         .execute("add-to-cart", session -> { ... })
 *         .execute("payment", session -> { ... })
 *     )
 *     .build();
 * }</pre>
 */
public final class Scenario {

    private final String name;
    private final List<ScenarioStep> steps;

    private Scenario(String name, List<ScenarioStep> steps) {
        this.name = name;
        this.steps = Collections.unmodifiableList(steps);
    }

    /**
     * Start building a named scenario.
     */
    public static Builder named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Scenario name must not be blank");
        }
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public List<ScenarioStep> steps() {
        return steps;
    }

    /**
     * Fluent builder for constructing scenarios.
     */
    public static final class Builder {
        private final String name;
        private final List<ScenarioStep> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        // ─── Core actions ───

        /**
         * Execute an action with a name.
         */
        public Builder execute(String actionName, Action action) {
            steps.add(new ScenarioStep.Execute(actionName, action));
            return this;
        }

        // ─── Pauses / Think time ───

        /**
         * Pause for a fixed duration.
         */
        public Builder pause(Duration duration) {
            steps.add(new ScenarioStep.Pause(new PauseStrategy.Fixed(duration)));
            return this;
        }

        /**
         * Pause for a random duration between min and max.
         */
        public Builder pause(Duration min, Duration max) {
            steps.add(new ScenarioStep.Pause(new PauseStrategy.Uniform(min, max)));
            return this;
        }

        /**
         * Pause with a custom strategy.
         */
        public Builder pause(PauseStrategy strategy) {
            steps.add(new ScenarioStep.Pause(strategy));
            return this;
        }

        // ─── Loops ───

        /**
         * Repeat a group of steps N times. The current iteration index (0-based)
         * is stored in the session under the given counterKey.
         */
        public Builder repeat(int times, String counterKey, Consumer<Builder> stepBuilder) {
            Builder inner = new Builder(name + ".repeat");
            stepBuilder.accept(inner);
            steps.add(new ScenarioStep.Repeat(times, counterKey, inner.steps));
            return this;
        }

        /**
         * Repeat a group of steps while a condition holds.
         */
        public Builder repeatWhile(Predicate<Session> condition, String label, Consumer<Builder> stepBuilder) {
            Builder inner = new Builder(name + ".repeatWhile");
            stepBuilder.accept(inner);
            steps.add(new ScenarioStep.RepeatWhile(condition, label, inner.steps));
            return this;
        }

        // ─── Conditionals ───

        /**
         * Conditionally execute steps if a predicate is true.
         */
        public Builder doIf(Predicate<Session> condition, String label, Consumer<Builder> thenSteps) {
            Builder thenBuilder = new Builder(name + ".if");
            thenSteps.accept(thenBuilder);
            steps.add(new ScenarioStep.IfCondition(condition, label, thenBuilder.steps, List.of()));
            return this;
        }

        /**
         * Conditionally execute steps with an else branch.
         */
        public Builder doIfElse(Predicate<Session> condition, String label,
                                Consumer<Builder> thenSteps, Consumer<Builder> elseSteps) {
            Builder thenBuilder = new Builder(name + ".if.then");
            Builder elseBuilder = new Builder(name + ".if.else");
            thenSteps.accept(thenBuilder);
            elseSteps.accept(elseBuilder);
            steps.add(new ScenarioStep.IfCondition(condition, label, thenBuilder.steps, elseBuilder.steps));
            return this;
        }

        /**
         * Exit the current scenario iteration if the condition is true.
         * The user restarts the scenario from the beginning with a fresh session.
         */
        public Builder exitIf(Predicate<Session> condition) {
            steps.add(new ScenarioStep.ExitIf(condition));
            return this;
        }

        // ─── Feeders ───

        /**
         * Inject feeder data into the session. Called once per iteration
         * before subsequent steps consume the data.
         */
        public Builder feed(Feeder feeder) {
            steps.add(new ScenarioStep.Feed(feeder));
            return this;
        }

        // ─── Random branching ───

        /**
         * Randomly choose one of several branches based on weight.
         * Weights are relative — they don't need to sum to 100.
         */
        public Builder randomSwitch(Consumer<RandomSwitchBuilder> branches) {
            RandomSwitchBuilder rsBuilder = new RandomSwitchBuilder(name);
            branches.accept(rsBuilder);
            steps.add(new ScenarioStep.RandomSwitch(rsBuilder.build()));
            return this;
        }

        // ─── Groups ───

        /**
         * Group steps under a logical name for aggregated metrics.
         */
        public Builder group(String groupName, Consumer<Builder> stepBuilder) {
            Builder inner = new Builder(name + ".group." + groupName);
            stepBuilder.accept(inner);
            steps.add(new ScenarioStep.Group(groupName, inner.steps));
            return this;
        }

        /**
         * Build the scenario.
         */
        public Scenario build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("Scenario must contain at least one step");
            }
            return new Scenario(name, new ArrayList<>(steps));
        }
    }

    /**
     * Builder for random switch branches.
     */
    public static final class RandomSwitchBuilder {
        private final String parentName;
        private final List<ScenarioStep.RandomSwitch.WeightedSteps> branches = new ArrayList<>();

        private RandomSwitchBuilder(String parentName) {
            this.parentName = parentName;
        }

        /**
         * Add a weighted branch.
         *
         * @param weight relative weight for this branch
         * @param stepBuilder steps to execute if this branch is chosen
         * @return this builder
         */
        public RandomSwitchBuilder branch(double weight, Consumer<Builder> stepBuilder) {
            Builder inner = new Builder(parentName + ".switch");
            stepBuilder.accept(inner);
            branches.add(new ScenarioStep.RandomSwitch.WeightedSteps(weight, inner.steps));
            return this;
        }

        List<ScenarioStep.RandomSwitch.WeightedSteps> build() {
            if (branches.isEmpty()) {
                throw new IllegalStateException("RandomSwitch must have at least one branch");
            }
            return new ArrayList<>(branches);
        }
    }
}
