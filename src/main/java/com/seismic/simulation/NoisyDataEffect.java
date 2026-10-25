package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;

/** A sensor with a high noise floor: noisy amplitude, random frequency, noise level above 0.6. */
public final class NoisyDataEffect extends SingleSensorEffect {

    public NoisyDataEffect(String targetSensorId, Instant start, double durationSeconds) {
        super(ScenarioType.NOISY_DATA, targetSensorId, start, durationSeconds);
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        draft.amplitude = Math.min(1.9, draft.amplitude + Math.abs(random.nextGaussian() * 0.16));
        draft.frequencyHz = between(random, 0.5, 45);
        draft.noiseLevel = between(random, 0.62, 0.90);
        draft.label = "NOISY_DATA";
    }
}
