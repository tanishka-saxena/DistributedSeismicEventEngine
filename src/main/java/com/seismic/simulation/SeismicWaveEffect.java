package com.seismic.simulation;

import com.seismic.model.GeoPoint;
import com.seismic.model.Sensor;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Random;

/**
 * A simulated seismic event: shaking that starts at an epicentre and reaches each sensor after a
 * delay of distance / wave speed, with amplitude attenuated as {@code peak * exp(-distance / L)}.
 * This makes readings of nearby sensors temporally and spatially related, which is exactly what
 * the correlation stage looks for.
 */
public final class SeismicWaveEffect extends TimedEffect {

    private final GeoPoint epicentre;
    private final double durationSeconds;
    private final double peakAmplitude;
    private final double attenuationLengthKm;
    private final double waveSpeedKmPerSecond;
    private final double maxRadiusKm;
    private final double minFrequencyHz;
    private final double maxFrequencyHz;
    private final String label;

    public SeismicWaveEffect(ScenarioType type, GeoPoint epicentre, Instant start, double durationSeconds,
                             double peakAmplitude, double attenuationLengthKm, double waveSpeedKmPerSecond,
                             double maxRadiusKm, String label) {
        // The effect as a whole stays alive until the wave has passed the farthest affected sensor.
        super(type, start, durationSeconds + maxRadiusKm / waveSpeedKmPerSecond);
        if (!epicentre.isKnown()) {
            throw new IllegalArgumentException("Epicentre must have coordinates");
        }
        this.epicentre = epicentre;
        this.durationSeconds = durationSeconds;
        this.peakAmplitude = peakAmplitude;
        this.attenuationLengthKm = attenuationLengthKm;
        this.waveSpeedKmPerSecond = waveSpeedKmPerSecond;
        this.maxRadiusKm = maxRadiusKm;
        this.label = label;
        // Seismic shaking is low frequency; stronger events are modelled slightly lower.
        boolean major = type == ScenarioType.MAJOR_EVENT;
        this.minFrequencyHz = major ? 2.0 : 3.0;
        this.maxFrequencyHz = major ? 5.0 : 6.5;
    }

    @Override
    public boolean appliesTo(Sensor sensor, Instant time) {
        double distance = epicentre.distanceKm(sensor.getLocation());
        if (Double.isNaN(distance) || distance > maxRadiusKm) {
            return false;
        }
        Instant arrival = arrivalAt(distance);
        return !time.isBefore(arrival) && time.isBefore(arrival.plus(Duration.ofMillis((long) (durationSeconds * 1000))));
    }

    @Override
    public void apply(ReadingDraft draft, Sensor sensor, Instant time, Random random) {
        double distance = epicentre.distanceKm(sensor.getLocation());
        double secondsSinceArrival = Duration.between(arrivalAt(distance), time).toMillis() / 1000.0;
        double shaking = peakAmplitude * Math.exp(-distance / attenuationLengthKm)
                * envelope(secondsSinceArrival) * between(random, 0.92, 1.08);
        // Shaking adds to the background signal; it never lowers it.
        draft.amplitude = Math.max(draft.amplitude, draft.amplitude * 0.3 + shaking);
        draft.frequencyHz = between(random, minFrequencyHz, maxFrequencyHz);
        draft.durationSeconds = between(random, 1.5, 4.0);
        draft.label = label;
    }

    /** Ramp up during the first second, full strength, then decay over the last quarter. */
    private double envelope(double secondsSinceArrival) {
        if (secondsSinceArrival < 1.0) {
            return 0.65;
        }
        double remaining = durationSeconds - secondsSinceArrival;
        if (remaining < durationSeconds * 0.25) {
            return 0.75;
        }
        return 1.0;
    }

    private Instant arrivalAt(double distanceKm) {
        return start.plus(Duration.ofMillis(Math.round(distanceKm / waveSpeedKmPerSecond * 1000)));
    }

    public GeoPoint getEpicentre() {
        return epicentre;
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "%s near %.3f, %.3f (peak %.2f, radius %.0f km)",
                type().getDisplayName(), epicentre.latitude(), epicentre.longitude(), peakAmplitude, maxRadiusKm);
    }
}
