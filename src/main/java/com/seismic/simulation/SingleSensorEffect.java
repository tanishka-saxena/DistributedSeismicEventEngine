package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Objects;

/** An effect that targets exactly one sensor while its peers keep behaving normally. */
public abstract class SingleSensorEffect extends TimedEffect {

    protected final String targetSensorId;

    protected SingleSensorEffect(ScenarioType type, String targetSensorId, Instant start, double durationSeconds) {
        super(type, start, durationSeconds);
        this.targetSensorId = Objects.requireNonNull(targetSensorId);
    }

    @Override
    public boolean appliesTo(Sensor sensor, Instant time) {
        return sensor.getSensorId().equals(targetSensorId) && isWithinWindow(time);
    }

    public String getTargetSensorId() {
        return targetSensorId;
    }

    @Override
    public String describe() {
        return type().getDisplayName() + " on " + targetSensorId;
    }
}
