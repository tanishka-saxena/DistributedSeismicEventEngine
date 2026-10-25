package com.seismic.simulation;

import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Random;

/**
 * Produces one synthetic reading for one sensor: a quiet background signal, then every scenario
 * effect that applies to the sensor at that moment. The class is stateless; all randomness comes
 * from the {@link Random} passed in, so callers control reproducibility (one seeded Random per sensor).
 * <p>
 * Used by both the live {@link SensorSimulator} and the offline {@code DemoDatasetGenerator},
 * so the bundled dataset and the live simulation share the same signal model.
 */
public final class ReadingGenerator {

    /**
     * @return the reading, or empty if a communication-failure effect stopped it from being delivered
     */
    public Optional<SeismicReading> generate(Sensor sensor, Instant time, String readingId, Random random,
                                             Collection<? extends ScenarioEffect> effects, ReadingSource source) {
        ReadingDraft draft = background(random);
        for (ScenarioEffect effect : effects) {
            if (effect.appliesTo(sensor, time)) {
                effect.apply(draft, sensor, time, random);
            }
        }
        if (draft.dropped) {
            return Optional.empty();
        }
        return Optional.of(SeismicReading.builder()
                .readingId(readingId)
                .sensorId(sensor.getSensorId())
                .timestamp(time)
                .amplitude(round(draft.amplitude))
                .frequencyHz(round(draft.frequencyHz))
                .durationSeconds(round(draft.durationSeconds))
                .noiseLevel(round(draft.noiseLevel))
                .source(source)
                .scenarioLabel(draft.label)
                .build());
    }

    /** Normal background micro-vibration: amplitude around 0.07-0.16, well below the 0.30 minor threshold. */
    private ReadingDraft background(Random random) {
        double amplitude = 0.07 + Math.abs(random.nextGaussian()) * 0.03;
        double frequency = 1.0 + random.nextDouble() * 9.0;
        double duration = 0.5 + random.nextDouble() * 1.5;
        double noise = 0.04 + random.nextDouble() * 0.11;
        return new ReadingDraft(amplitude, frequency, duration, noise);
    }

    /** Four decimals keeps CSV output readable; NaN stays NaN (a missing value). */
    private static double round(double value) {
        return Double.isNaN(value) ? value : Math.round(value * 10_000.0) / 10_000.0;
    }
}
