package com.seismic.model;

/** Urgency of an alert. Declared from lowest to highest so the declaration order equals urgency. */
public enum AlertSeverity {
    INFO, WARNING, HIGH, CRITICAL;

    /** Maps an event severity to the alert urgency used for that event. */
    public static AlertSeverity fromEventSeverity(EventSeverity severity) {
        return switch (severity) {
            case NORMAL, MINOR -> INFO;
            case MODERATE -> WARNING;
            case MAJOR -> HIGH;
            case CRITICAL -> CRITICAL;
        };
    }
}
