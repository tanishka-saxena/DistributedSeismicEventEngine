package com.seismic.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One measurement reported by a virtual sensor.
 * <p>
 * The class is immutable: validation and filtering return modified copies ({@link #withQuality},
 * {@link #withFilteredAmplitude}), so the original measured amplitude is always kept next to the
 * filtered value and the effect of filtering can be shown.
 * <p>
 * Missing numeric values are stored as {@link Double#NaN}. Physical ranges are deliberately NOT
 * enforced in the constructor: a reading must be able to carry bad data so that
 * {@code DataValidator} (the validation boundary) can flag it instead of the data silently disappearing.
 *
 * <ul>
 *   <li>amplitude: synthetic normalised amplitude units (sensor range 0.0 to 2.0)</li>
 *   <li>frequencyHz: dominant frequency in hertz</li>
 *   <li>durationSeconds: duration of the signal burst in seconds</li>
 *   <li>noiseLevel: estimated noise ratio from 0.0 (clean) to 1.0 (pure noise)</li>
 *   <li>timestamp: an {@link Instant}, i.e. always UTC</li>
 * </ul>
 */
public final class SeismicReading {

    private final String readingId;
    private final String sensorId;
    private final Instant timestamp;
    private final double amplitude;
    private final double frequencyHz;
    private final double durationSeconds;
    private final double noiseLevel;
    private final double filteredAmplitude;
    private final ReadingQuality quality;
    private final String qualityNote;
    private final ReadingSource source;
    private final String scenarioLabel;

    private SeismicReading(Builder b) {
        if (b.readingId == null || b.readingId.isBlank()) {
            throw new IllegalArgumentException("Reading ID must not be blank");
        }
        if (b.sensorId == null || b.sensorId.isBlank()) {
            throw new IllegalArgumentException("Sensor ID must not be blank");
        }
        this.readingId = b.readingId.trim();
        this.sensorId = b.sensorId.trim().toUpperCase(Locale.ROOT);
        this.timestamp = Objects.requireNonNull(b.timestamp, "timestamp");
        this.amplitude = b.amplitude;
        this.frequencyHz = b.frequencyHz;
        this.durationSeconds = b.durationSeconds;
        this.noiseLevel = b.noiseLevel;
        this.filteredAmplitude = b.filteredAmplitude;
        this.quality = Objects.requireNonNull(b.quality, "quality");
        this.qualityNote = b.qualityNote == null ? "" : b.qualityNote;
        this.source = Objects.requireNonNull(b.source, "source");
        this.scenarioLabel = b.scenarioLabel == null ? "" : b.scenarioLabel;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .readingId(readingId).sensorId(sensorId).timestamp(timestamp)
                .amplitude(amplitude).frequencyHz(frequencyHz).durationSeconds(durationSeconds)
                .noiseLevel(noiseLevel).filteredAmplitude(filteredAmplitude)
                .quality(quality).qualityNote(qualityNote).source(source).scenarioLabel(scenarioLabel);
    }

    public SeismicReading withQuality(ReadingQuality newQuality, String note) {
        return toBuilder().quality(newQuality).qualityNote(note).build();
    }

    public SeismicReading withFilteredAmplitude(double value) {
        return toBuilder().filteredAmplitude(value).build();
    }

    public String getReadingId() { return readingId; }
    public String getSensorId() { return sensorId; }
    public Instant getTimestamp() { return timestamp; }
    public double getAmplitude() { return amplitude; }
    public double getFrequencyHz() { return frequencyHz; }
    public double getDurationSeconds() { return durationSeconds; }
    public double getNoiseLevel() { return noiseLevel; }
    /** Moving-average amplitude, or NaN if the reading has not been filtered (or is invalid). */
    public double getFilteredAmplitude() { return filteredAmplitude; }
    public ReadingQuality getQuality() { return quality; }
    public String getQualityNote() { return qualityNote; }
    public ReadingSource getSource() { return source; }
    /** Ground-truth label written by the generator (synthetic data only); empty if unknown. */
    public String getScenarioLabel() { return scenarioLabel; }

    public boolean hasFilteredAmplitude() {
        return !Double.isNaN(filteredAmplitude);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SeismicReading other && readingId.equals(other.readingId);
    }

    @Override
    public int hashCode() {
        return readingId.hashCode();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s[%s @%s amp=%.3f filt=%.3f %s]",
                readingId, sensorId, timestamp, amplitude, filteredAmplitude, quality);
    }

    /** Builder: readings have many optional numeric fields, so a builder is clearer than a long constructor. */
    public static final class Builder {
        private String readingId;
        private String sensorId;
        private Instant timestamp;
        private double amplitude = Double.NaN;
        private double frequencyHz = Double.NaN;
        private double durationSeconds = Double.NaN;
        private double noiseLevel = Double.NaN;
        private double filteredAmplitude = Double.NaN;
        private ReadingQuality quality = ReadingQuality.UNCHECKED;
        private String qualityNote = "";
        private ReadingSource source = ReadingSource.SIMULATION;
        private String scenarioLabel = "";

        public Builder readingId(String v) { readingId = v; return this; }
        public Builder sensorId(String v) { sensorId = v; return this; }
        public Builder timestamp(Instant v) { timestamp = v; return this; }
        public Builder amplitude(double v) { amplitude = v; return this; }
        public Builder frequencyHz(double v) { frequencyHz = v; return this; }
        public Builder durationSeconds(double v) { durationSeconds = v; return this; }
        public Builder noiseLevel(double v) { noiseLevel = v; return this; }
        public Builder filteredAmplitude(double v) { filteredAmplitude = v; return this; }
        public Builder quality(ReadingQuality v) { quality = v; return this; }
        public Builder qualityNote(String v) { qualityNote = v; return this; }
        public Builder source(ReadingSource v) { source = v; return this; }
        public Builder scenarioLabel(String v) { scenarioLabel = v; return this; }

        public SeismicReading build() {
            return new SeismicReading(this);
        }
    }
}
