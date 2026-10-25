package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;

/**
 * A sensor that sends broken measurements: missing amplitude, negative amplitude, impossible
 * frequency, or a noise ratio above 1. About 30% of its readings stay valid.
 */
public final class InvalidDataEffect extends SingleSensorEffect {

    public InvalidDataEffect(String targetSensorId, Instant start, double durationSeconds) {
        super(ScenarioType.INVALID_DATA, targetSensorId, start, durationSeconds);
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        draft.label = "INVALID_DATA";
        if (random.nextDouble() < 0.3) {
            return;
        }
        switch (random.nextInt(4)) {
            case 0 -> draft.amplitude = Double.NaN;
            case 1 -> draft.amplitude = -between(random, 0.05, 0.5);
            case 2 -> draft.frequencyHz = 0;
            default -> draft.noiseLevel = between(random, 1.2, 1.8);
        }
    }
}
