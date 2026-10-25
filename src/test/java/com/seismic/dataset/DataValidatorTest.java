package com.seismic.dataset;

import com.seismic.TestData;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataValidatorTest {

    private final DataValidator validator = TestData.validator();

    @Test
    void cleanReadingIsValid() {
        ValidationResult result = validator.validate(TestData.reading("R1", "A1", 0, 0.12));
        assertEquals(ReadingQuality.VALID, result.quality());
        assertFalse(result.rejected());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void missingAmplitudeIsFlaggedInvalidButKept() {
        ValidationResult result = validator.validate(TestData.reading("R1", "A1", 0, Double.NaN));
        assertEquals(ReadingQuality.INVALID, result.quality());
        assertFalse(result.rejected(), "the record is kept so the problem stays visible");
        assertTrue(result.summary().contains("amplitude missing"));
    }

    @Test
    void impossibleNumericValuesAreInvalid() {
        assertEquals(ReadingQuality.INVALID, validator.validate(TestData.reading("R1", "A1", 0, -0.1)).quality());
        assertEquals(ReadingQuality.INVALID, validator.validate(TestData.reading("R2", "A1", 0, 2.5)).quality());
        assertEquals(ReadingQuality.INVALID, validator.validate(
                TestData.builder("R3", "A1", 0, 0.1).frequencyHz(0).build()).quality());
        assertEquals(ReadingQuality.INVALID, validator.validate(
                TestData.builder("R4", "A1", 0, 0.1).noiseLevel(1.4).build()).quality());
        assertEquals(ReadingQuality.INVALID, validator.validate(
                TestData.builder("R5", "A1", 0, 0.1).durationSeconds(-1).build()).quality());
    }

    @Test
    void noisyOrNearSaturationReadingsAreSuspicious() {
        assertEquals(ReadingQuality.SUSPICIOUS, validator.validate(
                TestData.builder("R1", "A1", 0, 0.2).noiseLevel(0.7).build()).quality());
        assertEquals(ReadingQuality.SUSPICIOUS, validator.validate(TestData.reading("R2", "A1", 0, 1.85)).quality());
    }

    @Test
    void missingOptionalFieldsAreNotedButStayValid() {
        SeismicReading reading = TestData.builder("R1", "A1", 0, 0.1)
                .frequencyHz(Double.NaN).durationSeconds(Double.NaN).build();
        ValidationResult result = validator.validate(reading);
        assertEquals(ReadingQuality.VALID, result.quality());
        assertTrue(result.summary().contains("frequency not reported"));
        assertTrue(result.summary().contains("duration not reported"));
    }

    @Test
    void implausibleTimestampsAreRejected() {
        SeismicReading old = TestData.builder("R1", "A1", 0, 0.1).timestamp(Instant.parse("1999-12-31T23:59:59Z")).build();
        assertTrue(validator.validate(old).rejected());
        SeismicReading future = TestData.builder("R2", "A1", 0, 0.1)
                .timestamp(TestData.CLOCK.instant().plus(Duration.ofDays(2))).build();
        assertTrue(validator.validate(future).rejected());
    }

    @Test
    void parsingNoteFromLoaderIsKept() {
        SeismicReading reading = TestData.builder("R1", "A1", 0, Double.NaN)
                .qualityNote("amplitude 'abc' is not a number").build();
        ValidationResult result = validator.validate(reading);
        assertEquals(ReadingQuality.INVALID, result.quality());
        assertTrue(result.summary().contains("'abc'"));
    }
}
