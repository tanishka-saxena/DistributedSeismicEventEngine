package com.seismic.model;

/** Validation outcome of a single reading (see DataValidator for the rules). */
public enum ReadingQuality {
    /** Not yet validated. */
    UNCHECKED,
    /** All values present and within the physical range of the virtual sensor. */
    VALID,
    /** Values are possible but questionable (very noisy or near saturation); excluded from correlation. */
    SUSPICIOUS,
    /** A value is missing or impossible; kept for the record but excluded from detection. */
    INVALID;

    /** VALID and SUSPICIOUS readings carry a usable amplitude; INVALID and UNCHECKED do not. */
    public boolean hasUsableAmplitude() {
        return this == VALID || this == SUSPICIOUS;
    }
}
