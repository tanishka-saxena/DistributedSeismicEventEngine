package com.seismic.dataset;

import com.seismic.TestData;
import com.seismic.config.ProcessingConfig.FilterSettings;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseFilterTest {

    private static final double EPS = 1e-9;
    private final FilterSettings settings = new FilterSettings(3, 5.0);

    @Test
    void movingAverageUsesLastThreeReadingsOfTheSensor() {
        NoiseFilter filter = new NoiseFilter(settings);
        assertEquals(0.1, filter.apply(TestData.valid("R1", "A1", 0, 0.1)).getFilteredAmplitude(), EPS);
        assertEquals(0.15, filter.apply(TestData.valid("R2", "A1", 1, 0.2)).getFilteredAmplitude(), EPS);
        assertEquals(0.2, filter.apply(TestData.valid("R3", "A1", 2, 0.3)).getFilteredAmplitude(), EPS);
        assertEquals((0.2 + 0.3 + 0.6) / 3, filter.apply(TestData.valid("R4", "A1", 3, 0.6)).getFilteredAmplitude(), EPS);
    }

    @Test
    void differentSensorsAreNeverAveragedTogether() {
        NoiseFilter filter = new NoiseFilter(settings);
        filter.apply(TestData.valid("R1", "A1", 0, 0.1));
        assertEquals(0.9, filter.apply(TestData.valid("R2", "A2", 0, 0.9)).getFilteredAmplitude(), EPS);
        assertEquals(0.1, filter.apply(TestData.valid("R3", "A1", 1, 0.1)).getFilteredAmplitude(), EPS);
    }

    @Test
    void largeTimeGapRestartsTheWindow() {
        NoiseFilter filter = new NoiseFilter(settings);
        filter.apply(TestData.valid("R1", "A1", 0, 0.9));
        assertEquals(0.1, filter.apply(TestData.valid("R2", "A1", 10, 0.1)).getFilteredAmplitude(), EPS);
    }

    @Test
    void outOfOrderReadingIsNotAddedToTheWindow() {
        NoiseFilter filter = new NoiseFilter(settings);
        filter.apply(TestData.valid("R1", "A1", 2, 0.1));
        assertEquals(0.9, filter.apply(TestData.valid("R2", "A1", 1, 0.9)).getFilteredAmplitude(), EPS);
        assertEquals(0.1, filter.apply(TestData.valid("R3", "A1", 3, 0.1)).getFilteredAmplitude(), EPS);
    }

    @Test
    void batchFilteringProcessesEachSensorInTimestampOrder() {
        List<SeismicReading> shuffled = List.of(
                TestData.valid("R3", "A1", 2, 0.3),
                TestData.valid("B1", "B1", 1, 0.8),
                TestData.valid("R1", "A1", 0, 0.1),
                TestData.valid("R2", "A1", 1, 0.2));
        List<SeismicReading> result = NoiseFilter.applyAll(shuffled, settings);
        List<SeismicReading> a1 = result.stream().filter(r -> r.getSensorId().equals("A1")).toList();
        assertEquals(List.of("R1", "R2", "R3"), a1.stream().map(SeismicReading::getReadingId).toList());
        assertEquals(0.1, a1.get(0).getFilteredAmplitude(), EPS);
        assertEquals(0.15, a1.get(1).getFilteredAmplitude(), EPS);
        assertEquals(0.2, a1.get(2).getFilteredAmplitude(), EPS);
    }

    @Test
    void invalidReadingsAreSkippedAndKeepNoFilteredValue() {
        NoiseFilter filter = new NoiseFilter(settings);
        filter.apply(TestData.valid("R1", "A1", 0, 0.1));
        SeismicReading invalid = TestData.builder("R2", "A1", 1, 5.0).quality(ReadingQuality.INVALID).build();
        assertTrue(Double.isNaN(filter.apply(invalid).getFilteredAmplitude()));
        assertEquals(0.2, filter.apply(TestData.valid("R3", "A1", 2, 0.3)).getFilteredAmplitude(), EPS);
    }

    @Test
    void spikeIsKeptInRawValueAndOnlySmoothedInFilteredValue() {
        NoiseFilter filter = new NoiseFilter(settings);
        filter.apply(TestData.valid("R1", "A1", 0, 0.1));
        filter.apply(TestData.valid("R2", "A1", 1, 0.1));
        SeismicReading spike = filter.apply(TestData.valid("R3", "A1", 2, 1.0));
        assertEquals(1.0, spike.getAmplitude(), EPS);
        assertEquals(0.4, spike.getFilteredAmplitude(), EPS);
    }
}
