package com.seismic.model;

import java.time.Instant;
import java.util.Objects;

/**
 * An alert shown to the operator. Immutable: acknowledging returns a new instance, which makes
 * alerts safe to pass between the processing thread, the database thread and the JavaFX thread.
 */
public final class Alert {

    private final String alertId;
    private final String eventId;
    private final String sensorId;
    private final Instant createdAt;
    private final AlertType type;
    private final AlertSeverity severity;
    private final String description;
    private final boolean acknowledged;
    private final Instant acknowledgedAt;

    public Alert(String alertId, String eventId, String sensorId, Instant createdAt, AlertType type,
                 AlertSeverity severity, String description, boolean acknowledged, Instant acknowledgedAt) {
        if (alertId == null || alertId.isBlank()) {
            throw new IllegalArgumentException("Alert ID must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Alert description must not be blank");
        }
        if (acknowledged && acknowledgedAt == null) {
            throw new IllegalArgumentException("An acknowledged alert needs an acknowledgement time");
        }
        this.alertId = alertId;
        this.eventId = eventId;
        this.sensorId = sensorId;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.type = Objects.requireNonNull(type, "type");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.description = description;
        this.acknowledged = acknowledged;
        this.acknowledgedAt = acknowledgedAt;
    }

    public static Alert create(String alertId, String eventId, String sensorId, Instant createdAt,
                               AlertType type, AlertSeverity severity, String description) {
        return new Alert(alertId, eventId, sensorId, createdAt, type, severity, description, false, null);
    }

    public Alert acknowledge(Instant at) {
        if (acknowledged) {
            return this;
        }
        return new Alert(alertId, eventId, sensorId, createdAt, type, severity, description, true, at);
    }

    public String getAlertId() { return alertId; }
    /** Related event ID, or null if the alert is not about a persisted event. */
    public String getEventId() { return eventId; }
    /** Related sensor ID, or null. */
    public String getSensorId() { return sensorId; }
    public Instant getCreatedAt() { return createdAt; }
    public AlertType getType() { return type; }
    public AlertSeverity getSeverity() { return severity; }
    public String getDescription() { return description; }
    public boolean isAcknowledged() { return acknowledged; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Alert other && alertId.equals(other.alertId);
    }

    @Override
    public int hashCode() {
        return alertId.hashCode();
    }

    @Override
    public String toString() {
        return alertId + " " + severity + " " + type + ": " + description;
    }
}
