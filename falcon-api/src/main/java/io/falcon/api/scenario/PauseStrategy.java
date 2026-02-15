package io.falcon.api.scenario;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Defines how virtual users pause between actions (think time).
 * Without pauses, users loop at maximum speed which doesn't reflect real traffic.
 */
public sealed interface PauseStrategy {

    /**
     * @return the duration to pause for this invocation
     */
    Duration duration();

    /**
     * Fixed pause duration.
     */
    record Fixed(Duration value) implements PauseStrategy {
        @Override
        public Duration duration() {
            return value;
        }
    }

    /**
     * Random pause between min and max (uniform distribution).
     */
    record Uniform(Duration min, Duration max) implements PauseStrategy {
        public Uniform {
            if (min.compareTo(max) > 0) {
                throw new IllegalArgumentException("min must be <= max");
            }
        }

        @Override
        public Duration duration() {
            long minMs = min.toMillis();
            long maxMs = max.toMillis();
            long pauseMs = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
            return Duration.ofMillis(pauseMs);
        }
    }

    /**
     * No pause.
     */
    record None() implements PauseStrategy {
        @Override
        public Duration duration() {
            return Duration.ZERO;
        }
    }
}