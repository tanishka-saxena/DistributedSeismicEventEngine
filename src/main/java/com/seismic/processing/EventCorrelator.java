package com.seismic.processing;

import com.seismic.config.ProcessingConfig.CorrelationSettings;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Stage 2: groups candidate readings from different sensors into events.
 *
 * <p>A candidate joins an open event only if all three checks pass:
 * <ol>
 *   <li><b>Temporal</b>: it is no more than {@code windowSeconds} after the event's last activity, and the
 *       event would not exceed {@code maxEventDurationSeconds}.</li>
 *   <li><b>Spatial</b>: its sensor is within {@code radiusKm} of the event's origin sensor (the first sensor
 *       that triggered). If coordinates are missing, it may only join an event from the same zone.</li>
 *   <li><b>Signal consistency</b>: its dominant frequency is within {@code frequencyToleranceHz} of the event's
 *       mean frequency, and its burst lasts at least {@code minSignalDurationSeconds}. Missing values skip that check.</li>
 * </ol>
 * If several open events match, the one whose origin is closest wins; if none match, the candidate starts
 * a new event. This keeps simultaneous events in different areas apart.
 *
 * <p>Events are closed when no candidate arrived for {@code windowSeconds}, when they reach the maximum
 * duration, or (single-sensor events only) when the correlation window since the first trigger passes
 * without any other sensor joining. The last rule makes repeated spikes from one sensor count as separate
 * isolated anomalies.
 *
 * <p>Only VALID readings from ACTIVE sensors are correlated; SUSPICIOUS (noisy) readings and readings from
 * OFFLINE/FAULTY sensors are skipped. Not thread-safe: owned by the processing thread.
 * Cost per candidate: O(open events), which stays small because events close after a few seconds.
 */
public final class EventCorrelator {

    private final CorrelationSettings settings;
    private final SensorNetwork network;
    private final Supplier<String> eventIdSupplier;
    private final ReadingSource source;
    private final List<SeismicEvent> openEvents = new ArrayList<>();

    /** Result of offering a candidate: the event it now belongs to, or the reason it was not correlated. */
    public record Outcome(SeismicEvent event, boolean newEvent, String skippedReason) {
        public boolean correlated() {
            return event != null;
        }
    }

    public EventCorrelator(CorrelationSettings settings, SensorNetwork network,
                           Supplier<String> eventIdSupplier, ReadingSource source) {
        this.settings = settings;
        this.network = network;
        this.eventIdSupplier = eventIdSupplier;
        this.source = source;
    }

    public Outcome accept(CandidateDetection candidate) {
        SeismicReading reading = candidate.reading();
        Sensor sensor = network.require(reading.getSensorId());
        if (reading.getQuality() != ReadingQuality.VALID) {
            return new Outcome(null, false, reading.getQuality() + " reading is not used for correlation");
        }
        if (!sensor.getStatus().isEligibleForCorrelation()) {
            return new Outcome(null, false, "sensor is " + sensor.getStatus());
        }

        SeismicEvent best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SeismicEvent event : openEvents) {
            if (mismatchReason(event, reading, sensor) == null) {
                double distance = distanceFromOrigin(event, sensor);
                double rank = Double.isNaN(distance) ? Double.MAX_VALUE / 2 : distance;
                if (best == null || rank < bestDistance) {
                    best = event;
                    bestDistance = rank;
                }
            }
        }
        if (best != null) {
            boolean newSensor = best.getParticipation(sensor.getSensorId()) == null;
            double distance = distanceFromOrigin(best, sensor);
            best.addCandidate(reading, sensor.getZone(), distance);
            if (newSensor) {
                best.addExplanation(String.format(Locale.ROOT,
                        "%s joined %+.1f s after origin, %s from origin, %.2f Hz (raw %.3f, filtered %.3f)",
                        sensor.getSensorId(), seconds(best.getStartTime(), reading.getTimestamp()),
                        Double.isNaN(distance) ? "distance unknown" : String.format(Locale.ROOT, "%.1f km", distance),
                        reading.getFrequencyHz(), reading.getAmplitude(), reading.getFilteredAmplitude()));
            }
            return new Outcome(best, false, null);
        }

