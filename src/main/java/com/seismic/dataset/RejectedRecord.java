package com.seismic.dataset;

/** A CSV line that could not be turned into a reading, with the reason. */
public record RejectedRecord(int lineNumber, String reason, String rawLine) {
}
