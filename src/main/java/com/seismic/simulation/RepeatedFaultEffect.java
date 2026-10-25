package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;

/**
 * A malfunctioning sensor: for the effect window it repeatedly emits short high-frequency spikes
 * and occasionally an impossible out-of-range value, while neighbouring sensors stay quiet.
 */
public final class RepeatedFaultEffect extends SingleSensorEffect {

    private static final double SPIKE_PROBABILITY = 0.45;
    private static final double OUT_OF_RANGE_PROBABILITY = 0.10;

    public RepeatedFaultEffect(String targetSensorId, Instant start, double durationSeconds) {
        super(ScenarioType.SENSOR_FAULT, targetSensorId, start, durationSeconds);
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        double roll = random.nextDouble();
        if (roll < OUT_OF_RANGE_PROBABILITY) {
            draft.amplitude = between(random, 2.3, 3.0); // beyond the 0..2 sensor range
        } else if (roll < OUT_OF_RANGE_PROBABILITY + SPIKE_PROBABILITY) {
            draft.amplitude = between(random, 0.80, 1.40);
            draft.frequencyHz = between(random, 20, 45);
            draft.durationSeconds = between(random, 0.05, 0.30);
        }
        draft.label = "SENSOR_FAULT";
    }
}
