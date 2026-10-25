package com.seismic.simulation;

/**
 * Mutable working values of a reading while scenario effects are applied to it. Used only inside
 * one sampling task, so it does not need to be thread-safe.
 */
public final class ReadingDraft {
    double amplitude;
    double frequencyHz;
    double durationSeconds;
    double noiseLevel;
    String label = "NORMAL";
    /** Set by a communication-failure effect: the reading is generated but never delivered. */
    boolean dropped;

    ReadingDraft(double amplitude, double frequencyHz, double durationSeconds, double noiseLevel) {
        this.amplitude = amplitude;
        this.frequencyHz = frequencyHz;
        this.durationSeconds = durationSeconds;
        this.noiseLevel = noiseLevel;
    }

    public double amplitude() { return amplitude; }
    public double frequencyHz() { return frequencyHz; }
    public double durationSeconds() { return durationSeconds; }
    public double noiseLevel() { return noiseLevel; }
    public String label() { return label; }
    public boolean dropped() { return dropped; }
}
