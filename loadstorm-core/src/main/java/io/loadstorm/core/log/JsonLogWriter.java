package io.loadstorm.core.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.loadstorm.api.log.ExecutionRecord;
import io.loadstorm.api.log.LogWriter;
import io.loadstorm.api.metrics.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes test results and execution records to a JSON log file.
 * The log file contains all records that can be displayed as a chart.
 */
public class JsonLogWriter implements LogWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonLogWriter.class);

    private final Path logFilePath;
    private final ObjectMapper objectMapper;
    private BufferedWriter streamWriter;

    public JsonLogWriter(String logFilePath) {
        this.logFilePath = Path.of(logFilePath);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void write(TestResult result) {
        try {
            objectMapper.writeValue(logFilePath.toFile(), result);
            log.info("Test results written to: {}", logFilePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write test results to {}", logFilePath, e);
        }
    }

    @Override
    public void append(ExecutionRecord record) {
        try {
            if (streamWriter == null) {
                Path streamPath = Path.of(logFilePath.toString().replace(".json", "-stream.jsonl"));
                streamWriter = Files.newBufferedWriter(streamPath);
            }
            streamWriter.write(objectMapper.writeValueAsString(record));
            streamWriter.newLine();
            streamWriter.flush();
        } catch (IOException e) {
            log.error("Failed to append execution record", e);
        }
    }

    @Override
    public void close() {
        if (streamWriter != null) {
            try {
                streamWriter.close();
            } catch (IOException e) {
                log.error("Failed to close stream writer", e);
            }
        }
    }
}
