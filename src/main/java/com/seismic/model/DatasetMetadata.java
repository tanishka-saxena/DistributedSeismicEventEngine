package com.seismic.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Summary of one loaded dataset file: where it came from and how many records passed validation.
 *
 * @param datasetId database ID, or null if the metadata has not been saved
 */
public record DatasetMetadata(Long datasetId, String fileName, DatasetSourceType sourceType, Instant loadedAt,
                              int totalRecords, int validRecords, int suspiciousRecords, int invalidRecords,
                              int rejectedRecords, Instant firstReadingAt, Instant lastReadingAt, String notes) {

    public DatasetMetadata {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(loadedAt, "loadedAt");
        if (totalRecords < 0 || validRecords < 0 || suspiciousRecords < 0 || invalidRecords < 0 || rejectedRecords < 0) {
            throw new IllegalArgumentException("Record counts must not be negative");
        }
        if (validRecords + suspiciousRecords + invalidRecords + rejectedRecords != totalRecords) {
            throw new IllegalArgumentException("Record counts do not add up to the total");
        }
    }

    public DatasetMetadata withId(Long id) {
        return new DatasetMetadata(id, fileName, sourceType, loadedAt, totalRecords, validRecords,
                suspiciousRecords, invalidRecords, rejectedRecords, firstReadingAt, lastReadingAt, notes);
    }

    public int acceptedRecords() {
        return totalRecords - rejectedRecords;
    }
}
