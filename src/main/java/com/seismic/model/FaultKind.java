package com.seismic.model;

/** Kinds of suspicious sensor behaviour reported by SensorFaultDetector. */
public enum FaultKind {
    REPEATED_INVALID_READINGS,
    REPEATED_ISOLATED_OUTLIERS,
    PEER_DISAGREEMENT,
    RELIABILITY_DROP
}
