package com.seismic.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A virtual seismic sensor node.
 * <p>
 * Identity fields (ID, name, zone, location) are immutable. The operational state (status,
 * reliability, latest reading) is written by the processing thread and read by the JavaFX thread,
 * so every accessor is {@code synchronized}. The UI works on {@link #copy()} snapshots so it never
 * sees a half-updated sensor.
 */
public final class Sensor {

    public static final double MIN_RELIABILITY = 0.0;
    public static final double MAX_RELIABILITY = 100.0;

    private final String sensorId;
    private final String name;
    private final String zone;
    private final GeoPoint location;

    private SensorStatus status = SensorStatus.ACTIVE;
    private double reliabilityScore = MAX_RELIABILITY;
    private Instant latestReadingTime;
    private Instant lastHeartbeat;
    private double latestAmplitude = Double.NaN;
    private double latestFilteredAmplitude = Double.NaN;
    private ReadingQuality latestQuality = ReadingQuality.UNCHECKED;

    public Sensor(String sensorId, String name, String zone, GeoPoint location) {
        if (sensorId == null || !sensorId.trim().matches("[A-Za-z0-9_-]{1,16}")) {
            throw new IllegalArgumentException("Sensor ID must be 1-16 letters, digits, '_' or '-': " + sensorId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Sensor name must not be blank");
        }
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("Sensor zone must not be blank");
        }
        this.sensorId = sensorId.trim().toUpperCase(Locale.ROOT);
        this.name = name.trim();
        this.zone = zone.trim();
        this.location = Objects.requireNonNull(location, "location (use GeoPoint.UNKNOWN if not known)");
    }

    /** Returns an independent copy that is safe to hand to another thread. */
    public synchronized Sensor copy() {
        Sensor copy = new Sensor(sensorId, name, zone, location);
        copy.status = status;
        copy.reliabilityScore = reliabilityScore;
        copy.latestReadingTime = latestReadingTime;
        copy.lastHeartbeat = lastHeartbeat;
        copy.latestAmplitude = latestAmplitude;
        copy.latestFilteredAmplitude = latestFilteredAmplitude;
        copy.latestQuality = latestQuality;
        return copy;
    }

    /** Stores a summary of the newest reading; the reading itself lives in the reading repository. */
    public synchronized void recordReading(SeismicReading reading) {
        if (!reading.getSensorId().equals(sensorId)) {
            throw new IllegalArgumentException("Reading " + reading.getReadingId() + " belongs to " + reading.getSensorId());
        }
        if (latestReadingTime == null || !reading.getTimestamp().isBefore(latestReadingTime)) {
            latestReadingTime = reading.getTimestamp();
            latestAmplitude = reading.getAmplitude();
            latestFilteredAmplitude = reading.getFilteredAmplitude();
            latestQuality = reading.getQuality();
        }
    }

    /** Adds {@code delta} to the reliability score, clamped to 0..100. Returns the new score. */
    public synchronized double adjustReliability(double delta) {
        reliabilityScore = Math.max(MIN_RELIABILITY, Math.min(MAX_RELIABILITY, reliabilityScore + delta));
        return reliabilityScore;
    }

    /** Restores the state a sensor has when the network is first created. */
    public synchronized void resetState() {
        status = SensorStatus.ACTIVE;
        reliabilityScore = MAX_RELIABILITY;
        latestReadingTime = null;
        lastHeartbeat = null;
        latestAmplitude = Double.NaN;
        latestFilteredAmplitude = Double.NaN;
        latestQuality = ReadingQuality.UNCHECKED;
    }

    public String getSensorId() { return sensorId; }
    public String getName() { return name; }
    public String getZone() { return zone; }
    public GeoPoint getLocation() { return location; }

    public synchronized SensorStatus getStatus() { return status; }
    public synchronized void setStatus(SensorStatus status) { this.status = Objects.requireNonNull(status); }
    public synchronized double getReliabilityScore() { return reliabilityScore; }
    public synchronized void setReliabilityScore(double score) {
        if (score < MIN_RELIABILITY || score > MAX_RELIABILITY || Double.isNaN(score)) {
            throw new IllegalArgumentException("Reliability must be between 0 and 100: " + score);
        }
        this.reliabilityScore = score;
    }
    public synchronized Instant getLatestReadingTime() { return latestReadingTime; }
    public synchronized Instant getLastHeartbeat() { return lastHeartbeat; }
    public synchronized void setLastHeartbeat(Instant at) { this.lastHeartbeat = at; }
    public synchronized double getLatestAmplitude() { return latestAmplitude; }
    public synchronized double getLatestFilteredAmplitude() { return latestFilteredAmplitude; }
    public synchronized ReadingQuality getLatestQuality() { return latestQuality; }

    /** One-line description of the newest reading for tables and tooltips. */
    public synchronized String getLatestReadingSummary() {
        if (latestReadingTime == null) {
            return "no readings yet";
        }
        if (Double.isNaN(latestAmplitude)) {
            return "amplitude missing (" + latestQuality + ")";
        }
        return String.format(Locale.ROOT, "amp %.3f / filtered %.3f (%s)",
                latestAmplitude, latestFilteredAmplitude, latestQuality);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sensor other && sensorId.equals(other.sensorId);
    }

    @Override
    public int hashCode() {
        return sensorId.hashCode();
    }

    @Override
    public String toString() {
        return sensorId + " (" + name + ", " + zone + ")";
    }
}
