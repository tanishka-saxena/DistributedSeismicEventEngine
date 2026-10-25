package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.config.ProcessingConfig;
import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.EventSeverity;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeverityClassifierTest {

    private final ProcessingConfig config = TestData.config();
    private final SeverityClassifier classifier = new SeverityClassifier(config.severity(),
            new EventDetector(config.thresholds()), 3, 60);

    /**
     * An event with {@code sensors} participants spread evenly up to {@code radiusKm} from the origin,
     * lasting {@code durationSeconds}, with the given peak filtered amplitude and confidence.
     */
    private static SeismicEvent event(int sensors, double peak, double durationSeconds, double radiusKm, double confidence) {
        SeismicEvent event = new SeismicEvent("E1", ReadingSource.SIMULATION, "S0", TestData.BASE, TestData.T0);
        for (int i = 0; i < sensors; i++) {
            double distance = sensors == 1 ? 0 : radiusKm * i / (sensors - 1);
            event.addCandidate(TestData.valid("R" + i, "S" + i, 0, peak), "Z", distance);
        }
        event.addCandidate(TestData.valid("END", "S0", durationSeconds, peak), "Z", 0);
        event.setConfidence(new ConfidenceBreakdown(1, OptionalDouble.of(1), OptionalDouble.of(1), 1, confidence));
        return event;
    }

    @Test
    void baseSeverityComesFromPeakFilteredAmplitude() {
        assertEquals(EventSeverity.NORMAL, classifier.classify(event(3, 0.2, 3, 5, 90)).severity());
        assertEquals(EventSeverity.MINOR, classifier.classify(event(3, 0.35, 3, 5, 90)).severity());
        assertEquals(EventSeverity.MODERATE, classifier.classify(event(3, 0.6, 3, 5, 90)).severity());
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(3, 0.9, 3, 5, 90)).severity());
    }

    @Test
    void criticalNeedsStrengthSensorsDurationAndConfidence() {
        assertEquals(EventSeverity.CRITICAL, classifier.classify(event(6, 1.1, 6, 30, 90)).severity());
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(5, 1.1, 6, 30, 90)).severity(), "too few sensors");
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(6, 1.1, 3, 30, 90)).severity(), "too short");
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(6, 0.95, 6, 30, 90)).severity(), "too weak");
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(6, 1.1, 6, 30, 70)).severity(), "confidence too low");
    }

    @Test
    void widespreadEventIsRaisedOneLevel() {
        assertEquals(EventSeverity.MAJOR, classifier.classify(event(6, 0.6, 6, 25, 90)).severity());
        assertEquals(EventSeverity.MODERATE, classifier.classify(event(6, 0.6, 6, 10, 90)).severity(), "area too small");
    }

    @Test
    void singleSensorIsCappedAtMinorEvenWhenVeryStrong() {
        assertEquals(EventSeverity.MINOR, classifier.classify(event(1, 1.5, 6, 0, 100)).severity());
    }

    @Test
    void lowConfidenceCapsSeverityAtModerate() {
        assertEquals(EventSeverity.MODERATE, classifier.classify(event(4, 0.9, 6, 5, 50)).severity());
    }
}
