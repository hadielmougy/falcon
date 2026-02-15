package io.falcon.core.report;

import io.falcon.api.runtime.TestResult;
import io.falcon.api.pool.PoolMetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportGeneratorTest {

    @TempDir
    Path tempDir;

    private TestResult buildTestResult() {
        Instant start = Instant.parse("2026-02-14T10:00:00Z");
        Instant end = Instant.parse("2026-02-14T10:05:00Z");

        List<TestResult.ActionSummary> summaries = List.of(
                new TestResult.ActionSummary("login", 5000, 4950, 50, 42.3, 38.0, 78.5, 120.1, 250.0, 16.7),
                new TestResult.ActionSummary("browse", 15000, 14980, 20, 28.1, 25.0, 55.2, 88.7, 180.0, 50.0),
                new TestResult.ActionSummary("checkout", 2000, 1990, 10, 95.6, 88.0, 150.3, 210.5, 450.0, 6.7)
        );

        // Generate 60 snapshots (one per second for each action over 20 seconds)
        List<PoolMetricsSnapshot> snapshots = new ArrayList<>();
        for (int t = 0; t < 20; t++) {
            Instant ts = start.plusSeconds(t);
            snapshots.add(new PoolMetricsSnapshot("login", 100, 100, 0, 250 + t * 12, 3 + t / 10, 42.0 + t * 0.1, 120.0 + t * 0.5, 16.5 + Math.sin(t) * 2, ts));
            snapshots.add(new PoolMetricsSnapshot("browse", 100, 100, 0, 750 + t * 37, 1 + t / 20, 28.0 + t * 0.05, 88.0 + t * 0.3, 50.0 + Math.sin(t) * 5, ts));
            snapshots.add(new PoolMetricsSnapshot("checkout", 30, 100, 0, 100 + t * 5, 0, 95.0 + t * 0.2, 210.0 + t * 0.8, 6.5 + Math.cos(t) * 1, ts));
        }

        return new TestResult(start, end, Duration.between(start, end), 100, summaries, snapshots);
    }

    // ─── HTML Report ───

    @Test
    void shouldGenerateHtmlReport() throws IOException {
        Path output = tempDir.resolve("report.html");
        var generator = new HtmlReportGenerator();

        Path result = generator.generate(buildTestResult(), output);

        assertThat(result).exists();
        String content = Files.readString(result);

        // Structure
        assertThat(content).contains("<!DOCTYPE html>");
        assertThat(content).contains("<title>Falcon Test Report</title>");
        assertThat(content).contains("chart.js");

        // Summary data
        assertThat(content).contains("100");             // users
        assertThat(content).contains("5m 0s");           // duration

        // Action names
        assertThat(content).contains("login");
        assertThat(content).contains("browse");
        assertThat(content).contains("checkout");

        // Chart canvases
        assertThat(content).contains("rpsChart");
        assertThat(content).contains("rtChart");
        assertThat(content).contains("activeChart");
        assertThat(content).contains("errorChart");
    }

    @Test
    void htmlReportShouldBeStandalone() throws IOException {
        Path output = tempDir.resolve("standalone.html");
        new HtmlReportGenerator().generate(buildTestResult(), output);

        String content = Files.readString(output);

        // All CSS should be inline
        assertThat(content).contains("<style>");
        // Chart.js is loaded from CDN
        assertThat(content).contains("cdn.jsdelivr.net/npm/chart.js");
        // No external CSS references
        assertThat(content).doesNotContain("<link rel=\"stylesheet\"");
    }

    @Test
    void htmlReportShouldContainChartData() throws IOException {
        Path output = tempDir.resolve("charts.html");
        new HtmlReportGenerator().generate(buildTestResult(), output);

        String content = Files.readString(output);

        // Should have chart datasets with action names
        assertThat(content).contains("label:'login'");
        assertThat(content).contains("label:'browse'");
        assertThat(content).contains("label:'checkout'");

        // Should have data arrays
        assertThat(content).contains("new Chart(");
    }

    @Test
    void htmlReportShouldShowPassFailBadges() throws IOException {
        Path output = tempDir.resolve("badges.html");
        new HtmlReportGenerator().generate(buildTestResult(), output);

        String content = Files.readString(output);

        // login has 50 failures → FAIL badge
        assertThat(content).contains("badge-fail");
    }

    @Test
    void htmlFormatShouldBeHTML() {
        assertThat(new HtmlReportGenerator().format()).isEqualTo("HTML");
    }

    @Test
    void htmlReportShouldCreateDirectories() throws IOException {
        Path output = tempDir.resolve("sub/dir/report.html");
        new HtmlReportGenerator().generate(buildTestResult(), output);

        assertThat(output).exists();
    }

    // ─── CSV Report ───

    @Test
    void shouldGenerateCsvSummary() throws IOException {
        Path output = tempDir.resolve("report.csv");
        var generator = new CsvReportGenerator();

        generator.generate(buildTestResult(), output);

        Path summaryPath = tempDir.resolve("report-summary.csv");
        assertThat(summaryPath).exists();

        List<String> lines = Files.readAllLines(summaryPath);

        // Header + 3 action rows
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).contains("action,total_requests,success,failure");
        assertThat(lines.get(1)).startsWith("login,");
        assertThat(lines.get(2)).startsWith("browse,");
        assertThat(lines.get(3)).startsWith("checkout,");
    }

    @Test
    void shouldGenerateCsvTimeSeries() throws IOException {
        Path output = tempDir.resolve("report.csv");
        new CsvReportGenerator().generate(buildTestResult(), output);

        Path timeseriesPath = tempDir.resolve("report-timeseries.csv");
        assertThat(timeseriesPath).exists();

        List<String> lines = Files.readAllLines(timeseriesPath);

        // Header + 60 rows (20 snapshots × 3 actions)
        assertThat(lines).hasSize(61);
        assertThat(lines.get(0)).contains("timestamp,action,active_users");
        assertThat(lines.get(1)).contains("login");
    }

    @Test
    void csvSummaryShouldBeParseable() throws IOException {
        Path output = tempDir.resolve("data.csv");
        new CsvReportGenerator().generate(buildTestResult(), output);

        Path summaryPath = tempDir.resolve("data-summary.csv");
        List<String> lines = Files.readAllLines(summaryPath);

        // Parse first data row (login)
        String[] fields = lines.get(1).split(",");
        assertThat(fields[0]).isEqualTo("login");
        assertThat(Long.parseLong(fields[1])).isEqualTo(5000);   // total
        assertThat(Long.parseLong(fields[2])).isEqualTo(4950);   // success
        assertThat(Long.parseLong(fields[3])).isEqualTo(50);     // failure
        assertThat(Double.parseDouble(fields[4])).isEqualTo(42.0); // avg ms
    }

    @Test
    void csvFormatShouldBeCSV() {
        assertThat(new CsvReportGenerator().format()).isEqualTo("CSV");
    }

    // ─── Edge cases ───

    @Test
    void shouldHandleEmptySnapshots() throws IOException {
        TestResult emptyTimeSeries = new TestResult(
                Instant.now(), Instant.now(), Duration.ZERO, 0,
                List.of(new TestResult.ActionSummary("idle", 0, 0, 0, 0, 0, 0, 0, 0, 0)),
                List.of()
        );

        Path htmlOutput = tempDir.resolve("empty.html");
        new HtmlReportGenerator().generate(emptyTimeSeries, htmlOutput);
        assertThat(htmlOutput).exists();
        assertThat(Files.readString(htmlOutput)).contains("idle");

        Path csvOutput = tempDir.resolve("empty.csv");
        new CsvReportGenerator().generate(emptyTimeSeries, csvOutput);
        assertThat(tempDir.resolve("empty-summary.csv")).exists();
    }

    @Test
    void shouldHandleSingleSnapshot() throws IOException {
        Instant now = Instant.now();
        TestResult single = new TestResult(
                now, now.plusSeconds(1), Duration.ofSeconds(1), 1,
                List.of(new TestResult.ActionSummary("ping", 1, 1, 0, 5.0, 5.0, 5.0, 5.0, 5.0, 1.0)),
                List.of(new PoolMetricsSnapshot("ping", 1, 1, 0, 1, 0, 5.0, 5.0, 1.0, now))
        );

        Path output = tempDir.resolve("single.html");
        new HtmlReportGenerator().generate(single, output);
        assertThat(Files.readString(output)).contains("ping");
    }

    @Test
    void shouldFormatLargeNumbers() throws IOException {
        TestResult bigNumbers = new TestResult(
                Instant.now(), Instant.now().plusSeconds(3600), Duration.ofHours(1), 1000,
                List.of(new TestResult.ActionSummary("flood", 1_500_000, 1_499_000, 1_000, 12.5, 10.0, 25.0, 50.0, 200.0, 416.7)),
                List.of()
        );

        Path output = tempDir.resolve("big.html");
        new HtmlReportGenerator().generate(bigNumbers, output);
        String content = Files.readString(output);

        // Should use K/M formatting
        assertThat(content).contains("1,5M");  // total requests
        assertThat(content).contains("1h");    // duration
    }
}