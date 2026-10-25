package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SignalLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDetectorTest {

    private final EventDetector detector = new EventDetector(TestData.config().thresholds());

    @Test
    void thresholdBoundariesFollowTheSpecification() {
        assertEquals(SignalLevel.NORMAL, detector.classify(0.2999));
        assertEquals(SignalLevel.MINOR, detector.classify(0.30));
        assertEquals(SignalLevel.MINOR, detector.classify(0.4999));
        assertEquals(SignalLevel.MODERATE, detector.classify(0.50));
        assertEquals(SignalLevel.MODERATE, detector.classify(0.75), "0.75 is inclusive in MODERATE");
        assertEquals(SignalLevel.HIGH, detector.classify(0.7501));
        assertEquals(SignalLevel.NORMAL, detector.classify(Double.NaN));
    }

    @Test
    void normalReadingIsNotACandidate() {
        assertTrue(detector.detect(TestData.valid("R1", "A1", 0, 0.12)).isEmpty());
    }

    @Test
    void invalidReadingIsNeverACandidate() {
        var invalid = TestData.builder("R1", "A1", 0, 0.9).quality(ReadingQuality.INVALID).build();
        assertTrue(detector.detect(invalid).isEmpty());
    }

    @Test
    void spikeIsACandidateButNotSustained() {
        var spike = TestData.builder("R1", "A1", 0, 1.0).quality(ReadingQuality.VALID).filteredAmplitude(0.2).build();
        CandidateDetection candidate = detector.detect(spike).orElseThrow();
        assertEquals(SignalLevel.HIGH, candidate.level());
        assertEquals(SignalLevel.NORMAL, candidate.sustainedLevel());
        assertFalse(candidate.isSustained());
    }
}