        SeismicEvent event = new SeismicEvent(eventIdSupplier.get(), source, sensor.getSensorId(),
                sensor.getLocation(), reading.getTimestamp());
        event.addCandidate(reading, sensor.getZone(), 0.0);
        event.addExplanation(String.format(Locale.ROOT,
                "Candidate opened by %s (%s) at %s: raw amplitude %.3f = %s, filtered %.3f = %s, %.2f Hz, %.2f s burst",
                sensor.getSensorId(), sensor.getZone(), reading.getTimestamp().truncatedTo(ChronoUnit.MILLIS), reading.getAmplitude(),
                candidate.level(), reading.getFilteredAmplitude(), candidate.sustainedLevel(),
                reading.getFrequencyHz(), reading.getDurationSeconds()));
        if (!Double.isNaN(reading.getDurationSeconds()) && reading.getDurationSeconds() < settings.minSignalDurationSeconds()) {
            event.addExplanation(String.format(Locale.ROOT,
                    "Burst of %.2f s is shorter than %.2f s: looks like a transient, not seismic shaking",
                    reading.getDurationSeconds(), settings.minSignalDurationSeconds()));
        }
        openEvents.add(event);
        return new Outcome(event, true, null);
    }

    /** Returns null if the reading may join the event, otherwise the reason it may not. */
    String mismatchReason(SeismicEvent event, SeismicReading reading, Sensor sensor) {
        Instant time = reading.getTimestamp();
        double sinceLast = seconds(event.getLastActivityTime(), time);
        double sinceStart = seconds(event.getStartTime(), time);
        if (sinceStart < -settings.windowSeconds()) {
            return "reading is before the event window";
        }
        if (sinceLast > settings.windowSeconds()) {
            return "outside correlation window";
        }
        if (sinceStart > settings.maxEventDurationSeconds()) {
            return "event already at maximum duration";
        }
        double distance = distanceFromOrigin(event, sensor);
        if (Double.isNaN(distance)) {
            Sensor origin = network.require(event.getOriginSensorId());
            if (!origin.getZone().equals(sensor.getZone())) {
                return "location unknown and sensor is in a different zone";
            }
        } else if (distance > settings.radiusKm()) {
            return "outside spatial radius";
        }
        double duration = reading.getDurationSeconds();
        if (!Double.isNaN(duration) && duration < settings.minSignalDurationSeconds()) {
            return "burst too short to be seismic shaking";
        }
        double meanFrequency = event.getMeanFrequencyHz();
        double frequency = reading.getFrequencyHz();
        if (!Double.isNaN(meanFrequency) && !Double.isNaN(frequency)
                && Math.abs(frequency - meanFrequency) > settings.frequencyToleranceHz()) {
            return "frequency inconsistent with event";
        }
        return null;
    }

    /** Removes and returns the events whose activity has ended as of {@code now}. */
    public List<SeismicEvent> closeExpired(Instant now) {
        List<SeismicEvent> closed = new ArrayList<>();
        Iterator<SeismicEvent> it = openEvents.iterator();
        while (it.hasNext()) {
            SeismicEvent event = it.next();
            boolean quiet = seconds(event.getLastActivityTime(), now) > settings.windowSeconds();
            boolean tooLong = seconds(event.getStartTime(), now) > settings.maxEventDurationSeconds();
            boolean uncorroborated = event.getParticipatingSensorCount() == 1
                    && seconds(event.getStartTime(), now) > settings.windowSeconds();
            if (quiet || tooLong || uncorroborated) {
                it.remove();
                closed.add(event);
            }
        }
        return closed;
    }

    /** Removes and returns every open event (used when monitoring stops or a dataset ends). */
    public List<SeismicEvent> closeAll() {
        List<SeismicEvent> closed = new ArrayList<>(openEvents);
        openEvents.clear();
        return closed;
    }

    public List<SeismicEvent> getOpenEvents() {
        return List.copyOf(openEvents);
    }

    public Optional<SeismicEvent> findOpen(String eventId) {
        return openEvents.stream().filter(e -> e.getEventId().equals(eventId)).findFirst();
    }

    private double distanceFromOrigin(SeismicEvent event, Sensor sensor) {
        return event.getOriginLocation().distanceKm(sensor.getLocation());
    }

    static double seconds(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }
}
