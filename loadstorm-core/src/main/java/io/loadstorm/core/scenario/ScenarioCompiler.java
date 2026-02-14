package io.loadstorm.core.scenario;

import io.loadstorm.api.action.Action;
import io.loadstorm.api.action.ActionChain;
import io.loadstorm.api.scenario.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Compiles a {@link Scenario} into an {@link ActionChain} that the runtime can execute.
 * <p>
 * Rich DSL constructs (pauses, repeats, conditionals, feeders, random switches)
 * are compiled into Action wrappers. The runtime doesn't need to understand
 * any of these concepts — it just executes actions in sequence.
 * <p>
 * Compilation strategy:
 * <ul>
 *   <li>{@code Execute} → direct action</li>
 *   <li>{@code Pause} → action that sleeps</li>
 *   <li>{@code Feed} → action that injects data into session</li>
 *   <li>{@code Repeat} → unrolled into N copies of the inner steps</li>
 *   <li>{@code RepeatWhile} → compiled as a single action that loops internally</li>
 *   <li>{@code IfCondition} → compiled as a single action with runtime branching</li>
 *   <li>{@code ExitIf} → action that throws a sentinel exception to skip remaining steps</li>
 *   <li>{@code RandomSwitch} → action that picks a branch and executes it inline</li>
 *   <li>{@code Group} → inner steps flattened with the group name as prefix</li>
 * </ul>
 */
public final class ScenarioCompiler {

    private ScenarioCompiler() {}

    /**
     * Compile a scenario into an action chain.
     */
    public static ActionChain compile(Scenario scenario) {
        ActionChain.Builder builder = ActionChain.builder();
        compileSteps(scenario.steps(), builder, "");
        return builder.build();
    }

    private static void compileSteps(List<ScenarioStep> steps, ActionChain.Builder builder, String prefix) {
        for (ScenarioStep step : steps) {
            switch (step) {
                case ScenarioStep.Execute exec ->
                        builder.then(prefix + exec.name(), exec.action());

                case ScenarioStep.Pause pause ->
                        builder.then(prefix + "_pause", compilePause(pause.strategy()));

                case ScenarioStep.Feed feed ->
                        builder.then(prefix + "_feed:" + feed.feeder().name(), compileFeed(feed.feeder()));

                case ScenarioStep.Repeat repeat ->
                        compileRepeat(repeat, builder, prefix);

                case ScenarioStep.RepeatWhile repeatWhile ->
                        builder.then(prefix + "repeatWhile:" + repeatWhile.label(),
                                compileRepeatWhile(repeatWhile));

                case ScenarioStep.IfCondition ifCond ->
                        builder.then(prefix + "if:" + ifCond.label(),
                                compileIf(ifCond));

                case ScenarioStep.ExitIf exitIf ->
                        builder.then(prefix + "_exitIf", compileExitIf(exitIf));

                case ScenarioStep.RandomSwitch randomSwitch ->
                        builder.then(prefix + "_randomSwitch",
                                compileRandomSwitch(randomSwitch));

                case ScenarioStep.Group group ->
                        compileSteps(group.steps(), builder, group.name() + ".");
            }
        }
    }

    private static Action compilePause(PauseStrategy strategy) {
        return session -> {
            Duration pause = strategy.duration();
            if (!pause.isZero()) {
                Thread.sleep(pause.toMillis());
            }
        };
    }

    private static Action compileFeed(Feeder feeder) {
        return session -> {
            if (!feeder.hasNext()) {
                throw new ScenarioExitException("Feeder '" + feeder.name() + "' exhausted");
            }
            Map<String, Object> data = feeder.next();
            data.forEach(session::put);
        };
    }

    /**
     * Unroll repeat into N copies of the inner steps.
     * Each iteration sets the counter key in the session.
     */
    private static void compileRepeat(ScenarioStep.Repeat repeat,
                                      ActionChain.Builder builder, String prefix) {
        for (int i = 0; i < repeat.times(); i++) {
            final int iteration = i;
            // Inject the iteration counter
            builder.then(prefix + repeat.counterKey() + "[" + i + "]._counter", session -> {
                session.put(repeat.counterKey(), iteration);
            });
            // Compile inner steps for this iteration
            compileSteps(repeat.steps(), builder, prefix + repeat.counterKey() + "[" + i + "].");
        }
    }

