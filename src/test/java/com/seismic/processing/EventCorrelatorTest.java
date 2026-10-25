package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import com.seismic.model.SignalLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCorrelatorTest {

    private SensorNetwork network;
    private EventCorrelator correlator;

    @BeforeEach
    void setUp() {
        network = TestData.network();
        correlator = new EventCorrelator(TestData.config().correlation(), network, TestData.ids("EV"),
                ReadingSource.SIMULATION);
    }

    private static CandidateDetection candidate(SeismicReading reading) {
        return new CandidateDetection(reading, SignalLevel.MODERATE, SignalLevel.MODERATE);
    }

    private EventCorrelator.Outcome accept(String id, String sensor, double second, double amplitude) {
        return correlator.accept(candidate(TestData.valid(id, sensor, second, amplitude)));
    }

    @Test
    void nearbyCandidatesWithinTheWindowFormOneEvent() {
        var first = accept("R1", "A1", 0, 0.6);
        var second = accept("R2", "A2", 1, 0.6);
        var third = accept("R3", "A3", 1.5, 0.6);
        assertTrue(first.newEvent());
        assertFalse(second.newEvent());
        assertEquals(first.event(), second.event());
        assertEquals(first.event(), third.event());
        assertEquals(3, first.event().getParticipatingSensorCount());
    }

    @Test
    void candidatesOutsideTheCorrelationWindowAreNotGrouped() {
        var first = accept("R1", "A1", 0, 0.6);
        var late = accept("R2", "A2", 20, 0.6);
        assertTrue(late.newEvent());
        assertNotEquals(first.event().getEventId(), late.event().getEventId());
    }

    @Test
    void simultaneousCandidatesFarApartAreSeparateEvents() {
        var zoneA = accept("R1", "A1", 0, 0.6);
        var zoneB = accept("R2", "B1", 0, 0.6);
        assertNotEquals(zoneA.event().getEventId(), zoneB.event().getEventId(), "100 km apart is beyond the 40 km radius");
    }

    @Test
    void inconsistentFrequencyStartsANewEvent() {
        var seismic = accept("R1", "A1", 0, 0.6);
        SeismicReading electrical = TestData.builder("R2", "A2", 1, 0.6).frequencyHz(25)
                .quality(ReadingQuality.VALID).filteredAmplitude(0.6).build();
        var other = correlator.accept(candidate(electrical));
        assertNotEquals(seismic.event().getEventId(), other.event().getEventId());
    }

    @Test
    void veryShortBurstDoesNotJoinAnEvent() {
        var seismic = accept("R1", "A1", 0, 0.6);
        SeismicReading knock = TestData.builder("R2", "A2", 1, 0.9).durationSeconds(0.1)
                .quality(ReadingQuality.VALID).filteredAmplitude(0.9).build();
        assertNotEquals(seismic.event().getEventId(), correlator.accept(candidate(knock)).event().getEventId());
    }

    @Test
    void suspiciousReadingsAndInactiveSensorsAreNotCorrelated() {
        SeismicReading noisy = TestData.builder("R1", "A1", 0, 0.6).noiseLevel(0.8)
                .quality(ReadingQuality.SUSPICIOUS).filteredAmplitude(0.6).build();
        assertFalse(correlator.accept(candidate(noisy)).correlated());
        network.require("A2").setStatus(SensorStatus.OFFLINE);
        assertFalse(accept("R2", "A2", 0, 0.6).correlated());
    }

    @Test
    void singleSensorEventClosesWhenTheWindowPassesWithoutSupport() {
        accept("R1", "A1", 0, 0.9);
        assertTrue(correlator.closeExpired(TestData.at(4)).isEmpty());
        assertEquals(1, correlator.closeExpired(TestData.at(5.5)).size());
        assertTrue(correlator.getOpenEvents().isEmpty());
    }

    @Test
    void multiSensorEventStaysOpenWhileActivityContinues() {
        accept("R1", "A1", 0, 0.6);
        accept("R2", "A2", 1, 0.6);
        accept("R3", "A1", 4, 0.6);
        assertTrue(correlator.closeExpired(TestData.at(8)).isEmpty(), "last activity at t=4, window 5 s");
        assertEquals(1, correlator.closeExpired(TestData.at(9.5)).size());
    }
}
