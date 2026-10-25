package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.model.FaultFinding;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatusChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Drives an {@link EventProcessingPipeline} second by second with a scripted reading per sensor,
 * mimicking the live loop (tick, then readings) without threads or sleeps.
 */
final class PipelineRunner {

    final SensorNetwork network = TestData.network();
    final EventProcessingPipeline pipeline = new EventProcessingPipeline(TestData.config(), network,
            TestData.validator(), ReadingSource.SIMULATION, TestData.ids("EV"));
    final Map<String, SeismicEvent> events = new LinkedHashMap<>();
    final List<SeismicEvent> eventHistory = new ArrayList<>();
    final List<SensorStatusChange> statusChanges = new ArrayList<>();
    final List<FaultFinding> findings = new ArrayList<>();
    private int sequence;

    PipelineRunner() {
        pipeline.start(TestData.T0);
    }

    /**
     * Runs seconds [from, to). The script returns the reading for a sensor at a second, or null for "no reading".
     */
    void run(int from, int to, BiFunction<Sensor, Integer, SeismicReading.Builder> script) {
        for (int second = from; second < to; second++) {
            collect(pipeline.tick(TestData.at(second)));
            for (Sensor sensor : network.sensors()) {
                SeismicReading.Builder builder = script.apply(sensor, second);
                if (builder != null) {
                    collect(pipeline.process(builder.readingId("R" + (++sequence)).build()));
                }
            }
        }
    }

    /** Background reading helper: every sensor reports a quiet 0.10 amplitude. */
    static SeismicReading.Builder quiet(Sensor sensor, int second) {
        return TestData.builder("tmp", sensor.getSensorId(), second, 0.10);
    }

    void collect(PipelineResult result) {
        for (SeismicEvent event : result.getUpdatedEvents()) {
            events.put(event.getEventId(), event);
            eventHistory.add(event);
        }
        statusChanges.addAll(result.getStatusChanges());
        findings.addAll(result.getFaultFindings());
    }
}