    /**
     * RepeatWhile compiles to a single action that loops internally.
     * Inner steps execute sequentially within this action.
     */
    private static Action compileRepeatWhile(ScenarioStep.RepeatWhile repeatWhile) {
        return session -> {
            int iteration = 0;
            while (repeatWhile.condition().test(session)) {
                session.put(repeatWhile.label() + ".iteration", iteration);
                for (ScenarioStep innerStep : repeatWhile.steps()) {
                    executeStepInline(innerStep, session);
                }
                iteration++;
            }
        };
    }

    /**
     * IfCondition compiles to a single action with runtime branching.
     */
    private static Action compileIf(ScenarioStep.IfCondition ifCond) {
        return session -> {
            List<ScenarioStep> branch = ifCond.condition().test(session)
                    ? ifCond.thenSteps()
                    : ifCond.elseSteps();
            for (ScenarioStep innerStep : branch) {
                executeStepInline(innerStep, session);
            }
        };
    }

    /**
     * ExitIf throws a sentinel exception that the runtime catches
     * to skip remaining steps and restart the scenario.
     */
    private static Action compileExitIf(ScenarioStep.ExitIf exitIf) {
        return session -> {
            if (exitIf.condition().test(session)) {
                throw new ScenarioExitException("Exit condition met");
            }
        };
    }

    /**
     * RandomSwitch picks a branch based on weights and executes it inline.
     */
    private static Action compileRandomSwitch(ScenarioStep.RandomSwitch randomSwitch) {
        return session -> {
            List<ScenarioStep.RandomSwitch.WeightedSteps> branches = randomSwitch.branches();

            double totalWeight = branches.stream()
                    .mapToDouble(ScenarioStep.RandomSwitch.WeightedSteps::weight)
                    .sum();
            double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;

            double cumulative = 0;
            List<ScenarioStep> chosen = branches.getLast().steps(); // fallback

            for (var branch : branches) {
                cumulative += branch.weight();
                if (roll < cumulative) {
                    chosen = branch.steps();
                    break;
                }
            }

            for (ScenarioStep innerStep : chosen) {
                executeStepInline(innerStep, session);
            }
        };
    }

    /**
     * Execute a step inline (within a parent action, not as a separate pool action).
     * Used for steps inside repeat, if, and randomSwitch.
     */
    private static void executeStepInline(ScenarioStep step,
                                          io.loadstorm.api.action.Session session) throws Exception {
        switch (step) {
            case ScenarioStep.Execute exec -> exec.action().execute(session);

            case ScenarioStep.Pause pause -> {
                Duration d = pause.strategy().duration();
                if (!d.isZero()) Thread.sleep(d.toMillis());
            }

            case ScenarioStep.Feed feed -> {
                if (feed.feeder().hasNext()) {
                    feed.feeder().next().forEach(session::put);
                }
            }

            case ScenarioStep.ExitIf exitIf -> {
                if (exitIf.condition().test(session)) {
                    throw new ScenarioExitException("Exit condition met");
                }
            }

            case ScenarioStep.IfCondition ifCond -> {
                List<ScenarioStep> branch = ifCond.condition().test(session)
                        ? ifCond.thenSteps() : ifCond.elseSteps();
                for (ScenarioStep inner : branch) {
                    executeStepInline(inner, session);
                }
            }

            case ScenarioStep.Repeat repeat -> {
                for (int i = 0; i < repeat.times(); i++) {
                    session.put(repeat.counterKey(), i);
                    for (ScenarioStep inner : repeat.steps()) {
                        executeStepInline(inner, session);
                    }
                }
            }

            case ScenarioStep.RepeatWhile repeatWhile -> {
                int iter = 0;
                while (repeatWhile.condition().test(session)) {
                    session.put(repeatWhile.label() + ".iteration", iter++);
                    for (ScenarioStep inner : repeatWhile.steps()) {
                        executeStepInline(inner, session);
                    }
                }
            }

            case ScenarioStep.RandomSwitch rs -> compileRandomSwitch(rs).execute(session);

            case ScenarioStep.Group group -> {
                for (ScenarioStep inner : group.steps()) {
                    executeStepInline(inner, session);
                }
            }
        }
    }
}