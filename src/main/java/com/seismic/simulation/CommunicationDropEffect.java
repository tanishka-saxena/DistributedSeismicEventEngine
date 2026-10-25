package com.seismic.simulation;

import com.seismic.model.Sensor;

import java.time.Instant;
import java.util.Random;

/**
 * Simulates a broken link: the sensor keeps measuring but its readings never reach the engine.
 * This is different from a faulty sensor, which does report, only with bad values.
 */
public final class CommunicationDropEffect extends SingleSensorEffect {

    public CommunicationDropEffect(String targetSensorId, Instant start, double durationSeconds) {
        super(ScenarioType.COMMUNICATION_FAILURE, targetSensorId, start, durationSeconds);
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        draft.dropped = true;
    }
}
