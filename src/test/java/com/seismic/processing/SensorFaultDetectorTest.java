package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.model.FaultKind;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorFaultDetectorTest {

    private static SeismicReading.Builder spike(String sensorId, int second) {
        return TestData.builder("x", sensorId, second, 0.95).frequencyHz(30).durationSeconds(0.1);
    }

    @Test
    void repeatedIsolatedAnomaliesFlagTheSensorAsFaulty() {
        PipelineRunner runner = new PipelineRunner();
        Set<Integer> spikeSeconds = Set.of(2, 9, 16);
        runner.run(0, 25, (sensor, second) -> sensor.getSensorId().equals("A1") && spikeSeconds.contains(second)
                ? spike("A1", second) : PipelineRunner.quiet(sensor, second));

        assertTrue(runner.statusChanges.stream().anyMatch(c -> c.sensorId().equals("A1") && c.newStatus() == SensorStatus.FAULTY));
        assertTrue(runner.findings.stream().anyMatch(f -> f.kind() == FaultKind.REPEATED_ISOLATED_OUTLIERS));
        assertTrue(runner.findings.stream().anyMatch(f -> f.kind() == FaultKind.PEER_DISAGREEMENT));
        assertEquals(SensorStatus.ACTIVE, runner.network.require("A2").getStatus(), "peers stay healthy");
    }

    @Test
    void oneIsolatedSpikeOnlyLowersReliability() {
        PipelineRunner runner = new PipelineRunner();
        runner.run(0, 12, (sensor, second) -> sensor.getSensorId().equals("A1") && second == 2
                ? spike("A1", second) : PipelineRunner.quiet(sensor, second));
        Sensor a1 = runner.network.require("A1");
        assertEquals(SensorStatus.ACTIVE, a1.getStatus());
        assertTrue(a1.getReliabilityScore() < 100);
    }

    @Test
    void sensorThatTriggersFirstInARealEventIsNotPenalised() {
        PipelineRunner runner = new PipelineRunner();
        // Three times, A1 triggers one second before A2, A3 and A4 (as if closest to the epicentre).
        runner.run(0, 60, (sensor, second) -> {
            int phase = second % 20;
            String id = sensor.getSensorId();
            boolean a1Shaking = id.equals("A1") && phase >= 2 && phase < 7;
            boolean othersShaking = Set.of("A2", "A3", "A4").contains(id) && phase >= 3 && phase < 7;
            return a1Shaking || othersShaking ? TestData.builder("x", id, second, 0.6) : PipelineRunner.quiet(sensor, second);
        });
        Sensor a1 = runner.network.require("A1");
        assertEquals(SensorStatus.ACTIVE, a1.getStatus());
        assertEquals(100.0, a1.getReliabilityScore(), 1e-9);
        assertTrue(runner.findings.stream().noneMatch(f -> f.sensorId().equals("A1")));
        assertEquals(3, runner.events.values().stream().filter(e -> e.getParticipatingSensorCount() == 4).count());
    }

    @Test
    void repeatedInvalidReadingsFlagFaultAndValidStreakRecovers() {
        SensorNetwork network = TestData.network();
        SensorFaultDetector detector = new SensorFaultDetector(TestData.config().reliability(), network, 40);
        Sensor a1 = network.require("A1");
        FaultUpdate last = FaultUpdate.NONE;
        for (int i = 0; i < 3; i++) {
            SeismicReading invalid = TestData.builder("I" + i, "A1", i, Double.NaN)
                    .quality(ReadingQuality.INVALID).qualityNote("amplitude missing").build();
            last = detector.onReading(a1, invalid, false);
        }
        assertEquals(SensorStatus.FAULTY, a1.getStatus());
        assertEquals(FaultKind.REPEATED_INVALID_READINGS, last.findings().get(0).kind());
        assertEquals(88.0, a1.getReliabilityScore(), 1e-9, "3 x 4 point invalid penalty");

        FaultUpdate recovery = FaultUpdate.NONE;
        for (int i = 0; i < 20; i++) {
            recovery = detector.onReading(a1, TestData.valid("V" + i, "A1", 10 + i, 0.1), false);
        }
        assertEquals(SensorStatus.ACTIVE, a1.getStatus());
        assertTrue(recovery.statusChange().orElseThrow().isRecovery());
    }

    @Test
    void suddenReliabilityDropIsReportedOnce() {
        SensorNetwork network = TestData.network();
        SensorFaultDetector detector = new SensorFaultDetector(TestData.config().reliability(), network, 40);
        Sensor b1 = network.require("B1");
        detector.onReading(b1, TestData.valid("V1", "B1", 0, 0.1), false);
        // Quiet neighbours, so each anomaly counts as a disagreement with peers (8 points each).
        network.require("B2").recordReading(TestData.valid("N1", "B2", 3, 0.1));
        network.require("B3").recordReading(TestData.valid("N2", "B3", 3, 0.1));
        for (int i = 0; i < 6; i++) {
            SeismicEvent dismissed = new SeismicEvent("E" + i, ReadingSource.SIMULATION, "B1", b1.getLocation(), TestData.at(i));
            dismissed.addCandidate(TestData.valid("S" + i, "B1", i, 0.9), "B", 0);
            detector.onIsolatedAnomaly(b1, dismissed, TestData.at(i + 1));
        }
        assertEquals(1, detector.checkReliabilityTrends(TestData.at(10)).stream()
                .filter(f -> f.kind() == FaultKind.RELIABILITY_DROP).count());
        assertTrue(detector.checkReliabilityTrends(TestData.at(11)).isEmpty(), "same drop is not reported again");
    }
}
