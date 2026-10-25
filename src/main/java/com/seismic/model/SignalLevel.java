package com.seismic.model;

/**
 * Threshold level of one synthetic amplitude value. These levels describe a single reading only;
 * they mark candidates, not confirmed events, and are not real magnitude scales.
 */
public enum SignalLevel {
    NORMAL, MINOR, MODERATE, HIGH;

    public boolean isCandidate() {
        return this != NORMAL;
    }

    /** Severity implied by the signal level alone, before any multi-sensor rules are applied. */
    public EventSeverity toBaseSeverity() {
        return switch (this) {
            case NORMAL -> EventSeverity.NORMAL;
            case MINOR -> EventSeverity.MINOR;
            case MODERATE -> EventSeverity.MODERATE;
            case HIGH -> EventSeverity.MAJOR;
        };
    }
}
