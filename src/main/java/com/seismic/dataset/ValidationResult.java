package com.seismic.dataset;

import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;

import java.util.List;

/**
 * Outcome of validating one reading.
 *
 * @param rejected true if the record cannot be used at all (e.g. an implausible timestamp);
 *                 rejected records are reported but not kept as readings
 * @param issues   human-readable reasons, empty for a clean VALID reading
 */
public record ValidationResult(ReadingQuality quality, List<String> issues, boolean rejected) {

    public ValidationResult {
        issues = List.copyOf(issues);
    }

    public String summary() {
        return String.join("; ", issues);
    }

    /** Returns a copy of the reading carrying this quality and note. */
    public SeismicReading applyTo(SeismicReading reading) {
        return reading.withQuality(quality, summary());
    }
}
