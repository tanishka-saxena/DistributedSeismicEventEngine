package com.seismic.alert;

import com.seismic.TestData;
import com.seismic.model.Alert;
import com.seismic.model.AlertSeverity;
import com.seismic.model.AlertType;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertManagerTest {

    /** A clock the test can move forward, so rate limits are tested without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = TestData.T0;

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final AlertManager manager = new AlertManager(clock, TestData.ids("AL"), 100);

    private static SeismicEvent event(EventStatus status, EventSeverity severity) {
        SeismicEvent event = new SeismicEvent("EV1", ReadingSource.SIMULATION, "A1", TestData.BASE, TestData.T0);
        event.addCandidate(TestData.valid("R1", "A1", 0, 0.6), "A", 0);
        event.setStatus(status);
        event.setSeverity(severity);
        return event;
    }

    private static SensorStatusChange change(String sensor, SensorStatus from, SensorStatus to) {
        return new SensorStatusChange(sensor, from, to, "test", TestData.T0);
    }

    @Test
    void unchangedEventDoesNotRaiseDuplicateAlerts() {
        assertEquals(1, manager.onEventUpdated(event(EventStatus.CONFIRMED, EventSeverity.MODERATE)).size());
        assertEquals(0, manager.onEventUpdated(event(EventStatus.CONFIRMED, EventSeverity.MODERATE)).size());
        assertEquals(0, manager.onEventUpdated(event(EventStatus.RESOLVED, EventSeverity.MODERATE)).size(),
                "resolving with the same severity is not a new alert");
        assertEquals(1, manager.history().size());
    }

    @Test
    void severityEscalationRaisesANewMajorEventAlert() {
        manager.onEventUpdated(event(EventStatus.CONFIRMED, EventSeverity.MODERATE));
        List<Alert> escalated = manager.onEventUpdated(event(EventStatus.CONFIRMED, EventSeverity.MAJOR));
        assertEquals(1, escalated.size());
        assertEquals(AlertType.MAJOR_EVENT, escalated.get(0).getType());
        assertEquals(AlertSeverity.HIGH, escalated.get(0).getSeverity());
    }

    @Test
    void severityDowngradeDoesNotRaiseAnAlert() {
        manager.onEventUpdated(event(EventStatus.CONFIRMED, EventSeverity.CRITICAL));
        assertTrue(manager.onEventUpdated(event(EventStatus.RESOLVED, EventSeverity.MAJOR)).isEmpty());
        assertEquals(1, manager.history().size());
    }

    @Test
    void candidatesRaiseNoAlertUncertainAndDismissedDo() {
        assertTrue(manager.onEventUpdated(event(EventStatus.CANDIDATE, EventSeverity.MINOR)).isEmpty());
        assertEquals(AlertType.LOW_CONFIDENCE_EVENT,
                manager.onEventUpdated(event(EventStatus.UNCERTAIN, EventSeverity.MINOR)).get(0).getType());
        Alert isolated = manager.onEventUpdated(event(EventStatus.DISMISSED, EventSeverity.MINOR)).get(0);
        assertEquals(AlertType.ISOLATED_ANOMALY, isolated.getType());
        assertEquals(null, isolated.getEventId(), "dismissed candidates are not persisted events");
    }

    @Test
    void offlineSensorIsAlertedOnceUntilItRecovers() {
        assertTrue(manager.onSensorStatusChange(change("A1", SensorStatus.ACTIVE, SensorStatus.OFFLINE)).isPresent());
        assertTrue(manager.onSensorStatusChange(change("A1", SensorStatus.ACTIVE, SensorStatus.OFFLINE)).isEmpty());
        Alert recovered = manager.onSensorStatusChange(change("A1", SensorStatus.OFFLINE, SensorStatus.ACTIVE)).orElseThrow();
        assertEquals(AlertType.SENSOR_RECOVERED, recovered.getType());
        assertTrue(manager.onSensorStatusChange(change("A1", SensorStatus.ACTIVE, SensorStatus.OFFLINE)).isPresent(),
                "a new failure after recovery is a new alert");
    }

    @Test
    void acknowledgingRemovesTheAlertFromTheUnacknowledgedQueue() {
        Alert alert = manager.onSensorStatusChange(change("A1", SensorStatus.ACTIVE, SensorStatus.FAULTY)).orElseThrow();
        assertEquals(1, manager.unacknowledgedCount());
        clock.advance(Duration.ofSeconds(5));
        Alert acknowledged = manager.acknowledge(alert.getAlertId()).orElseThrow();
        assertTrue(acknowledged.isAcknowledged());
        assertEquals(clock.instant(), acknowledged.getAcknowledgedAt());
        assertEquals(0, manager.unacknowledgedCount());
        assertTrue(manager.acknowledge(alert.getAlertId()).isEmpty(), "already acknowledged");
        assertTrue(manager.history().get(0).isAcknowledged());
    }

    @Test
    void mostUrgentAlertComesFirst() {
        manager.onEventUpdated(event(EventStatus.DISMISSED, EventSeverity.MINOR));          // INFO
        clock.advance(Duration.ofSeconds(1));
        SeismicEvent critical = event(EventStatus.CONFIRMED, EventSeverity.CRITICAL);       // CRITICAL
        manager.onEventUpdated(critical);
        clock.advance(Duration.ofSeconds(1));
        manager.onSensorStatusChange(change("B1", SensorStatus.ACTIVE, SensorStatus.OFFLINE)); // WARNING
        assertEquals(AlertSeverity.CRITICAL, manager.mostUrgent().orElseThrow().getSeverity());
        assertEquals(List.of(AlertSeverity.CRITICAL, AlertSeverity.WARNING, AlertSeverity.INFO),
                manager.unacknowledgedByPriority().stream().map(Alert::getSeverity).toList());
    }

    @Test
    void repeatedProcessingErrorsAreRateLimited() {
        assertTrue(manager.onProcessingError("Database", "connection lost").isPresent());
        assertTrue(manager.onProcessingError("Database", "connection lost").isEmpty());
        clock.advance(Duration.ofSeconds(61));
        assertTrue(manager.onProcessingError("Database", "connection lost").isPresent());
    }

    @Test
    void historyIsBounded() {
        AlertManager small = new AlertManager(clock, TestData.ids("AL"), 3);
        for (int i = 0; i < 5; i++) {
            small.onSensorStatusChange(change("S" + i, SensorStatus.ACTIVE, SensorStatus.OFFLINE));
        }
        assertEquals(3, small.history().size());
        assertEquals(3, small.unacknowledgedCount());
    }
}
