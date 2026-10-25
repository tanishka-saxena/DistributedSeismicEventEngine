package com.seismic.processing;

import com.seismic.config.ProcessingConfig;
import com.seismic.dataset.DataValidator;
import com.seismic.model.EventStatus;
import com.seismic.model.FaultFinding;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatusChange;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Replays a recorded dataset through {@link EventProcessingPipeline} using the data's own timestamps as
 * the clock: before the readings of each new second the pipeline gets a tick at that time, so
 * communication timeouts and event closing behave exactly as they would live, only faster.
 * The replay works on a fresh copy of the sensor network and never touches the live sensors.
 */
public final class DatasetAnalyzer {

    public record AnalysisResult(List<SeismicReading> processedReadings, List<SeismicEvent> events,
                                 List<SeismicEvent> dismissedCandidates, List<SensorStatusChange> statusChanges,
                                 List<FaultFinding> faultFindings, List<Sensor> finalSensorStates) {

        /** Events that were confirmed (and later resolved) or are uncertain; dismissed candidates excluded. */
        public List<SeismicEvent> eventsWithStatus(EventStatus status) {
            return events.stream().filter(e -> e.getStatus() == status).toList();
        }
    }

    private final ProcessingConfig config;
    private final DataValidator validator;

    public DatasetAnalyzer(ProcessingConfig config, DataValidator validator) {
        this.config = config;
        this.validator = validator;
    }

    public AnalysisResult analyze(List<SeismicReading> readings, SensorNetwork network, Supplier<String> eventIds) {
        SensorNetwork replayNetwork = network.freshCopy();
        EventProcessingPipeline pipeline = new EventProcessingPipeline(config, replayNetwork, validator,
                ReadingSource.DATASET, eventIds);
        List<SeismicReading> ordered = new ArrayList<>(readings);
        ordered.sort(Comparator.comparing(SeismicReading::getTimestamp).thenComparing(SeismicReading::getSensorId));

        List<SeismicReading> processed = new ArrayList<>(ordered.size());
        Map<String, SeismicEvent> latestEventState = new LinkedHashMap<>();
        List<SensorStatusChange> changes = new ArrayList<>();
        List<FaultFinding> findings = new ArrayList<>();

        Instant lastTick = null;
        for (SeismicReading reading : ordered) {
            Instant time = reading.getTimestamp();
            if (lastTick == null) {
                pipeline.start(time);
                lastTick = time;
            } else if (time.isAfter(lastTick)) {
                collect(pipeline.tick(time), latestEventState, changes, findings, processed);
                lastTick = time;
            }
            collect(pipeline.process(reading), latestEventState, changes, findings, processed);
        }
        if (lastTick != null) {
            // Close events still open at the end of the data. No further tick is run: the data simply
            // ends, which must not be mistaken for every sensor going silent.
            collect(pipeline.finish(lastTick), latestEventState, changes, findings, processed);
        }

        List<SeismicEvent> events = new ArrayList<>();
        List<SeismicEvent> dismissed = new ArrayList<>();
        for (SeismicEvent event : latestEventState.values()) {
            (event.getStatus() == EventStatus.DISMISSED ? dismissed : events).add(event);
        }
        return new AnalysisResult(processed, events, dismissed, changes, findings, replayNetwork.snapshot());
    }

    private static void collect(PipelineResult result, Map<String, SeismicEvent> events,
                                List<SensorStatusChange> changes, List<FaultFinding> findings,
                                List<SeismicReading> processed) {
        if (result.getReading() != null) {
            processed.add(result.getReading());
        }
        result.getUpdatedEvents().forEach(e -> events.put(e.getEventId(), e));
        changes.addAll(result.getStatusChanges());
        findings.addAll(result.getFaultFindings());
    }
}
