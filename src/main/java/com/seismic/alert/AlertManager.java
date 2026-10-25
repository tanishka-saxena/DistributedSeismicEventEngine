package com.seismic.alert;

import com.seismic.model.Alert;
import com.seismic.model.AlertSeverity;
import com.seismic.model.AlertType;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.FaultFinding;
import com.seismic.model.FaultKind;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Supplier;

/**
 * Turns engine results into operator alerts and keeps the alert history.
 *
 * <p><b>Duplicate control</b>: every alert has a deduplication key describing the condition, e.g.
 * {@code EVENT:EVL-...-0003:MAJOR} or {@code COMM:S05}. An alert is raised only when its key is new, so an
 * unchanged event or a sensor that stays offline does not produce a new alert on every cycle. A change
 * (severity escalation, or a sensor that recovers and fails again) produces a new key and a new alert.
 *
 * <p><b>Priority</b>: unacknowledged alerts are kept in a {@link PriorityQueue} ordered by severity
 * (CRITICAL first) and then age (oldest first), so the dashboard can show the most urgent alert in O(1).
 *
 * <p>Thread safety: the processing thread raises alerts while the JavaFX thread acknowledges them, so
 * every public method is {@code synchronized}. Alerts themselves are immutable.
 */
public final class AlertManager {

    static final Duration ERROR_REPEAT_INTERVAL = Duration.ofSeconds(60);
    private static final int MAX_DEDUP_KEYS = 5_000;

    private static final Comparator<Alert> PRIORITY = Comparator
            .comparing(Alert::getSeverity, Comparator.reverseOrder())
            .thenComparing(Alert::getCreatedAt);

