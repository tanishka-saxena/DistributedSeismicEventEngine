package com.seismic.processing;

import com.seismic.config.ProcessingConfig;
import com.seismic.dataset.DataValidator;
import com.seismic.dataset.NoiseFilter;
import com.seismic.dataset.ValidationResult;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Connects the processing stages in a fixed order for one reading:
 * <pre>
 * heartbeat -&gt; validation -&gt; noise filter -&gt; candidate detection -&gt; fault detection
 *           -&gt; multi-sensor correlation -&gt; confidence -&gt; confirmation -&gt; severity
 * </pre>
 * and, on every timer tick: communication timeouts, closing finished events (RESOLVED / UNCERTAIN /
 * DISMISSED), isolated-anomaly fault checks and reliability trends.
 *
 * <p>The same pipeline class serves the live simulator (SeismicEventService) and offline dataset analysis
 * (DatasetService), so both use identical rules. The pipeline is deliberately single-threaded: all engine
 * state is touched by one thread only, so the algorithms need no locks. Other threads get copies.
 */
public final class EventProcessingPipeline {

    private final ProcessingConfig config;
    private final SensorNetwork network;
    private final DataValidator validator;
    private final NoiseFilter noiseFilter;
    private final EventDetector detector;
    private final EventCorrelator correlator;
    private final ConfidenceCalculator confidenceCalculator;
    private final SeverityClassifier severityClassifier;
    private final SensorFaultDetector faultDetector;
    private final CommunicationMonitor communicationMonitor;

    public EventProcessingPipeline(ProcessingConfig config, SensorNetwork network, DataValidator validator,
                                   ReadingSource source, Supplier<String> eventIds) {
        this.config = config;
        this.network = network;
        this.validator = validator;
        this.noiseFilter = new NoiseFilter(config.filter());
        this.detector = new EventDetector(config.thresholds());
        this.correlator = new EventCorrelator(config.correlation(), network, eventIds, source);
        this.confidenceCalculator = new ConfidenceCalculator(config.confidence(), config.correlation(), config.thresholds());
        this.severityClassifier = new SeverityClassifier(config.severity(), detector,
                config.correlation().minSensors(), config.confidence().confirmationThreshold());
        this.faultDetector = new SensorFaultDetector(config.reliability(), network, config.correlation().radiusKm());
        double communicationPenalty = config.reliability().communicationPenalty();
        this.communicationMonitor = new CommunicationMonitor(config.communication(),
                (sensor, at) -> faultDetector.onCommunicationLoss(sensor, communicationPenalty, at));
    }

    /** Starts communication timeout tracking from {@code now}. */
    public void start(Instant now) {
        communicationMonitor.startTracking(now);
    }

    public PipelineResult process(SeismicReading incoming) {
        PipelineResult result = new PipelineResult();
        Optional<Sensor> found = network.find(incoming.getSensorId());
        if (found.isEmpty()) {
            result.addNote("Reading " + incoming.getReadingId() + " ignored: unknown sensor " + incoming.getSensorId());
            return result;
        }
        Sensor sensor = found.get();
        communicationMonitor.recordHeartbeat(sensor, incoming.getTimestamp()).ifPresent(result::addStatusChange);

        SeismicReading reading = incoming;
        if (reading.getQuality() == ReadingQuality.UNCHECKED) {
            ValidationResult validation = validator.validate(reading);
            if (validation.rejected()) {
                result.addNote("Reading " + reading.getReadingId() + " rejected: " + validation.summary());
                return result;
            }
            reading = validation.applyTo(reading);
        }
        reading = noiseFilter.apply(reading);
        sensor.recordReading(reading);

        Optional<CandidateDetection> candidate = detector.detect(reading);
        result.setReading(reading, candidate.isPresent());
        result.addFaultUpdate(faultDetector.onReading(sensor, reading, candidate.isPresent()));

        if (candidate.isPresent()) {
            EventCorrelator.Outcome outcome = correlator.accept(candidate.get());
            if (outcome.correlated()) {
                evaluate(outcome.event());
                result.addUpdatedEvent(outcome.event().copy());
            } else {
                result.addNote(String.format(Locale.ROOT, "Candidate %s from %s not correlated: %s",
                        reading.getReadingId(), sensor.getSensorId(), outcome.skippedReason()));
            }
        }
        return result;
    }

