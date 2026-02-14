package io.loadstorm.api.report;

import io.loadstorm.api.runtime.TestResult;

import java.nio.file.Path;

/**
 * Generates a report from a completed load test result.
 * Implementations can produce HTML, CSV, JSON, or any other format.
 */
public interface ReportGenerator {

    /**
     * Generate a report file from the test result.
     *
     * @param result     the completed test result
     * @param outputPath path where the report file should be written
     * @return the path to the generated report
     */
    Path generate(TestResult result, Path outputPath);

    /**
     * @return the format name (e.g., "HTML", "CSV", "JSON")
     */
    String format();
}