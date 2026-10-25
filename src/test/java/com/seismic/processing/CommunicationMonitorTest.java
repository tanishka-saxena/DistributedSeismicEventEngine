package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.config.ProcessingConfig.CommunicationSettings;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time is passed in explicitly, so timeouts are tested without waiting. */
class CommunicationMonitorTest {

    private SensorNetwork network;
    private Sensor a1;
    private CommunicationMonitor monitor;
    private final AtomicInteger penalties = new AtomicInteger();

    @BeforeEach
    void setUp() {
        network = TestData.network();
        a1 = network.require("A1");
        monitor = new CommunicationMonitor(new CommunicationSettings(5.0, 3), (sensor, at) -> penalties.incrementAndGet());
        monitor.startTracking(TestData.T0);
        network.sensors().forEach(s -> monitor.recordHeartbeat(s, TestData.T0));
    }

    @Test
    void sensorGoesOfflineOnlyAfterTheTimeout() {
        assertTrue(monitor.checkTimeouts(List.of(a1), TestData.at(5.0)).isEmpty(), "exactly at the timeout is still online");
        List<SensorStatusChange> changes = monitor.checkTimeouts(List.of(a1), TestData.at(5.5));
        assertEquals(1, changes.size());
        assertEquals(SensorStatus.OFFLINE, a1.getStatus());
        assertEquals(1, penalties.get());
        assertTrue(monitor.checkTimeouts(List.of(a1), TestData.at(8)).isEmpty(), "no repeated change or penalty");
        assertEquals(1, penalties.get());
    }

    @Test
    void sensorThatNeverReportsTimesOutFromTrackingStart() {
        CommunicationMonitor fresh = new CommunicationMonitor(new CommunicationSettings(5.0, 3), (s, at) -> { });
        fresh.startTracking(TestData.T0);
        Sensor b1 = network.require("B1");
        assertEquals(1, fresh.checkTimeouts(List.of(b1), TestData.at(6)).size());
    }

    @Test
    void offlineSensorRecoversAfterConsecutiveReadings() {
        monitor.checkTimeouts(List.of(a1), TestData.at(10));
        assertTrue(monitor.recordHeartbeat(a1, TestData.at(20)).isEmpty());
        assertTrue(monitor.recordHeartbeat(a1, TestData.at(21)).isEmpty());
        SensorStatusChange change = monitor.recordHeartbeat(a1, TestData.at(22)).orElseThrow();
        assertEquals(SensorStatus.ACTIVE, change.newStatus());
        assertEquals(SensorStatus.ACTIVE, a1.getStatus());
    }

    @Test
    void irregularReadingsDoNotCountTowardsRecovery() {
        monitor.checkTimeouts(List.of(a1), TestData.at(10));
        for (int second = 20; second <= 50; second += 6) {
            assertTrue(monitor.recordHeartbeat(a1, TestData.at(second)).isEmpty());
        }
        assertEquals(SensorStatus.OFFLINE, a1.getStatus());
    }

    @Test
    void recoveryRestoresThePreviousStatus() {
        a1.setStatus(SensorStatus.FAULTY);
        monitor.checkTimeouts(List.of(a1), TestData.at(10));
        monitor.recordHeartbeat(a1, TestData.at(11));
        monitor.recordHeartbeat(a1, TestData.at(12));
        assertEquals(SensorStatus.FAULTY, monitor.recordHeartbeat(a1, TestData.at(13)).orElseThrow().newStatus());
    }

    @Test
    void maintenanceSensorsAreNotMarkedOffline() {
        a1.setStatus(SensorStatus.MAINTENANCE);
        assertTrue(monitor.checkTimeouts(List.of(a1), TestData.at(60)).isEmpty());
    }
}
