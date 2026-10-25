package com.seismic.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One start-to-stop period of live monitoring, so persisted readings and events can be traced
 * back to the simulation settings that produced them.
 *
 * @param runId database ID, or null when running in memory only
 */
public record SimulationRun(Long runId, Instant startedAt, Instant endedAt, long randomSeed,
                            int sampleIntervalMillis, int sensorCount, long readingsProcessed, int eventsDetected) {

    public SimulationRun {
        Objects.requireNonNull(startedAt, "startedAt");
        if (sampleIntervalMillis <= 0 || sensorCount <= 0) {
            throw new IllegalArgumentException("Sample interval and sensor count must be positive");
        }
    }

    public SimulationRun withId(Long id) {
        return new SimulationRun(id, startedAt, endedAt, randomSeed, sampleIntervalMillis, sensorCount,
                readingsProcessed, eventsDetected);
    }

    public SimulationRun finished(Instant end, long readings, int events) {
        return new SimulationRun(runId, startedAt, end, randomSeed, sampleIntervalMillis, sensorCount, readings, events);
    }
}
