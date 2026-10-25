package com.seismic.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Base class for effects that are active during a fixed time window. */
public abstract class TimedEffect implements ScenarioEffect {

    private final ScenarioType type;
    protected final Instant start;
    protected final Instant end;

    protected TimedEffect(ScenarioType type, Instant start, double durationSeconds) {
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("Effect duration must be positive");
        }
        this.type = Objects.requireNonNull(type);
        this.start = Objects.requireNonNull(start);
        this.end = start.plus(Duration.ofMillis(Math.round(durationSeconds * 1000)));
    }

    @Override
    public ScenarioType type() {
        return type;
    }

    @Override
    public boolean isExpired(Instant time) {
        return !time.isBefore(end);
    }

    protected boolean isWithinWindow(Instant time) {
        return !time.isBefore(start) && time.isBefore(end);
    }

    protected static double between(java.util.Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }
}
