package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.config.ProcessingConfig;
import com.seismic.config.ProcessingConfig.ConfidenceSettings;
import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.GeoPoint;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceCalculatorTest {

    private final ProcessingConfig config = TestData.config();
    private final ConfidenceCalculator calculator =
            new ConfidenceCalculator(config.confidence(), config.correlation(), config.thresholds());

    /** Builds an event whose participants trigger at the given seconds with the given amplitude. */
    private static SeismicEvent event(SensorNetwork network, String[] sensors, double[] seconds, double amplitude) {
        Sensor origin = network.require(sensors[0]);
        SeismicEvent event = new SeismicEvent("E1", ReadingSource.SIMULATION, origin.getSensorId(),
                origin.getLocation(), TestData.at(seconds[0]));
        for (int i = 0; i < sensors.length; i++) {
            Sensor sensor = network.require(sensors[i]);
            event.addCandidate(TestData.valid("R" + i, sensors[i], seconds[i], amplitude), sensor.getZone(),
                    origin.getLocation().distanceKm(sensor.getLocation()));
        }
        return event;
    }

    @Test
    void singleSensorHasNoCorroborationAndLowConfidence() {
        SensorNetwork network = TestData.network();
        ConfidenceBreakdown c = calculator.calculate(event(network, new String[]{"A1"}, new double[]{0}, 0.95), network);
        assertEquals(0.0, c.temporalConsistency().orElseThrow(), 1e-9);
        assertEquals(0.0, c.spatialConsistency().orElseThrow(), 1e-9);
        assertTrue(c.score() < 50, "score " + c.score());
        assertEquals(35.0, c.score(), 0.05, "worked example 2 in docs/algorithms.md");
    }

    @Test
    void correlatedNearbySensorsGiveHighConfidence() {
        SensorNetwork network = TestData.network();
        ConfidenceBreakdown c = calculator.calculate(
                event(network, new String[]{"A1", "A2", "A3"}, new double[]{0, 0.4, 0.4}, 0.6), network);
        assertEquals(1.0, c.sensorAgreement(), 1e-9);
        assertEquals(1.0, c.spatialConsistency().orElseThrow(), 1e-9, "A4 is farther than every participant");
        assertTrue(c.score() >= 80, "score " + c.score());
        assertEquals(97.2, c.score(), 0.05, "worked example 1 in docs/algorithms.md");
    }

    @Test
    void silentSensorInsideTheAffectedAreaLowersSpatialConsistency() {
        SensorNetwork network = TestData.network();
        // A4 participates (4.2 km away) while A3 (2 km away) stays silent.
        ConfidenceBreakdown c = calculator.calculate(
                event(network, new String[]{"A1", "A2", "A4"}, new double[]{0, 0.4, 0.7}, 0.6), network);
        assertEquals(0.75, c.spatialConsistency().orElseThrow(), 1e-9);
    }

    @Test
    void missingCoordinatesMakeFactorsUnavailableInsteadOfAgreeing() {
        SensorNetwork network = new SensorNetwork(List.of(
                new Sensor("X1", "X1", "Z", GeoPoint.UNKNOWN),
                new Sensor("X2", "X2", "Z", GeoPoint.UNKNOWN),
                new Sensor("X3", "X3", "Z", GeoPoint.UNKNOWN)));
        ConfidenceBreakdown c = calculator.calculate(
                event(network, new String[]{"X1", "X2", "X3"}, new double[]{0, 1, 1}, 0.6), network);
        assertTrue(c.temporalConsistency().isEmpty());
        assertTrue(c.spatialConsistency().isEmpty());
        double expected = 100 * (0.30 * c.sensorAgreement() + 0.25 * c.signalScore()) / 0.55;
        assertEquals(expected, c.score(), 1e-9, "renormalised over the available factors only");
    }

    @Test
    void confidenceAlwaysStaysBetween0And100() {
        SensorNetwork network = TestData.network();
        List<String> ids = new ArrayList<>(network.sensorIds());
        Random random = new Random(42);
        for (int trial = 0; trial < 500; trial++) {
            Collections.shuffle(ids, random);
            int count = 1 + random.nextInt(ids.size());
            String[] sensors = ids.subList(0, count).toArray(new String[0]);
            double[] seconds = new double[count];
            for (int i = 0; i < count; i++) {
                seconds[i] = random.nextDouble() * 30;
            }
            seconds[0] = 0;
            double score = calculator.calculate(event(network, sensors, seconds, random.nextDouble() * 2), network).score();
            assertTrue(score >= 0 && score <= 100, "score " + score);
        }
    }

    @Test
    void weightsMustSumToOne() {
        assertThrows(IllegalArgumentException.class, () -> new ConfidenceSettings(0.5, 0.5, 0.5, 0.5, 60));
    }
}
