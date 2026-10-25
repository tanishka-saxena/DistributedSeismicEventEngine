package com.seismic.model;

import java.time.Instant;
import java.util.Objects;

/** Records that a sensor moved from one status to another, and why. */
public record SensorStatusChange(String sensorId, SensorStatus oldStatus, SensorStatus newStatus,
                                 String reason, Instant at) {
    public SensorStatusChange {
        Objects.requireNonNull(sensorId, "sensorId");
        Objects.requireNonNull(newStatus, "newStatus");
        Objects.requireNonNull(at, "at");
    }

    public boolean isRecovery() {
        return newStatus == SensorStatus.ACTIVE && oldStatus != SensorStatus.ACTIVE;
    }
}
