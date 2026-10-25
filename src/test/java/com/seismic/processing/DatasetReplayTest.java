package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.dataset.CsvDatasetLoader;
import com.seismic.dataset.DatasetLoadResult;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;
import com.seismic.simulation.SensorNetworkFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of the whole processing pipeline on the bundled synthetic dataset. Every scripted
 * scenario of DemoDatasetGenerator must produce its expected outcome.
 */
class DatasetReplayTest {

    private static DatasetAnalyzer.AnalysisResult result;

    @BeforeAll
    static void replayBundledDataset() throws Exception {
        SensorNetwork network = SensorNetworkFactory.load(Path.of("data", "sensors.csv"));
        CsvDatasetLoader loader = new CsvDatasetLoader(TestData.validator(), TestData.CLOCK);
        DatasetLoadResult loaded = loader.load(Path.of("data", "seismic_sensor_readings.csv"),
                DatasetSourceType.BUNDLED, network);
        result = new DatasetAnalyzer(TestData.config(), TestData.validator())
                .analyze(loaded.readings(), network, TestData.ids("EV"));
        result.events().forEach(e -> System.out.println(e + " zone=" + e.getAffectedZoneSummary()
                + " start=" + e.getStartTime()));
        result.dismissedCandidates().forEach(e -> System.out.println("dismissed " + e));
        result.statusChanges().forEach(c -> System.out.println("status " + c));
    }

    @Test
    void minorEventInNorthZoneIsConfirmedAsMinor() {
        SeismicEvent north = findEventStartingIn("North", 15, 25);
        assertEquals(EventStatus.RESOLVED, north.getStatus(), "confirmed events end as RESOLVED");
        assertEquals(EventSeverity.MINOR, north.getSeverity());
        assertTrue(north.getParticipatingSensorCount() >= 3);
    }

    @Test
    void majorEventInCentralZoneIsConfirmedAsMajorOrCritical() {
        SeismicEvent central = findEventStartingIn("Central", 38, 45);
        assertEquals(EventStatus.RESOLVED, central.getStatus());
        assertTrue(central.getSeverity().isAtLeast(EventSeverity.MAJOR), "severity was " + central.getSeverity());
        assertTrue(central.getParticipatingSensorCount() >= 9, "outer zones should join via propagation");
        assertTrue(central.getConfidenceScore() >= 60);
    }

    @Test
    void weakTwoSensorEventIsUncertain() {
        SeismicEvent weak = findEventStartingIn("East", 85, 95);
        assertEquals(EventStatus.UNCERTAIN, weak.getStatus());
        assertEquals(Set.of("S07", "S09"), weak.getParticipatingSensorIds());
    }

    @Test
    void isolatedSpikeIsDismissedNotConfirmed() {
        assertTrue(result.dismissedCandidates().stream().anyMatch(e -> e.getOriginSensorId().equals("S08")
                && secondsAfterStart(e) >= 27 && secondsAfterStart(e) <= 30));
        assertTrue(result.events().stream().noneMatch(e -> e.getOriginSensorId().equals("S08")
                && e.getParticipatingSensorCount() == 1));
    }

    @Test
    void repeatedFaultySensorIsFlaggedFaulty() {
        assertTrue(changed("S11", SensorStatus.FAULTY), "S11 should become FAULTY");
    }

    @Test
    void sensorWithInvalidDataIsFlaggedFaulty() {
        assertTrue(changed("S13", SensorStatus.FAULTY), "S13 should become FAULTY");
    }

    @Test
    void silentSensorGoesOfflineAndRecovers() {
        List<SensorStatusChange> s05 = result.statusChanges().stream().filter(c -> c.sensorId().equals("S05")).toList();
        assertTrue(s05.stream().anyMatch(c -> c.newStatus() == SensorStatus.OFFLINE));
        assertTrue(s05.stream().anyMatch(c -> c.oldStatus() == SensorStatus.OFFLINE && c.newStatus() == SensorStatus.ACTIVE));
    }

    @Test
    void noisySensorIsNeitherOfflineNorFaulty() {
        assertFalse(result.statusChanges().stream().anyMatch(c -> c.sensorId().equals("S14")));
    }

    @Test
    void faultySensorIsNotOfflineAndOfflineSensorIsNotFaulty() {
        assertFalse(changed("S11", SensorStatus.OFFLINE));
        assertFalse(changed("S05", SensorStatus.FAULTY));
    }

    @Test
    void noFalseEventsFromFaultyOrNoisySensors() {
        // Every non-dismissed event must involve at least two sensors.
        assertTrue(result.events().stream().allMatch(e -> e.getParticipatingSensorCount() >= 2));
    }

    private static boolean changed(String sensorId, SensorStatus status) {
        return result.statusChanges().stream().anyMatch(c -> c.sensorId().equals(sensorId) && c.newStatus() == status);
    }

    private static SeismicEvent findEventStartingIn(String zone, double fromSecond, double toSecond) {
        return result.events().stream()
                .filter(e -> e.getAffectedZoneSummary().startsWith(zone))
                .filter(e -> secondsAfterStart(e) >= fromSecond && secondsAfterStart(e) <= toSecond)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No event in " + zone + " between t=" + fromSecond + " and " + toSecond));
    }

    private static double secondsAfterStart(SeismicEvent e) {
        return (e.getStartTime().toEpochMilli() - com.seismic.dataset.DemoDatasetGenerator.START.toEpochMilli()) / 1000.0;
    }
}
