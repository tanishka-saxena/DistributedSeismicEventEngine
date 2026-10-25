package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One short, strong, high-frequency spike on one sensor (for example a knock on the sensor housing).
 * Applied to exactly one reading; the AtomicBoolean makes "only once" safe across sampling threads.
 */
public final class IsolatedSpikeEffect extends SingleSensorEffect {

    private final AtomicBoolean used = new AtomicBoolean(false);

    public IsolatedSpikeEffect(String targetSensorId, Instant start, double windowSeconds) {
        super(ScenarioType.ISOLATED_SPIKE, targetSensorId, start, windowSeconds);
    }

    @Override
    public boolean appliesTo(Sensor sensor, Instant time) {
        return !used.get() && super.appliesTo(sensor, time);
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        if (!used.compareAndSet(false, true)) {
            return;
        }
        draft.amplitude = between(random, 0.85, 1.10);
        draft.frequencyHz = between(random, 25, 40);
        draft.durationSeconds = between(random, 0.05, 0.20);
        draft.label = "ISOLATED_SPIKE";
    }

    @Override
    public boolean isExpired(Instant time) {
        return used.get() || super.isExpired(time);
    }
}
