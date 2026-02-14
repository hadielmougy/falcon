package io.loadstorm.core.scenario;

import io.loadstorm.api.action.ActionChain;
import io.loadstorm.api.scenario.*;
import io.loadstorm.core.client.DefaultSession;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioCompilerTest {

    @Test
    void shouldCompileSimpleExecute() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("simple")
                .execute("step-1", s -> log.add("a"))
                .execute("step-2", s -> log.add("b"))
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);

        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.get(0).name()).isEqualTo("step-1");
        assertThat(chain.get(1).name()).isEqualTo("step-2");

        // Execute the chain manually
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }
        assertThat(log).containsExactly("a", "b");
    }

    // ─── Pause ───

    @Test
    void shouldCompileFixedPause() throws Exception {
        Scenario scenario = Scenario.named("pause-test")
                .execute("before", s -> {})
                .pause(Duration.ofMillis(50))
                .execute("after", s -> {})
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        assertThat(chain.size()).isEqualTo(3);
        assertThat(chain.get(1).name()).isEqualTo("_pause");

        // Verify pause actually waits
        var session = new DefaultSession();
        long start = System.currentTimeMillis();
        chain.get(1).action().execute(session);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(40);
    }

    @Test
    void shouldCompileRandomPause() throws Exception {
        Scenario scenario = Scenario.named("random-pause")
                .pause(Duration.ofMillis(10), Duration.ofMillis(50))
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();

        long start = System.currentTimeMillis();
        chain.get(0).action().execute(session);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(5);
    }

    // ─── Feed ───

    @Test
    void shouldCompileFeedAndInjectIntoSession() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        Feeder feeder = Feeders.generated("test-feeder", Map.of(
                "username", () -> "user-" + counter.incrementAndGet(),
                "role", () -> "admin"
        ));

        Scenario scenario = Scenario.named("feed-test")
                .feed(feeder)
                .execute("use-data", session -> {
                    String user = (String) session.get("username").orElseThrow();
                    String role = (String) session.get("role").orElseThrow();
                    session.put("result", user + ":" + role);
                })
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        assertThat(chain.size()).isEqualTo(2);

        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(session.get("result")).contains("user-1:admin");
    }

    @Test
    void shouldCircularFeederWrapAround() {
        List<Map<String, Object>> rows = List.of(
                Map.of("name", "alice"),
                Map.of("name", "bob")
        );
        Feeder feeder = Feeders.circular("wrap-test", rows);

        assertThat(feeder.next().get("name")).isEqualTo("alice");
        assertThat(feeder.next().get("name")).isEqualTo("bob");
        assertThat(feeder.next().get("name")).isEqualTo("alice"); // wraps
        assertThat(feeder.hasNext()).isTrue();
    }

    @Test
    void shouldRandomFeederPickFromRows() {
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "1"),
                Map.of("id", "2"),
                Map.of("id", "3")
        );
        Feeder feeder = Feeders.random("rand", rows);

        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(feeder.next().get("id"));
        }
        // With 100 draws from 3 items, we should see all 3
        assertThat(seen).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void shouldSequentialFeederExhaust() {
        List<Map<String, Object>> rows = List.of(Map.of("x", "1"));
        Feeder feeder = Feeders.sequential("seq", rows);

        assertThat(feeder.hasNext()).isTrue();
        feeder.next();
        assertThat(feeder.hasNext()).isFalse();
        assertThatThrownBy(feeder::next).isInstanceOf(NoSuchElementException.class);
    }

    // ─── Repeat ───

    @Test
    void shouldCompileRepeatByUnrolling() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("repeat-test")
                .repeat(3, "i", steps -> steps
                        .execute("action", session -> {
                            int i = (int) session.get("i").orElse(-1);
                            log.add("iter-" + i);
                        })
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        // 3 iterations × (1 counter + 1 action) = 6 steps
        assertThat(chain.size()).isEqualTo(6);

        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("iter-0", "iter-1", "iter-2");
    }

    @Test
    void shouldCompileNestedRepeat() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("nested")
                .repeat(2, "outer", outerSteps -> outerSteps
                        .repeat(2, "inner", innerSteps -> innerSteps
                                .execute("action", session -> {
                                    int o = (int) session.get("outer").orElse(-1);
                                    int i = (int) session.get("inner").orElse(-1);
                                    log.add(o + "." + i);
                                })
                        )
                )
                .build();

        var session = new DefaultSession();
        ActionChain chain = ScenarioCompiler.compile(scenario);
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("0.0", "0.1", "1.0", "1.1");
    }

    // ─── RepeatWhile ───

    @Test
    void shouldCompileRepeatWhile() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("while-test")
                .execute("init", session -> session.put("count", 0))
                .repeatWhile(
                        session -> (int) session.get("count").orElse(0) < 3,
                        "loop",
                        steps -> steps.execute("increment", session -> {
                            int c = (int) session.get("count").orElse(0);
                            log.add("iter-" + c);
                            session.put("count", c + 1);
                        })
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("iter-0", "iter-1", "iter-2");
    }

    // ─── IfCondition ───

    @Test
    void shouldCompileIfThenBranch() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("if-test")
                .execute("setup", session -> session.put("admin", true))
                .doIf(
                        session -> (boolean) session.get("admin").orElse(false),
                        "admin-check",
                        steps -> steps.execute("admin-action", s -> log.add("admin"))
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("admin");
    }

    @Test
    void shouldCompileIfElseBranch() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("ifelse-test")
                .execute("setup", session -> session.put("admin", false))
                .doIfElse(
                        session -> (boolean) session.get("admin").orElse(false),
                        "admin-check",
                        thenSteps -> thenSteps.execute("admin-path", s -> log.add("admin")),
                        elseSteps -> elseSteps.execute("user-path", s -> log.add("user"))
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("user");
    }

    // ─── ExitIf ───

    @Test
    void shouldCompileExitIfAndThrowSentinel() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("exit-test")
                .execute("setup", session -> session.put("error", true))
                .exitIf(session -> (boolean) session.get("error").orElse(false))
                .execute("should-not-run", session -> log.add("bad"))
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();

        // Execute step 0
        chain.get(0).action().execute(session);
        // Step 1 should throw ScenarioExitException
        assertThatThrownBy(() -> chain.get(1).action().execute(session))
                .isInstanceOf(ScenarioExitException.class);
        // Step 2 never executes
        assertThat(log).isEmpty();
    }

    @Test
    void shouldNotExitIfConditionIsFalse() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("no-exit")
                .execute("setup", session -> session.put("error", false))
                .exitIf(session -> (boolean) session.get("error").orElse(false))
                .execute("should-run", session -> log.add("ok"))
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly("ok");
    }

    // ─── RandomSwitch ───

    @Test
    void shouldCompileRandomSwitchAndPickBranch() throws Exception {
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());

        Scenario scenario = Scenario.named("switch-test")
                .randomSwitch(branches -> branches
                        .branch(50, steps -> steps.execute("path-a", s -> seen.add("a")))
                        .branch(50, steps -> steps.execute("path-b", s -> seen.add("b")))
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);

        // Run enough times to hit both branches
        for (int i = 0; i < 100; i++) {
            var session = new DefaultSession();
            chain.get(0).action().execute(session);
        }

        assertThat(seen).containsExactlyInAnyOrder("a", "b");
    }

    // ─── Group ───

    @Test
    void shouldCompileGroupWithPrefixedNames() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();

        Scenario scenario = Scenario.named("group-test")
                .group("checkout", steps -> steps
                        .execute("add-to-cart", s -> log.add("cart"))
                        .execute("payment", s -> log.add("pay"))
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        assertThat(chain.size()).isEqualTo(2);
        assertThat(chain.get(0).name()).isEqualTo("checkout.add-to-cart");
        assertThat(chain.get(1).name()).isEqualTo("checkout.payment");

        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }
        assertThat(log).containsExactly("cart", "pay");
    }

    // ─── Complex scenario ───

    @Test
    void shouldCompileFullECommerceScenario() throws Exception {
        var log = new ConcurrentLinkedQueue<String>();
        AtomicInteger userCounter = new AtomicInteger(0);

        Feeder userFeeder = Feeders.generated("users", Map.of(
                "username", () -> "user-" + userCounter.incrementAndGet(),
                "password", (Supplier<Object>) () -> "pass123"
        ));

        Scenario scenario = Scenario.named("e-commerce")
                .feed(userFeeder)
                .execute("login", session -> {
                    String user = (String) session.get("username").orElseThrow();
                    log.add("login:" + user);
                    session.put("loggedIn", true);
                })
                .pause(Duration.ofMillis(10))
                .repeat(2, "browse", steps -> steps
                        .execute("view-product", session -> {
                            int i = (int) session.get("browse").orElse(-1);
                            log.add("browse:" + i);
                        })
                        .pause(Duration.ofMillis(5))
                )
                .doIf(
                        session -> (boolean) session.get("loggedIn").orElse(false),
                        "auth-check",
                        steps -> steps
                                .group("checkout", inner -> inner
                                        .execute("add-to-cart", s -> log.add("cart"))
                                        .execute("pay", s -> log.add("pay"))
                                )
                )
                .build();

        ActionChain chain = ScenarioCompiler.compile(scenario);
        var session = new DefaultSession();
        for (var action : chain) {
            action.action().execute(session);
        }

        assertThat(log).containsExactly(
                "login:user-1",
                "browse:0",
                "browse:1",
                "cart",
                "pay"
        );
    }

    // ─── Scenario builder validation ───

    @Test
    void shouldRejectEmptyScenario() {
        assertThatThrownBy(() -> Scenario.named("empty").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> Scenario.named(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> Scenario.named(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldExposeScenarioNameAndSteps() {
        Scenario scenario = Scenario.named("my-scenario")
                .execute("a", s -> {})
                .pause(Duration.ofMillis(10))
                .build();

        assertThat(scenario.name()).isEqualTo("my-scenario");
        assertThat(scenario.steps()).hasSize(2);
    }

    // ─── PauseStrategy ───

    @Test
    void uniformPauseShouldRejectMinGreaterThanMax() {
        assertThatThrownBy(() -> new PauseStrategy.Uniform(Duration.ofSeconds(5), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonePauseShouldReturnZero() {
        assertThat(new PauseStrategy.None().duration()).isEqualTo(Duration.ZERO);
    }
}