    private final Clock clock;
    private final Supplier<String> alertIds;
    private final int maxHistory;
    /** Insertion-ordered history keyed by alert ID, bounded so memory cannot grow without limit. */
    private final LinkedHashMap<String, Alert> history;
    private final PriorityQueue<Alert> unacknowledged = new PriorityQueue<>(PRIORITY);
    /** Highest severity already alerted per event, so only escalations raise a new alert. Bounded. */
    private final LinkedHashMap<String, EventSeverity> alertedSeverity = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, EventSeverity> eldest) {
            return size() > MAX_DEDUP_KEYS;
        }
    };
    /** Dedup key -> time raised. Bounded: oldest keys are forgotten first. */
    private final LinkedHashMap<String, Instant> activeKeys = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_DEDUP_KEYS;
        }
    };

    public AlertManager(Clock clock, Supplier<String> alertIds, int maxHistory) {
        if (maxHistory < 1) {
            throw new IllegalArgumentException("maxHistory must be positive");
        }
        this.clock = clock;
        this.alertIds = alertIds;
        this.maxHistory = maxHistory;
        this.history = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Alert> eldest) {
                if (size() > AlertManager.this.maxHistory) {
                    unacknowledged.remove(eldest.getValue());
                    return true;
                }
                return false;
            }
        };
    }

    /** Raises alerts for event status/severity changes. Returns only alerts that are new. */
    public synchronized List<Alert> onEventUpdated(SeismicEvent event) {
        List<Alert> raised = new ArrayList<>();
        String id = event.getEventId();
        EventStatus status = event.getStatus();
        EventSeverity severity = event.getSeverity();
        String summary = String.format(Locale.ROOT, "%s in %s: %d sensors, confidence %.0f, peak %.2f",
                id, event.getAffectedZoneSummary(), event.getParticipatingSensorCount(),
                event.getConfidenceScore(), event.getPeakFilteredAmplitude());

        if (status == EventStatus.CONFIRMED || status == EventStatus.RESOLVED) {
            // One alert when confirmed, then one per ESCALATION; a later downgrade is not a new alert.
            EventSeverity alreadyAlerted = alertedSeverity.get(id);
            if (alreadyAlerted == null || severity.compareTo(alreadyAlerted) > 0) {
                alertedSeverity.put(id, severity);
                boolean major = severity.isAtLeast(EventSeverity.MAJOR);
                raise("EVENT:" + id + ":" + severity, id, event.getOriginSensorId(),
                        major ? AlertType.MAJOR_EVENT : AlertType.SEISMIC_EVENT,
                        AlertSeverity.fromEventSeverity(severity),
                        (alreadyAlerted == null ? severity + " seismic event confirmed: "
                                : "Event escalated " + alreadyAlerted + " -> " + severity + ": ") + summary)
                        .ifPresent(raised::add);
            }
        } else if (status == EventStatus.UNCERTAIN) {
            raise("UNCERTAIN:" + id, id, event.getOriginSensorId(), AlertType.LOW_CONFIDENCE_EVENT,
                    AlertSeverity.WARNING, "Uncertain event, needs investigation: " + summary).ifPresent(raised::add);
        } else if (status == EventStatus.DISMISSED) {
            // Dismissed candidates are not stored as events, so the alert carries no event ID.
            raise("ISOLATED:" + id, null, event.getOriginSensorId(), AlertType.ISOLATED_ANOMALY, AlertSeverity.INFO,
                    String.format(Locale.ROOT, "Isolated anomaly on %s (raw peak %.2f) not confirmed by other sensors",
                            event.getOriginSensorId(), event.getPeakAmplitude())).ifPresent(raised::add);
        }
        return raised;
    }

    public synchronized Optional<Alert> onSensorStatusChange(SensorStatusChange change) {
        String sensor = change.sensorId();
        if (change.newStatus() == SensorStatus.OFFLINE) {
            return raise("COMM:" + sensor, null, sensor, AlertType.COMMUNICATION_FAILURE, AlertSeverity.WARNING,
                    "Sensor " + sensor + " OFFLINE: " + change.reason());
        }
        if (change.newStatus() == SensorStatus.FAULTY) {
            return raise("FAULT:" + sensor, null, sensor, AlertType.SENSOR_FAULT, AlertSeverity.HIGH,
                    "Sensor " + sensor + " suspected FAULTY: " + change.reason());
        }
        if (change.isRecovery() || change.oldStatus() == SensorStatus.OFFLINE) {
            // The condition is over: forget its keys so a later failure raises a fresh alert.
            activeKeys.keySet().removeIf(k -> k.equals("COMM:" + sensor) || k.equals("FAULT:" + sensor)
                    || k.startsWith("FINDING:" + sensor + ":"));
            return Optional.of(store(Alert.create(alertIds.get(), null, sensor, clock.instant(),
                    AlertType.SENSOR_RECOVERED, AlertSeverity.INFO,
                    "Sensor " + sensor + " back to " + change.newStatus() + ": " + change.reason())));
        }
        return Optional.empty();
    }

    /**
     * Repeated-invalid and repeated-outlier findings are already reported through the FAULTY status
     * alert, and peer disagreement through the isolated-anomaly alert; only reliability drops get their own alert.
     */
    public synchronized Optional<Alert> onFaultFinding(FaultFinding finding) {
        if (finding.kind() != FaultKind.RELIABILITY_DROP) {
            return Optional.empty();
        }
        return raise("FINDING:" + finding.sensorId() + ":" + finding.kind(), null, finding.sensorId(),
                AlertType.SENSOR_FAULT, AlertSeverity.WARNING,
                "Sensor " + finding.sensorId() + ": " + finding.description());
    }

    /** Processing/database errors: the same source is reported at most once per minute. */
    public synchronized Optional<Alert> onProcessingError(String source, String message) {
        String key = "ERROR:" + source;
        Instant now = clock.instant();
        Instant last = activeKeys.get(key);
        if (last != null && Duration.between(last, now).compareTo(ERROR_REPEAT_INTERVAL) < 0) {
            return Optional.empty();
        }
        activeKeys.remove(key);
        return raise(key, null, null, AlertType.PROCESSING_ERROR, AlertSeverity.WARNING, source + ": " + message);
    }

    public synchronized Optional<Alert> acknowledge(String alertId) {
        Alert alert = history.get(alertId);
        if (alert == null || alert.isAcknowledged()) {
            return Optional.empty();
        }
        Alert acknowledged = alert.acknowledge(clock.instant());
        history.put(alertId, acknowledged);
        unacknowledged.remove(alert);
        return Optional.of(acknowledged);
    }

    public synchronized List<Alert> acknowledgeAll() {
        List<Alert> changed = new ArrayList<>();
        for (Alert alert : new ArrayList<>(unacknowledged)) {
            acknowledge(alert.getAlertId()).ifPresent(changed::add);
        }
        return changed;
    }

    /** All alerts, newest first. */
    public synchronized List<Alert> history() {
        List<Alert> list = new ArrayList<>(history.values());
        Collections.reverse(list);
        return list;
    }

    /** Unacknowledged alerts, most urgent first. */
    public synchronized List<Alert> unacknowledgedByPriority() {
        List<Alert> list = new ArrayList<>(unacknowledged);
        list.sort(PRIORITY);
        return list;
    }

    public synchronized Optional<Alert> mostUrgent() {
        return Optional.ofNullable(unacknowledged.peek());
    }

    public synchronized int unacknowledgedCount() {
        return unacknowledged.size();
    }

    /** Restores alerts loaded from the database at start-up (oldest first). */
    public synchronized void loadHistory(List<Alert> alertsOldestFirst) {
        for (Alert alert : alertsOldestFirst) {
            store(alert);
        }
    }

    /** Forgets dedup keys (on simulation reset) without deleting the history. */
    public synchronized void resetDeduplication() {
        activeKeys.clear();
        alertedSeverity.clear();
    }

    private Optional<Alert> raise(String key, String eventId, String sensorId, AlertType type,
                                  AlertSeverity severity, String description) {
        if (activeKeys.containsKey(key)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        activeKeys.put(key, now);
        return Optional.of(store(Alert.create(alertIds.get(), eventId, sensorId, now, type, severity, description)));
    }

    private Alert store(Alert alert) {
        history.put(alert.getAlertId(), alert);
        if (!alert.isAcknowledged()) {
            unacknowledged.add(alert);
        }
        return alert;
    }
}
