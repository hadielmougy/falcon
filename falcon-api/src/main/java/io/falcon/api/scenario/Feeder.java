package io.falcon.api.scenario;

import java.util.Map;

/**
 * A data source that feeds values into user sessions before action execution.
 * Each call to {@link #next()} returns the next row of data.
 * <p>
 * Feeders enable parameterized load tests — e.g., each virtual user logs in
 * with a different username from a CSV file.
 */
public interface Feeder {

    /**
     * @return the next row of data as key-value pairs to inject into the session
     */
    Map<String, Object> next();

    /**
     * @return true if this feeder has more data (infinite feeders always return true)
     */
    boolean hasNext();

    /**
     * @return a descriptive name for this feeder
     */
    String name();
}