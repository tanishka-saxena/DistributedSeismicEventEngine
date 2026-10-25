package com.seismic.model;

import java.time.Instant;

/**
 * How one sensor took part in one event: when it first and last triggered, its peak values,
 * and its distance from the event origin. Stored in the event_sensor_associations table.
 */
public final class SensorParticipation {

    private final String sensorId;
    private final String zone;
    private final double distanceFromOriginKm;
    private final boolean origin;
    private Instant firstTrigger;
    private Instant lastTrigger;
    private double peakAmplitude;
    private double peakFilteredAmplitude;
    private double firstFrequencyHz;
    private int candidateReadings;

    public SensorParticipation(String sensorId, String zone, double distanceFromOriginKm, boolean origin,
                               Instant firstTrigger, Instant lastTrigger, double peakAmplitude,
                               double peakFilteredAmplitude, double firstFrequencyHz, int candidateReadings) {
        this.sensorId = sensorId;
        this.zone = zone;
        this.distanceFromOriginKm = distanceFromOriginKm;
        this.origin = origin;
        this.firstTrigger = firstTrigger;
        this.lastTrigger = lastTrigger;
        this.peakAmplitude = peakAmplitude;
        this.peakFilteredAmplitude = peakFilteredAmplitude;
        this.firstFrequencyHz = firstFrequencyHz;
        this.candidateReadings = candidateReadings;
    }

    /** Creates the participation record for the first candidate reading of a sensor. */
    public static SensorParticipation start(SeismicReading reading, String zone, double distanceKm, boolean origin) {
        return new SensorParticipation(reading.getSensorId(), zone, distanceKm, origin,
                reading.getTimestamp(), reading.getTimestamp(), reading.getAmplitude(),
                filteredOrRaw(reading), reading.getFrequencyHz(), 1);
    }

    /** Adds a further candidate reading from the same sensor. */
    public void include(SeismicReading reading) {
        if (reading.getTimestamp().isBefore(firstTrigger)) {
            firstTrigger = reading.getTimestamp();
            firstFrequencyHz = reading.getFrequencyHz();
        }
        if (reading.getTimestamp().isAfter(lastTrigger)) {
            lastTrigger = reading.getTimestamp();
        }
        peakAmplitude = Math.max(peakAmplitude, reading.getAmplitude());
        peakFilteredAmplitude = Math.max(peakFilteredAmplitude, filteredOrRaw(reading));
        candidateReadings++;
    }

    private static double filteredOrRaw(SeismicReading reading) {
        return reading.hasFilteredAmplitude() ? reading.getFilteredAmplitude() : reading.getAmplitude();
    }

    public SensorParticipation copy() {
        return new SensorParticipation(sensorId, zone, distanceFromOriginKm, origin, firstTrigger, lastTrigger,
                peakAmplitude, peakFilteredAmplitude, firstFrequencyHz, candidateReadings);
    }

    public String getSensorId() { return sensorId; }
    public String getZone() { return zone; }
    public double getDistanceFromOriginKm() { return distanceFromOriginKm; }
    public boolean isOrigin() { return origin; }
    public Instant getFirstTrigger() { return firstTrigger; }
    public Instant getLastTrigger() { return lastTrigger; }
    public double getPeakAmplitude() { return peakAmplitude; }
    public double getPeakFilteredAmplitude() { return peakFilteredAmplitude; }
    public double getFirstFrequencyHz() { return firstFrequencyHz; }
    public int getCandidateReadings() { return candidateReadings; }
}
