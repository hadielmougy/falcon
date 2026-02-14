package io.loadstorm.web;


import io.loadstorm.api.environment.EnvironmentConfig;
import io.loadstorm.core.client.DefaultLoadClient;
import io.loadstorm.web.environment.WebEnvironment;

import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

public class WebExample {

    public static void main(String[] args) {
        var httpClient = HttpClient.newHttpClient();

        // Define your client with action chain
        var client = DefaultLoadClient.nonBlocking();

        client.execute("get-homepage", session -> {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://jsonplaceholder.typicode.com/posts/1"))
                    .GET()
                    .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            session.put("status", response.statusCode());
        });

        client.execute("get-comments", session -> {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://jsonplaceholder.typicode.com/posts/1/comments"))
                    .GET()
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        });

        // Configure the environment
        var config = EnvironmentConfig.create()
                .numberOfUsers(20)
                .rampUpTime(Duration.ofSeconds(10))
                .testDuration(Duration.ofMinutes(5))
                .connectionPoolSize(50)
                .logFilePath("loadstorm-results.json");

        // Start web environment
        var webEnv = new WebEnvironment(config, 8080);
        webEnv.registerClient(client);
        webEnv.startServer();

        /*
        webEnv.startServer();
        webEnv.start(client); // starts immediately
*/
        System.out.println("Dashboard: http://localhost:8080");
        System.out.println("SSE stream: http://localhost:8080/api/metrics/stream");
        System.out.println();
        System.out.println("POST http://localhost:8080/api/test/start  → start the test");
        System.out.println("POST http://localhost:8080/api/test/stop   → stop the test");
        System.out.println("GET  http://localhost:8080/api/metrics/snapshot → current metrics");
    }
}
