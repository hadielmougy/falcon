package io.falcon.web;

import io.falcon.api.environment.EnvironmentConfig;
import io.falcon.api.scenario.Feeders;
import io.falcon.api.scenario.Scenario;
import io.falcon.core.client.DefaultLoadClient;
import io.falcon.web.environment.WebEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Example load test using the Scenario DSL with the web dashboard.
 * <p>
 * Simulates a realistic user journey against jsonplaceholder.typicode.com:
 * <ol>
 *   <li>Feed: inject a unique userId into the session</li>
 *   <li>Login: fetch the user profile (simulated login)</li>
 *   <li>Pause: 500ms–2s think time</li>
 *   <li>Browse: repeat 3 times — view a post, then pause</li>
 *   <li>Conditional: if the user is "premium" (userId <= 3), enter checkout</li>
 *   <li>Random branch: 70% view comments, 30% view albums</li>
 * </ol>
 * <p>
 * Run with:
 * <pre>{@code
 * mvn compile exec:java -pl falcon-web \
 *   -Dexec.mainClass="io.falcon.example.WebExample"
 * }</pre>
 * Then open http://localhost:8080 for the dashboard.
 */
public class WebExample {

    public static void main(String[] args) {
        var httpClient = HttpClient.newHttpClient();
        var handler = HttpResponse.BodyHandlers.ofString();

        // ─── Feeder: generates a rotating userId 1–10 ───
        AtomicInteger userIdCounter = new AtomicInteger(0);
        var userFeeder = Feeders.generated("user-ids", Map.of(
                "userId", () -> (userIdCounter.getAndIncrement() % 10) + 1
        ));

        // ─── Scenario ───
        Scenario scenario = Scenario.named("jsonplaceholder-journey")

                // Inject userId into session
                .feed(userFeeder)

                // Step 1: Login (fetch user profile)
                .execute("login", session -> {
                    int userId = (int) session.get("userId").orElseThrow();
                    var request = HttpRequest.newBuilder()
                            .uri(URI.create("https://jsonplaceholder.typicode.com/users/" + userId))
                            .GET()
                            .build();
                    var response = httpClient.send(request, handler);
                    session.put("status", response.statusCode());
                    session.put("premium", userId <= 3);
                })

                // Think time after login
                .pause(Duration.ofMillis(500), Duration.ofSeconds(2))

                // Step 2: Browse posts (3 iterations)
                .repeat(3, "browseIndex", steps -> steps
                        .execute("view-post", session -> {
                            int postIndex = (int) session.get("browseIndex").orElse(0);
                            int userId = (int) session.get("userId").orElseThrow();
                            int postId = (userId - 1) * 10 + postIndex + 1;
                            var request = HttpRequest.newBuilder()
                                    .uri(URI.create("https://jsonplaceholder.typicode.com/posts/" + postId))
                                    .GET()
                                    .build();
                            httpClient.send(request, handler);
                        })
                        .pause(Duration.ofMillis(200), Duration.ofMillis(800))
                )

                // Step 3: Exit early if login failed
                .exitIf(session -> {
                    int status = (int) session.get("status").orElse(200);
                    return status != 200;
                })

                // Step 4: Checkout flow — only for premium users
                .doIf(
                        session -> (boolean) session.get("premium").orElse(false),
                        "premium-check",
                        checkoutSteps -> checkoutSteps
                                .group("checkout", group -> group
                                        .execute("add-to-cart", session -> {
                                            int userId = (int) session.get("userId").orElseThrow();
                                            var request = HttpRequest.newBuilder()
                                                    .uri(URI.create("https://jsonplaceholder.typicode.com/todos?userId=" + userId))
                                                    .GET()
                                                    .build();
                                            httpClient.send(request, handler);
                                        })
                                        .pause(Duration.ofMillis(300), Duration.ofMillis(600))
                                        .execute("confirm-order", session -> {
                                            var request = HttpRequest.newBuilder()
                                                    .uri(URI.create("https://jsonplaceholder.typicode.com/posts"))
                                                    .POST(HttpRequest.BodyPublishers.ofString(
                                                            """
                                                            {"title":"order","body":"confirmed","userId":1}
                                                            """))
                                                    .header("Content-Type", "application/json")
                                                    .build();
                                            httpClient.send(request, handler);
                                        })
                                )
                )

                // Step 5: Random engagement — 70% comments, 30% albums
                .randomSwitch(branches -> branches
                        .branch(70, commentSteps -> commentSteps
                                .execute("view-comments", session -> {
                                    var request = HttpRequest.newBuilder()
                                            .uri(URI.create("https://jsonplaceholder.typicode.com/posts/1/comments"))
                                            .GET()
                                            .build();
                                    httpClient.send(request, handler);
                                })
                        )
                        .branch(30, albumSteps -> albumSteps
                                .execute("view-albums", session -> {
                                    int userId = (int) session.get("userId").orElseThrow();
                                    var request = HttpRequest.newBuilder()
                                            .uri(URI.create("https://jsonplaceholder.typicode.com/users/" + userId + "/albums"))
                                            .GET()
                                            .build();
                                    httpClient.send(request, handler);
                                })
                        )
                )

                .build();

        // ─── Client ───
        var client = DefaultLoadClient.nonBlocking();
        client.execute(scenario);

        // ─── Environment ───
        var config = EnvironmentConfig.create()
                .numberOfUsers(50)
                .rampUpTime(Duration.ofSeconds(15))
                .testDuration(Duration.ofMinutes(3))
                .reportPath("/Users/hadielmougy/source/falcon/falcon");

        // ─── Web dashboard ───
        var webEnv = new WebEnvironment(config, 8080);
        webEnv.registerClient(client);
        webEnv.startServer();

        System.out.println("""
                
                ⚡ Falcon Web Dashboard
                ─────────────────────────
                Dashboard:  http://localhost:8080
                SSE stream: http://localhost:8080/api/metrics/stream
                
                POST http://localhost:8080/api/test/start  → start the test
                POST http://localhost:8080/api/test/stop   → stop the test
                GET  http://localhost:8080/api/metrics/snapshot → current metrics
                
                Scenario: jsonplaceholder-journey
                  → feed(user-ids) → login → pause → repeat(3, browse) →
                    exitIf(error) → if(premium, checkout) → randomSwitch(comments|albums)
                """);
    }
}