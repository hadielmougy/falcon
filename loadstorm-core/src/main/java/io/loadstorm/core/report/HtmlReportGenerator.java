package io.loadstorm.core.report;

import io.loadstorm.api.log.TestResult;
import io.loadstorm.api.pool.PoolMetricsSnapshot;
import io.loadstorm.api.report.ReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a standalone HTML report from a completed load test.
 * <p>
 * The report includes:
 * <ul>
 *   <li>Test summary (duration, users, total requests, pass/fail rate)</li>
 *   <li>Per-action breakdown table with response times and throughput</li>
 *   <li>Requests per second time-series chart (one line per action)</li>
 *   <li>Response time time-series chart (one line per action)</li>
 *   <li>Active users chart</li>
 *   <li>Response time distribution histogram</li>
 * </ul>
 * <p>
 * The output is a single self-contained HTML file with embedded Chart.js
 * loaded from CDN. No build step required.
 */
public class HtmlReportGenerator implements ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(HtmlReportGenerator.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public Path generate(TestResult result, Path outputPath) {
        String html = buildHtml(result);
        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, html);
            log.info("Report generated: {}", outputPath.toAbsolutePath());
            return outputPath;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write report to " + outputPath, e);
        }
    }

    @Override
    public String format() {
        return "HTML";
    }

    private String buildHtml(TestResult result) {
        // Group snapshots by action
        Map<String, List<PoolMetricsSnapshot>> byAction = result.timeSeriesSnapshots().stream()
                .collect(Collectors.groupingBy(PoolMetricsSnapshot::actionName, LinkedHashMap::new, Collectors.toList()));

        List<String> actionNames = new ArrayList<>(byAction.keySet());

        // Build time labels from first action's snapshots
        List<String> timeLabels = byAction.values().stream()
                .findFirst()
                .map(snaps -> snaps.stream()
                        .map(s -> s.timestamp().atZone(ZoneId.systemDefault()).format(TIME_FMT))
                        .toList())
                .orElse(List.of());

        // Aggregate totals
        long totalSuccess = result.actionSummaries().stream().mapToLong(TestResult.ActionSummary::successCount).sum();
        long totalFailure = result.actionSummaries().stream().mapToLong(TestResult.ActionSummary::failureCount).sum();
        long totalRequests = totalSuccess + totalFailure;
        double overallRps = result.actionSummaries().stream().mapToDouble(TestResult.ActionSummary::requestsPerSecond).sum();
        double overallAvgMs = result.actionSummaries().stream().mapToDouble(TestResult.ActionSummary::averageResponseTimeMs).average().orElse(0);
        double failRate = totalRequests > 0 ? (double) totalFailure / totalRequests * 100 : 0;

        String startTimeStr = result.startTime().atZone(ZoneId.systemDefault()).format(DATETIME_FMT);
        String endTimeStr = result.endTime().atZone(ZoneId.systemDefault()).format(DATETIME_FMT);
        String durationStr = formatDuration(result.totalDuration());

        StringBuilder sb = new StringBuilder();
        sb.append(htmlHead());
        sb.append(bodyOpen());

        // Header
        sb.append(sectionHeader(startTimeStr, endTimeStr, durationStr));

        // Summary cards
        sb.append(summaryCards(result.configuredUsers(), totalRequests, totalSuccess, totalFailure,
                failRate, overallRps, overallAvgMs));

        // Per-action table
        sb.append(actionTable(result.actionSummaries()));

        // Charts
        sb.append(chartSection("rpsChart", "Requests per Second"));
        sb.append(chartSection("rtChart", "Response Time (ms)"));
        sb.append(chartSection("activeChart", "Active Users"));
        sb.append(chartSection("errorChart", "Cumulative Errors"));

        // Footer
        sb.append(footer());
        sb.append(bodyClose());

        // Script with chart data
        sb.append(chartScript(actionNames, byAction, timeLabels));
        sb.append("</html>");

        return sb.toString();
    }

    // ─── HTML sections ───

    private String htmlHead() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>LoadStorm Test Report</title>
                <style>
                :root {
                    --bg: #0f1117; --surface: #161b22; --card: #1c2333;
                    --border: #2a3343; --text: #e1e4e8; --muted: #7a8ba5; --dim: #4a5b73;
                    --blue: #3b8bff; --cyan: #22d3ee; --green: #34d399;
                    --red: #ef4444; --amber: #f59e0b; --purple: #a78bfa;
                }
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); }
                .wrap { max-width: 1200px; margin: 0 auto; padding: 40px 24px; }
                .header { margin-bottom: 40px; }
                .header h1 { font-size: 28px; font-weight: 800; margin-bottom: 8px; }
                .header h1 span { color: var(--blue); }
                .header .meta { font-size: 13px; color: var(--muted); display: flex; gap: 24px; flex-wrap: wrap; }
                .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px; margin-bottom: 40px; }
                .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; }
                .card-label { font-size: 11px; color: var(--dim); text-transform: uppercase; letter-spacing: 1px; margin-bottom: 6px; }
                .card-val { font-size: 26px; font-weight: 800; font-variant-numeric: tabular-nums; }
                .card-val.blue { color: var(--blue); } .card-val.green { color: var(--green); }
                .card-val.red { color: var(--red); } .card-val.amber { color: var(--amber); }
                .card-val.cyan { color: var(--cyan); } .card-val.purple { color: var(--purple); }
                .card-sub { font-size: 12px; color: var(--muted); margin-top: 4px; }
                .section { margin-bottom: 40px; }
                .section h2 { font-size: 18px; font-weight: 700; margin-bottom: 16px; color: var(--muted); }
                table { width: 100%; border-collapse: collapse; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
                th { text-align: left; padding: 12px 16px; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.8px; color: var(--dim); background: rgba(0,0,0,0.3); border-bottom: 1px solid var(--border); }
                td { padding: 12px 16px; font-size: 14px; border-bottom: 1px solid rgba(42,51,67,0.5); font-variant-numeric: tabular-nums; }
                tr:last-child td { border-bottom: none; }
                .action-name { font-weight: 600; color: var(--blue); }
                .pass { color: var(--green); } .fail { color: var(--red); }
                .chart-box { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 20px; margin-bottom: 24px; }
                .chart-box h3 { font-size: 14px; color: var(--muted); margin-bottom: 12px; }
                canvas { width: 100% !important; height: 280px !important; }
                footer { text-align: center; padding: 40px 0 20px; color: var(--dim); font-size: 13px; }
                footer a { color: var(--blue); text-decoration: none; }
                .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; }
                .badge-pass { background: rgba(52,211,153,0.15); color: var(--green); }
                .badge-fail { background: rgba(239,68,68,0.15); color: var(--red); }
                @media print { body { background: #fff; color: #111; } .card, table, .chart-box { border-color: #ddd; } }
                </style>
                </head>
                """;
    }

    private String bodyOpen() {
        return "<body><div class=\"wrap\">\n";
    }

    private String bodyClose() {
        return "</div></body>\n";
    }

    private String sectionHeader(String start, String end, String duration) {
        return """
                <div class="header">
                    <h1><span>LoadStorm</span> Test Report</h1>
                    <div class="meta">
                        <span>Start: %s</span>
                        <span>End: %s</span>
                        <span>Duration: %s</span>
                    </div>
                </div>
                """.formatted(start, end, duration);
    }

    private String summaryCards(int users, long total, long success, long failure,
                                double failRate, double rps, double avgMs) {
        return """
                <div class="cards">
                    <div class="card"><div class="card-label">Virtual Users</div><div class="card-val blue">%d</div></div>
                    <div class="card"><div class="card-label">Total Requests</div><div class="card-val cyan">%s</div></div>
                    <div class="card"><div class="card-label">Successful</div><div class="card-val green">%s</div><div class="card-sub">%s</div></div>
                    <div class="card"><div class="card-label">Failed</div><div class="card-val red">%s</div><div class="card-sub">%s</div></div>
                    <div class="card"><div class="card-label">Failure Rate</div><div class="card-val %s">%.2f%%</div></div>
                    <div class="card"><div class="card-label">Avg RPS</div><div class="card-val amber">%.1f</div></div>
                    <div class="card"><div class="card-label">Avg Response</div><div class="card-val purple">%.1f ms</div></div>
                </div>
                """.formatted(
                users,
                formatNumber(total),
                formatNumber(success), String.format("%.1f%%", total > 0 ? (double) success / total * 100 : 0),
                formatNumber(failure), String.format("%.1f%%", failRate),
                failRate > 5 ? "red" : "green", failRate,
                rps, avgMs);
    }

    private String actionTable(List<TestResult.ActionSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="section">
                <h2>Per-Action Breakdown</h2>
                <table>
                <thead><tr>
                    <th>Action</th><th>Total</th><th>Success</th><th>Failed</th>
                    <th>Avg (ms)</th><th>p95 (ms)</th><th>p99 (ms)</th><th>RPS</th><th>Status</th>
                </tr></thead>
                <tbody>
                """);

        for (var s : summaries) {
            String status = s.failureCount() == 0
                    ? "<span class=\"badge badge-pass\">PASS</span>"
                    : "<span class=\"badge badge-fail\">" + s.failureCount() + " FAIL</span>";

            sb.append("""
                    <tr>
                        <td class="action-name">%s</td>
                        <td>%s</td>
                        <td class="pass">%s</td>
                        <td class="fail">%s</td>
                        <td>%.1f</td>
                        <td>%.1f</td>
                        <td>%.1f</td>
                        <td>%.1f</td>
                        <td>%s</td>
                    </tr>
                    """.formatted(
                    s.actionName(),
                    formatNumber(s.totalRequests()),
                    formatNumber(s.successCount()),
                    formatNumber(s.failureCount()),
                    s.averageResponseTimeMs(),
                    s.p95ResponseTimeMs(),
                    s.p99ResponseTimeMs(),
                    s.requestsPerSecond(),
                    status));
        }

        sb.append("</tbody></table></div>\n");
        return sb.toString();
    }

    private String chartSection(String id, String title) {
        return """
                <div class="chart-box"><h3>%s</h3><canvas id="%s"></canvas></div>
                """.formatted(title, id);
    }

    private String footer() {
        return """
                <footer>
                    Generated by <a href="https://github.com/hadielmougy/loadstorm">LoadStorm</a>
                </footer>
                """;
    }

    // ─── Chart data & script ───

    private String chartScript(List<String> actionNames,
                               Map<String, List<PoolMetricsSnapshot>> byAction,
                               List<String> timeLabels) {
        String[] colors = {"#3b8bff", "#34d399", "#f59e0b", "#ef4444", "#a78bfa", "#22d3ee", "#f0883e", "#e3b341"};

        // Build JSON arrays for each action's time series
        StringBuilder rpsDatasets = new StringBuilder("[");
        StringBuilder rtDatasets = new StringBuilder("[");
        StringBuilder activeDatasets = new StringBuilder("[");
        StringBuilder errorDatasets = new StringBuilder("[");

        for (int i = 0; i < actionNames.size(); i++) {
            String name = actionNames.get(i);
            String color = colors[i % colors.length];
            List<PoolMetricsSnapshot> snaps = byAction.get(name);

            String rpsArr = snaps.stream().map(s -> String.format("%.2f", s.requestsPerSecond())).collect(Collectors.joining(","));
            String rtArr = snaps.stream().map(s -> String.format("%.2f", s.averageResponseTimeMs())).collect(Collectors.joining(","));
            String activeArr = snaps.stream().map(s -> String.valueOf(s.activeCount())).collect(Collectors.joining(","));
            String errorArr = snaps.stream().map(s -> String.valueOf(s.failedCount())).collect(Collectors.joining(","));

            String comma = i > 0 ? "," : "";
            String ds = "{label:'%s',data:[%s],borderColor:'%s',backgroundColor:'transparent',tension:0.3,pointRadius:0,borderWidth:2}";
            rpsDatasets.append(comma).append(ds.formatted(name, rpsArr, color));
            rtDatasets.append(comma).append(ds.formatted(name, rtArr, color));
            activeDatasets.append(comma).append(ds.formatted(name, activeArr, color));
            errorDatasets.append(comma).append(ds.formatted(name, errorArr, color));
        }

        rpsDatasets.append("]");
        rtDatasets.append("]");
        activeDatasets.append("]");
        errorDatasets.append("]");

        String labelsJson = timeLabels.stream().map(t -> "'" + t + "'").collect(Collectors.joining(",", "[", "]"));

        return """
                <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js"></script>
                <script>
                const labels = %s;
                const chartOpts = {
                    responsive: true,
                    animation: false,
                    interaction: { mode: 'index', intersect: false },
                    scales: {
                        x: { ticks: { color: '#7a8ba5', maxTicksLimit: 15, font: { size: 11 } }, grid: { color: '#1c2333' } },
                        y: { beginAtZero: true, ticks: { color: '#7a8ba5', font: { size: 11 } }, grid: { color: '#1c2333' } }
                    },
                    plugins: {
                        legend: { labels: { color: '#e1e4e8', usePointStyle: true, pointStyle: 'circle', padding: 16 } },
                        tooltip: { backgroundColor: '#1c2333', borderColor: '#2a3343', borderWidth: 1, titleColor: '#e1e4e8', bodyColor: '#7a8ba5' }
                    }
                };
                
                new Chart(document.getElementById('rpsChart'), {
                    type: 'line', data: { labels, datasets: %s }, options: { ...chartOpts }
                });
                new Chart(document.getElementById('rtChart'), {
                    type: 'line', data: { labels, datasets: %s }, options: { ...chartOpts }
                });
                new Chart(document.getElementById('activeChart'), {
                    type: 'line', data: { labels, datasets: %s }, options: { ...chartOpts }
                });
                new Chart(document.getElementById('errorChart'), {
                    type: 'line', data: { labels, datasets: %s }, options: { ...chartOpts }
                });
                </script>
                """.formatted(labelsJson, rpsDatasets, rtDatasets, activeDatasets, errorDatasets);
    }

    // ─── Utilities ───

    private static String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return "%dh %dm %ds".formatted(hours, minutes, seconds);
        if (minutes > 0) return "%dm %ds".formatted(minutes, seconds);
        return "%ds".formatted(seconds);
    }

    private static String formatNumber(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }
}