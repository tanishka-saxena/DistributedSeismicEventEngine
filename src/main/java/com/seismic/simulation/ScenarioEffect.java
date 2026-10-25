package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;

/**
 * A temporary change to what one or more sensors report. The simulator asks every active effect
 * whether it applies to a sensor at a given time, then lets it modify the reading draft.
 * Implementations must be safe to call from several sampling threads at once.
 */
public interface ScenarioEffect {

    ScenarioType type();

    /** True if this effect changes the reading of {@code sensor} taken at {@code time}. */
    boolean appliesTo(Sensor sensor, Instant time);

    /** Modifies the draft. Only called when {@link #appliesTo} returned true. */
    void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random);

    /** True once the effect can no longer apply to any reading at or after {@code time}. */
    boolean isExpired(Instant time);

    /** Human-readable summary for the UI log. */
    String describe();
}
