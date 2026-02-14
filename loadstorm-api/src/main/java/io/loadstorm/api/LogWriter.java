package io.loadstorm.api;

/**
 * Writes test results to a log file that can be displayed as a chart.
 */
public interface LogWriter {

    /**
     * Write the complete test result to the configured log file.
     *
     * @param result the test result
     */
    void write(TestResult result);

    /**
     * Append a single metrics snapshot during the test (streaming mode).
     *
     * @param record the execution record
     */
    void append(ExecutionRecord record);

    /**
     * Flush and close the log writer.
     */
    void close();
}

