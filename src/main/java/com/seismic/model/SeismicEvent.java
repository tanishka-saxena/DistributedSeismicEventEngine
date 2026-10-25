package com.seismic.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A potential or confirmed seismic event assembled from candidate readings of one or more sensors.
 * <p>
 * An event object is mutated only by the processing thread while it is being assembled
 * (EventCorrelator adds readings, the pipeline sets confidence, status and severity). Every other
 * thread receives a {@link #copy()}.
 */
public final class SeismicEvent {

    /** Upper bound on readings kept in memory per event, so a long event cannot grow without limit. */
    public static final int MAX_STORED_READINGS = 500;

    private final String eventId;
    private final ReadingSource source;
    private final String originSensorId;
    private final GeoPoint originLocation;
    private final Instant startTime;

    private Instant lastActivityTime;
    private Instant resolvedAt;
    private EventStatus status = EventStatus.CANDIDATE;
    private EventSeverity severity = EventSeverity.NORMAL;
    private ConfidenceBreakdown confidence = ConfidenceBreakdown.NONE;
    private final Map<String, SensorParticipation> participations = new LinkedHashMap<>();
    private final List<SeismicReading> contributingReadings = new ArrayList<>();
    private final List<String> explanation = new ArrayList<>();
    private double frequencySum;
    private int frequencyCount;
    private double storedMeanFrequency = Double.NaN;
    private Long runId;
    private Long datasetId;

    public SeismicEvent(String eventId, ReadingSource source, String originSensorId,
                        GeoPoint originLocation, Instant startTime) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Event ID must not be blank");
        }
        this.eventId = eventId;
        this.source = Objects.requireNonNull(source, "source");
        this.originSensorId = Objects.requireNonNull(originSensorId, "originSensorId");
        this.originLocation = originLocation == null ? GeoPoint.UNKNOWN : originLocation;
        this.startTime = Objects.requireNonNull(startTime, "startTime");
        this.lastActivityTime = startTime;
    }

    /** Adds a candidate reading. The caller has already decided that it belongs to this event. */
    public void addCandidate(SeismicReading reading, String zone, double distanceFromOriginKm) {
        SensorParticipation existing = participations.get(reading.getSensorId());
        if (existing == null) {
            boolean isOrigin = reading.getSensorId().equals(originSensorId);
            participations.put(reading.getSensorId(),
                    SensorParticipation.start(reading, zone, distanceFromOriginKm, isOrigin));
        } else {
            existing.include(reading);
        }
        if (reading.getTimestamp().isAfter(lastActivityTime)) {
            lastActivityTime = reading.getTimestamp();
        }
        if (!Double.isNaN(reading.getFrequencyHz())) {
            frequencySum += reading.getFrequencyHz();
            frequencyCount++;
        }
        if (contributingReadings.size() < MAX_STORED_READINGS) {
            contributingReadings.add(reading);
        }
    }

    /** Used by repositories when an event is loaded from the database. */
    public void restoreParticipation(SensorParticipation participation) {
        participations.put(participation.getSensorId(), participation);
    }

    public void addExplanation(String line) {
        explanation.add(line);
    }

    public void replaceExplanation(Collection<String> lines) {
        explanation.clear();
        explanation.addAll(lines);
    }

    public SeismicEvent copy() {
        SeismicEvent copy = new SeismicEvent(eventId, source, originSensorId, originLocation, startTime);
        copy.lastActivityTime = lastActivityTime;
        copy.resolvedAt = resolvedAt;
        copy.status = status;
        copy.severity = severity;
        copy.confidence = confidence;
        participations.forEach((id, p) -> copy.participations.put(id, p.copy()));
        copy.contributingReadings.addAll(contributingReadings);
        copy.explanation.addAll(explanation);
        copy.frequencySum = frequencySum;
        copy.frequencyCount = frequencyCount;
        copy.storedMeanFrequency = storedMeanFrequency;
        copy.runId = runId;
        copy.datasetId = datasetId;
        return copy;
    }

    // ----- derived values -----

    public int getParticipatingSensorCount() {
        return participations.size();
    }

    public double getPeakAmplitude() {
        return participations.values().stream().mapToDouble(SensorParticipation::getPeakAmplitude).max().orElse(0);
    }

    /** Peak of the moving-average amplitude: a single-sample spike cannot inflate this value. */
    public double getPeakFilteredAmplitude() {
        return participations.values().stream()
                .mapToDouble(SensorParticipation::getPeakFilteredAmplitude).max().orElse(0);
    }

    public double getMeanFrequencyHz() {
        return frequencyCount > 0 ? frequencySum / frequencyCount : storedMeanFrequency;
    }

    public double getDurationSeconds() {
        return Duration.between(startTime, lastActivityTime).toMillis() / 1000.0;
    }

    /** Largest distance from the origin sensor to a participating sensor, in km (0 for one sensor). */
    public double getAffectedRadiusKm() {
        return participations.values().stream()
                .mapToDouble(SensorParticipation::getDistanceFromOriginKm)
                .filter(d -> !Double.isNaN(d)).max().orElse(0);
    }

    /** Zones involved, origin zone first. */
    public Set<String> getZones() {
        Set<String> zones = new LinkedHashSet<>();
        SensorParticipation origin = participations.get(originSensorId);
        if (origin != null) {
            zones.add(origin.getZone());
        }
        participations.values().forEach(p -> zones.add(p.getZone()));
        return zones;
    }

    /** Short text such as "Central (+North, East)". */
    public String getAffectedZoneSummary() {
        List<String> zones = new ArrayList<>(getZones());
        if (zones.isEmpty()) {
            return "unknown";
        }
        if (zones.size() == 1) {
            return zones.get(0);
        }
        return zones.get(0) + " (+" + String.join(", ", zones.subList(1, zones.size())) + ")";
    }

    // ----- accessors -----

    public String getEventId() { return eventId; }
    public ReadingSource getSource() { return source; }
    public String getOriginSensorId() { return originSensorId; }
    public GeoPoint getOriginLocation() { return originLocation; }
    public Instant getStartTime() { return startTime; }
    public Instant getLastActivityTime() { return lastActivityTime; }
    public void setLastActivityTime(Instant t) { this.lastActivityTime = Objects.requireNonNull(t); }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = Objects.requireNonNull(status); }
    public EventSeverity getSeverity() { return severity; }
    public void setSeverity(EventSeverity severity) { this.severity = Objects.requireNonNull(severity); }
    public ConfidenceBreakdown getConfidence() { return confidence; }
    public double getConfidenceScore() { return confidence.score(); }
    public void setConfidence(ConfidenceBreakdown confidence) { this.confidence = Objects.requireNonNull(confidence); }
    public Collection<SensorParticipation> getParticipations() { return Collections.unmodifiableCollection(participations.values()); }
    public SensorParticipation getParticipation(String sensorId) { return participations.get(sensorId); }
    public Set<String> getParticipatingSensorIds() { return Collections.unmodifiableSet(participations.keySet()); }
    public List<SeismicReading> getContributingReadings() { return Collections.unmodifiableList(contributingReadings); }
    public List<String> getExplanation() { return Collections.unmodifiableList(explanation); }
    public void setStoredMeanFrequencyHz(double value) { this.storedMeanFrequency = value; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long datasetId) { this.datasetId = datasetId; }

    @Override
    public String toString() {
        return eventId + " " + status + " " + severity + " sensors=" + participations.keySet()
                + String.format(" confidence=%.1f", confidence.score());
    }
}
