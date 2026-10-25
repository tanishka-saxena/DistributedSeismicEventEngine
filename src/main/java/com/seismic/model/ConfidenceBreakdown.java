package com.seismic.model;

import java.util.OptionalDouble;

/**
 * The factors behind an event confidence score. Each factor is in [0, 1]. An empty factor
 * means it could not be evaluated (for example, missing coordinates); it was left out of the
 * weighted average instead of being counted as agreement.
 *
 * @param score the final confidence index, 0 to 100
 */
public record ConfidenceBreakdown(double sensorAgreement, OptionalDouble temporalConsistency,
                                  OptionalDouble spatialConsistency, double signalScore, double score) {

    public static final ConfidenceBreakdown NONE =
            new ConfidenceBreakdown(0, OptionalDouble.empty(), OptionalDouble.empty(), 0, 0);

    public String describe() {
        return String.format("%.1f = agreement %.2f, temporal %s, spatial %s, signal %.2f",
                score, sensorAgreement, format(temporalConsistency), format(spatialConsistency), signalScore);
    }

    private static String format(OptionalDouble value) {
        return value.isPresent() ? String.format("%.2f", value.getAsDouble()) : "n/a";
    }
}
