package io.falcon.api.scenario;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Factory methods for common feeder types.
 */
public final class Feeders {

    private Feeders() {}

    /**
     * Create a feeder from a CSV file. First row is treated as headers.
     * Rows are served sequentially, wrapping around when exhausted.
     *
     * @param path path to the CSV file
     * @return a circular CSV feeder
     */
    public static Feeder csv(Path path) {
        return csv(path, ",");
    }

    /**
     * Create a feeder from a CSV file with a custom delimiter.
     */
    public static Feeder csv(Path path, String delimiter) {
        try {
            List<String> lines = Files.readAllLines(path);
            if (lines.size() < 2) {
                throw new IllegalArgumentException("CSV file must have a header row and at least one data row");
            }

            String[] headers = lines.getFirst().split(delimiter);
            List<Map<String, Object>> rows = new ArrayList<>();

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] values = line.split(delimiter, -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < values.length; j++) {
                    row.put(headers[j].trim(), values[j].trim());
                }
                rows.add(row);
            }

            return circular(path.getFileName().toString(), rows);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV file: " + path, e);
        }
    }

    /**
     * Create a feeder from a CSV resource on the classpath.
     */
    public static Feeder csvResource(String resourcePath) {
        return csvResource(resourcePath, ",");
    }

    /**
     * Create a feeder from a CSV classpath resource with a custom delimiter.
     */
    public static Feeder csvResource(String resourcePath, String delimiter) {
        try (var is = Feeders.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }

            List<String> lines = new BufferedReader(new InputStreamReader(is)).lines().toList();
            if (lines.size() < 2) {
                throw new IllegalArgumentException("CSV resource must have a header and at least one data row");
            }

            String[] headers = lines.getFirst().split(delimiter);
            List<Map<String, Object>> rows = new ArrayList<>();

            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] values = line.split(delimiter, -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.length && j < values.length; j++) {
                    row.put(headers[j].trim(), values[j].trim());
                }
                rows.add(row);
            }

            return circular(resourcePath, rows);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV resource: " + resourcePath, e);
        }
    }

    /**
     * Create a circular feeder that wraps around when exhausted.
     */
    public static Feeder circular(String name, List<Map<String, Object>> rows) {
        AtomicInteger index = new AtomicInteger(0);
        return new Feeder() {
            @Override
            public Map<String, Object> next() {
                int i = index.getAndUpdate(v -> (v + 1) % rows.size());
                return rows.get(i);
            }

            @Override
            public boolean hasNext() {
                return true; // circular never runs out
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /**
     * Create a random feeder that picks rows randomly.
     */
    public static Feeder random(String name, List<Map<String, Object>> rows) {
        return new Feeder() {
            @Override
            public Map<String, Object> next() {
                return rows.get(ThreadLocalRandom.current().nextInt(rows.size()));
            }

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /**
     * Create a feeder that generates values dynamically using suppliers.
     * <p>
     * Example:
     * <pre>{@code
     * Feeders.generated("random-users", Map.of(
     *     "username", () -> "user-" + UUID.randomUUID(),
     *     "email", () -> "test+" + counter.incrementAndGet() + "@example.com"
     * ));
     * }</pre>
     */
    public static Feeder generated(String name, Map<String, Supplier<Object>> generators) {
        return new Feeder() {
            @Override
            public Map<String, Object> next() {
                Map<String, Object> row = new LinkedHashMap<>();
                generators.forEach((key, supplier) -> row.put(key, supplier.get()));
                return row;
            }

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /**
     * Create a sequential feeder that exhausts after all rows are consumed.
     */
    public static Feeder sequential(String name, List<Map<String, Object>> rows) {
        AtomicInteger index = new AtomicInteger(0);
        return new Feeder() {
            @Override
            public Map<String, Object> next() {
                int i = index.getAndIncrement();
                if (i >= rows.size()) {
                    throw new NoSuchElementException("Feeder '" + name + "' exhausted after " + rows.size() + " rows");
                }
                return rows.get(i);
            }

            @Override
            public boolean hasNext() {
                return index.get() < rows.size();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}