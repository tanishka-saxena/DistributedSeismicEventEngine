package com.seismic.config;

import java.util.Properties;

/**
 * Every tunable rule of the processing engine in one place. Defaults are documented in
 * docs/algorithms.md and can be overridden in config/engine.properties.
 * <p>
 * The amplitude thresholds are arbitrary values for the SYNTHETIC amplitude field only;
 * they are not real earthquake magnitude thresholds.
 */
public record ProcessingConfig(DetectionThresholds thresholds,
                               FilterSettings filter,
                               CorrelationSettings correlation,
                               ConfidenceSettings confidence,
                               ReliabilityPolicy reliability,
                               CommunicationSettings communication,
                               SeverityRules severity) {

    /** Synthetic amplitude thresholds. Below minor = NORMAL; [minor, moderate) = MINOR; [moderate, high] = MODERATE; above high = HIGH. */
    public record DetectionThresholds(double minor, double moderate, double high) {
        public DetectionThresholds {
            if (!(minor > 0 && minor < moderate && moderate < high)) {
                throw new IllegalArgumentException("Thresholds must satisfy 0 < minor < moderate < high");
            }
        }
    }

    /** Moving-average filter: window size in readings, and the largest gap that still counts as "the same stream". */
    public record FilterSettings(int windowSize, double maxGapSeconds) {
        public FilterSettings {
            if (windowSize < 1 || windowSize > 20) {
                throw new IllegalArgumentException("Filter window must be between 1 and 20 readings");
            }
            requirePositive(maxGapSeconds, "filter.maxGapSeconds");
        }
    }

    /**
     * Multi-sensor correlation rules.
     *
     * @param windowSeconds          a reading joins an event if it arrives within this time of the event's last activity
     * @param maxEventDurationSeconds an event never spans longer than this, so unrelated activity is not chained together
     * @param radiusKm               maximum distance between a sensor and the event origin sensor
     * @param minSensors             distinct sensors needed to confirm an event
     * @param frequencyToleranceHz   allowed difference from the event's mean dominant frequency
     * @param minSignalDurationSeconds bursts shorter than this are not treated as seismic shaking
     * @param waveSpeedKmPerSecond   assumed propagation speed used to predict arrival delays
     */
    public record CorrelationSettings(double windowSeconds, double maxEventDurationSeconds, double radiusKm,
                                      int minSensors, double frequencyToleranceHz, double minSignalDurationSeconds,
                                      double waveSpeedKmPerSecond) {
        public CorrelationSettings {
            requirePositive(windowSeconds, "correlation.windowSeconds");
            requirePositive(maxEventDurationSeconds, "correlation.maxEventDurationSeconds");
            requirePositive(radiusKm, "correlation.radiusKm");
            requirePositive(frequencyToleranceHz, "correlation.frequencyToleranceHz");
            requirePositive(waveSpeedKmPerSecond, "correlation.waveSpeedKmPerSecond");
            if (minSensors < 2) {
                throw new IllegalArgumentException("correlation.minSensors must be at least 2");
            }
            if (minSignalDurationSeconds < 0) {
                throw new IllegalArgumentException("correlation.minSignalDurationSeconds must not be negative");
            }
        }
    }

    /** Weights of the confidence formula (must sum to 1) and the confidence needed for confirmation. */
    public record ConfidenceSettings(double agreementWeight, double temporalWeight, double spatialWeight,
                                     double signalWeight, double confirmationThreshold) {
        public ConfidenceSettings {
            double sum = agreementWeight + temporalWeight + spatialWeight + signalWeight;
            if (Math.abs(sum - 1.0) > 1e-6 || agreementWeight < 0 || temporalWeight < 0 || spatialWeight < 0 || signalWeight < 0) {
                throw new IllegalArgumentException("Confidence weights must be non-negative and sum to 1.0, got " + sum);
            }
            if (confirmationThreshold < 0 || confirmationThreshold > 100) {
                throw new IllegalArgumentException("confirmationThreshold must be 0..100");
            }
        }
    }

    /** Reliability score adjustments (score range 0..100) and fault rules. */
    public record ReliabilityPolicy(int validStreakForBonus, double validStreakBonus, double invalidPenalty,
                                    double outlierPenalty, double unverifiedOutlierPenalty, double communicationPenalty,
                                    int invalidWindowSize, int repeatedInvalidCount, int repeatedOutlierCount,
                                    double outlierMemorySeconds, double peerDisagreementRatio, double faultyBelowScore,
                                    int recoveryValidStreak, double suddenDropPoints, double suddenDropWindowSeconds) {
        public ReliabilityPolicy {
            if (validStreakForBonus < 1 || invalidWindowSize < 1 || repeatedInvalidCount < 1
                    || repeatedOutlierCount < 1 || recoveryValidStreak < 1) {
                throw new IllegalArgumentException("Reliability counts must be at least 1");
            }
            if (repeatedInvalidCount > invalidWindowSize) {
                throw new IllegalArgumentException("repeatedInvalidCount cannot exceed invalidWindowSize");
            }
        }
    }

    /** A sensor that sends nothing for timeoutSeconds is OFFLINE; it returns after recoveryReadings consecutive readings. */
    public record CommunicationSettings(double timeoutSeconds, int recoveryReadings) {
        public CommunicationSettings {
            requirePositive(timeoutSeconds, "communication.timeoutSeconds");
            if (recoveryReadings < 1) {
                throw new IllegalArgumentException("communication.recoveryReadings must be at least 1");
            }
        }
    }

    /** Escalation rules for SeverityClassifier. */
    public record SeverityRules(double criticalAmplitude, int criticalMinSensors, double criticalMinDurationSeconds,
                                double criticalMinConfidence, int widespreadMinSensors, double widespreadRadiusKm) {
    }

    public static ProcessingConfig defaults() {
        return fromProperties(new Properties());
    }

    /** Reads overrides from properties; any key that is absent keeps its default value. */
    public static ProcessingConfig fromProperties(Properties p) {
        return new ProcessingConfig(
                new DetectionThresholds(
                        d(p, "detection.minorThreshold", 0.30),
                        d(p, "detection.moderateThreshold", 0.50),
                        d(p, "detection.highThreshold", 0.75)),
                new FilterSettings(
                        i(p, "filter.windowSize", 3),
                        d(p, "filter.maxGapSeconds", 5.0)),
                new CorrelationSettings(
                        d(p, "correlation.windowSeconds", 5.0),
                        d(p, "correlation.maxEventDurationSeconds", 40.0),
                        d(p, "correlation.radiusKm", 40.0),
                        i(p, "correlation.minSensors", 3),
                        d(p, "correlation.frequencyToleranceHz", 4.0),
                        d(p, "correlation.minSignalDurationSeconds", 0.5),
                        d(p, "correlation.waveSpeedKmPerSecond", 6.0)),
                new ConfidenceSettings(
                        d(p, "confidence.agreementWeight", 0.30),
                        d(p, "confidence.temporalWeight", 0.25),
                        d(p, "confidence.spatialWeight", 0.20),
                        d(p, "confidence.signalWeight", 0.25),
                        d(p, "confidence.confirmationThreshold", 60.0)),
                new ReliabilityPolicy(
                        i(p, "reliability.validStreakForBonus", 10),
                        d(p, "reliability.validStreakBonus", 1.0),
                        d(p, "reliability.invalidPenalty", 4.0),
                        d(p, "reliability.outlierPenalty", 8.0),
                        d(p, "reliability.unverifiedOutlierPenalty", 3.0),
                        d(p, "reliability.communicationPenalty", 10.0),
                        i(p, "reliability.invalidWindowSize", 10),
                        i(p, "reliability.repeatedInvalidCount", 3),
                        i(p, "reliability.repeatedOutlierCount", 3),
                        d(p, "reliability.outlierMemorySeconds", 120.0),
                        d(p, "reliability.peerDisagreementRatio", 3.0),
                        d(p, "reliability.faultyBelowScore", 40.0),
                        i(p, "reliability.recoveryValidStreak", 20),
                        d(p, "reliability.suddenDropPoints", 20.0),
                        d(p, "reliability.suddenDropWindowSeconds", 60.0)),
                new CommunicationSettings(
                        d(p, "communication.timeoutSeconds", 5.0),
                        i(p, "communication.recoveryReadings", 3)),
                new SeverityRules(
                        d(p, "severity.criticalAmplitude", 1.0),
                        i(p, "severity.criticalMinSensors", 6),
                        d(p, "severity.criticalMinDurationSeconds", 5.0),
                        d(p, "severity.criticalMinConfidence", 75.0),
                        i(p, "severity.widespreadMinSensors", 6),
                        d(p, "severity.widespreadRadiusKm", 20.0)));
    }

    static double d(Properties p, String key, double defaultValue) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config key " + key + " is not a number: " + value, e);
        }
    }

    static int i(Properties p, String key, int defaultValue) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Config key " + key + " is not an integer: " + value, e);
        }
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0)) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