    /** Time-driven checks. In live mode {@code now} is the clock; in dataset replay it is the data time. */
    public PipelineResult tick(Instant now) {
        PipelineResult result = new PipelineResult();
        communicationMonitor.checkTimeouts(network.sensors(), now).forEach(result::addStatusChange);
        for (SeismicEvent event : correlator.closeExpired(now)) {
            finalizeEvent(event, now, result);
        }
        result.addFaultFindings(faultDetector.checkReliabilityTrends(now));
        return result;
    }

    /** Closes every open event, e.g. when monitoring stops or the dataset has been fully replayed. */
    public PipelineResult finish(Instant now) {
        PipelineResult result = new PipelineResult();
        for (SeismicEvent event : correlator.closeAll()) {
            finalizeEvent(event, now, result);
        }
        return result;
    }

    /** Recomputes confidence, confirmation and severity of an event that is still open. */
    private void evaluate(SeismicEvent event) {
        event.setConfidence(confidenceCalculator.calculate(event, network));
        int minSensors = config.correlation().minSensors();
        double threshold = config.confidence().confirmationThreshold();
        if (event.getStatus() == EventStatus.CANDIDATE && event.getParticipatingSensorCount() >= minSensors
                && event.getConfidenceScore() >= threshold) {
            event.setStatus(EventStatus.CONFIRMED);
            event.addExplanation(String.format(Locale.ROOT,
                    "CONFIRMED: %d sensors >= %d required and confidence %.1f >= %.0f (%s)",
                    event.getParticipatingSensorCount(), minSensors, event.getConfidenceScore(), threshold,
                    event.getConfidence().describe()));
        }
        updateSeverity(event);
    }

    private void updateSeverity(SeismicEvent event) {
        SeverityClassifier.Decision decision = severityClassifier.classify(event);
        EventSeverity previous = event.getSeverity();
        event.setSeverity(decision.severity());
        if (decision.severity() != previous && event.getStatus() != EventStatus.CANDIDATE) {
            event.addExplanation("Severity " + decision.severity() + ": " + String.join("; ", decision.reasons()));
        }
    }

    private void finalizeEvent(SeismicEvent event, Instant now, PipelineResult result) {
        evaluate(event);
        int sensors = event.getParticipatingSensorCount();
        int minSensors = config.correlation().minSensors();
        if (event.getStatus() == EventStatus.CONFIRMED) {
            event.setStatus(EventStatus.RESOLVED);
            event.setResolvedAt(now);
            event.addExplanation(String.format(Locale.ROOT,
                    "RESOLVED: no correlated activity for %.0f s; final confidence %s",
                    config.correlation().windowSeconds(), event.getConfidence().describe()));
        } else if (sensors == 1) {
            event.setStatus(EventStatus.DISMISSED);
            event.setResolvedAt(now);
            event.addExplanation(String.format(Locale.ROOT,
                    "DISMISSED as isolated anomaly: no other sensor within %.0f km triggered within %.0f s",
                    config.correlation().radiusKm(), config.correlation().windowSeconds()));
            Sensor sensor = network.require(event.getOriginSensorId());
            FaultUpdate update = faultDetector.onIsolatedAnomaly(sensor, event, now);
            update.findings().forEach(f -> event.addExplanation("Fault check: " + f.description()));
            result.addFaultUpdate(update);
        } else {
            event.setStatus(EventStatus.UNCERTAIN);
            event.setResolvedAt(now);
            String reason = sensors < minSensors
                    ? String.format(Locale.ROOT, "only %d sensors, %d required", sensors, minSensors)
                    : String.format(Locale.ROOT, "confidence %.1f below %.0f", event.getConfidenceScore(),
                    config.confidence().confirmationThreshold());
            event.addExplanation("UNCERTAIN, needs investigation: " + reason + " (" + event.getConfidence().describe() + ")");
        }
        updateSeverity(event);
        result.addUpdatedEvent(event.copy());
    }

    public List<SeismicEvent> getOpenEvents() {
        return correlator.getOpenEvents().stream().map(SeismicEvent::copy).toList();
    }

    public EventDetector getDetector() {
        return detector;
    }
}
