package com.seismic.config;

import java.util.Properties;

/**
 * Settings of the live sensor simulator. All randomness is derived from {@code randomSeed}, so a
 * run with the same seed and the same scenario button presses produces the same readings per sensor.
 *
 * @param sampleIntervalMillis time between two readings of the same sensor
 * @param threadPoolSize       worker threads shared by the per-sensor sampling tasks
 */
public record SimulationConfig(long randomSeed, int sampleIntervalMillis, int threadPoolSize,
                               double minorEventSeconds, double minorEventPeakAmplitude,
                               double majorEventSeconds, double majorEventPeakAmplitude,
                               double faultSeconds, double communicationFailureSeconds,
                               double noisySeconds, double invalidDataSeconds,
                               double attenuationLengthKm, double waveSpeedKmPerSecond) {

    public SimulationConfig {
        if (sampleIntervalMillis < 100 || sampleIntervalMillis > 60_000) {
            throw new IllegalArgumentException("Sample interval must be between 100 ms and 60 s");
        }
        if (threadPoolSize < 1 || threadPoolSize > 32) {
            throw new IllegalArgumentException("Thread pool size must be between 1 and 32");
        }
        if (minorEventPeakAmplitude <= 0 || majorEventPeakAmplitude <= minorEventPeakAmplitude) {
            throw new IllegalArgumentException("Major event peak must exceed minor event peak");
        }
    }

    public static SimulationConfig defaults() {
        return fromProperties(new Properties());
    }

    public static SimulationConfig fromProperties(Properties p) {
        return new SimulationConfig(
                (long) ProcessingConfig.d(p, "simulation.randomSeed", 20261013),
                ProcessingConfig.i(p, "simulation.sampleIntervalMillis", 1000),
                ProcessingConfig.i(p, "simulation.threadPoolSize", 4),
                ProcessingConfig.d(p, "simulation.minorEventSeconds", 8),
                ProcessingConfig.d(p, "simulation.minorEventPeakAmplitude", 0.44),
                ProcessingConfig.d(p, "simulation.majorEventSeconds", 12),
                ProcessingConfig.d(p, "simulation.majorEventPeakAmplitude", 1.15),
                ProcessingConfig.d(p, "simulation.faultSeconds", 25),
                ProcessingConfig.d(p, "simulation.communicationFailureSeconds", 15),
                ProcessingConfig.d(p, "simulation.noisySeconds", 20),
                ProcessingConfig.d(p, "simulation.invalidDataSeconds", 12),
                ProcessingConfig.d(p, "simulation.attenuationLengthKm", 40),
                ProcessingConfig.d(p, "simulation.waveSpeedKmPerSecond", 6.0));
    }
}
