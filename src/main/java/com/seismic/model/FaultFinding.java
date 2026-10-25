package com.seismic.model;

import java.time.Instant;

/** One observation of suspicious behaviour for a sensor. */
public record FaultFinding(String sensorId, FaultKind kind, String description, Instant at) {
}
