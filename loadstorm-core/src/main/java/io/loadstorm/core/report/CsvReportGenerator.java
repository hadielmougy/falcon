package io.loadstorm.core.report;

import io.loadstorm.api.runtime.TestResult;
import io.loadstorm.api.pool.PoolMetricsSnapshot;
import io.loadstorm.api.report.ReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates CSV reports from a completed load test.
 * <p>
 * Produces two files:
 * <ul>
 *   <li>{name}-summary.csv — per-action summary statistics</li>
 *   <li>{name}-timeseries.csv — every metrics snapshot for charting in Excel/Grafana</li>
 * </ul>
 */
public class CsvReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(CsvReportGenerator.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    @Override
    public Path generate(TestResult result, Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());

            String baseName = outputPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            Path dir = outputPath.getParent();

            Path summaryPath = dir.resolve(baseName + "-summary.csv");
            Path timeseriesPath = dir.resolve(baseName + "-timeseries.csv");

            writeSummary(result, summaryPath);
            writeTimeSeries(result, timeseriesPath);

            log.info("CSV reports generated: {} and {}", summaryPath, timeseriesPath);
            return summaryPath;

        } catch (IOException e) {
            throw new RuntimeException("Failed to write CSV report", e);
        }
    }

    @Override
    public String format() {
        return "CSV";
    }

    private void writeSummary(TestResult result, Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("action,total_requests,success,failure,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,rps");

        for (var s : result.actionSummaries()) {
            lines.add("%s,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f".formatted(
                    escapeCsv(s.actionName()),
                    s.totalRequests(),
                    s.successCount(),
                    s.failureCount(),
                    s.averageResponseTimeMs(),
                    s.p50ResponseTimeMs(),
                    s.p95ResponseTimeMs(),
                    s.p99ResponseTimeMs(),
                    s.maxResponseTimeMs(),
                    s.requestsPerSecond()
            ));
        }

        Files.write(path, lines);
    }

    private void writeTimeSeries(TestResult result, Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp,action,active_users,completed,failed,avg_response_ms,p99_response_ms,rps");

        for (PoolMetricsSnapshot snap : result.timeSeriesSnapshots()) {
            String ts = snap.timestamp().atZone(ZoneId.systemDefault()).format(TS_FMT);
            lines.add("%s,%s,%d,%d,%d,%.2f,%.2f,%.2f".formatted(
                    ts,
                    escapeCsv(snap.actionName()),
                    snap.activeCount(),
                    snap.completedCount(),
                    snap.failedCount(),
                    snap.averageResponseTimeMs(),
                    snap.p99ResponseTimeMs(),
                    snap.requestsPerSecond()
            ));
        }

        Files.write(path, lines);
    }

    private static String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}