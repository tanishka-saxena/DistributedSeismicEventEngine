package com.seismic.processing;

import com.seismic.TestData;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorStatus;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventProcessingPipelineTest {

    @Test
    void normalReadingsDoNotGenerateAnyEvent() {
        PipelineRunner runner = new PipelineRunner();
        runner.run(0, 30, PipelineRunner::quiet);
        assertTrue(runner.events.isEmpty());
        assertTrue(runner.statusChanges.isEmpty());
    }

    @Test
    void singleHighReadingCreatesOnlyACandidateThatIsDismissed() {
        PipelineRunner runner = new PipelineRunner();
        runner.run(0, 15, (sensor, second) -> sensor.getSensorId().equals("A1") && second == 3
                ? TestData.builder("x", "A1", second, 0.95)
                : PipelineRunner.quiet(sensor, second));

        assertEquals(1, runner.events.size());
        assertTrue(runner.eventHistory.stream().noneMatch(e -> e.getStatus() == EventStatus.CONFIRMED),
                "a single sensor must never confirm an event");
        assertTrue(runner.eventHistory.stream().anyMatch(e -> e.getStatus() == EventStatus.CANDIDATE));
        SeismicEvent last = runner.events.values().iterator().next();
        assertEquals(EventStatus.DISMISSED, last.getStatus());
        assertTrue(last.getSeverity().compareTo(EventSeverity.MINOR) <= 0, "one sensor caps severity at MINOR");
    }

    @Test
    void correlatedReadingsFromNearbySensorsConfirmAnEvent() {
        PipelineRunner runner = new PipelineRunner();
        Set<String> shaking = Set.of("A1", "A2", "A3");
        runner.run(0, 20, (sensor, second) -> shaking.contains(sensor.getSensorId()) && second >= 5 && second < 10
                ? TestData.builder("x", sensor.getSensorId(), second, 0.62)
                : PipelineRunner.quiet(sensor, second));

        assertEquals(1, runner.events.size());
        SeismicEvent event = runner.events.values().iterator().next();
        assertTrue(runner.eventHistory.stream().anyMatch(e -> e.getStatus() == EventStatus.CONFIRMED));
        assertEquals(EventStatus.RESOLVED, event.getStatus());
        assertEquals(shaking, event.getParticipatingSensorIds());
        assertTrue(event.getConfidenceScore() >= 60, "confidence " + event.getConfidenceScore());
        assertFalse(event.getExplanation().isEmpty());
    }

    @Test
    void invalidReadingsStillCountAsCommunication() {
        PipelineRunner runner = new PipelineRunner();
        runner.run(0, 15, (sensor, second) -> sensor.getSensorId().equals("A1")
                ? TestData.builder("x", "A1", second, Double.NaN)
                : PipelineRunner.quiet(sensor, second));
        assertTrue(runner.statusChanges.stream().noneMatch(c -> c.newStatus() == SensorStatus.OFFLINE),
                "a sensor sending invalid data is faulty, not offline");
        assertTrue(runner.statusChanges.stream().anyMatch(c -> c.sensorId().equals("A1") && c.newStatus() == SensorStatus.FAULTY));
    }

    @Test
    void missingReadingsMakeTheSensorOffline() {
        PipelineRunner runner = new PipelineRunner();
        runner.run(0, 15, (sensor, second) -> sensor.getSensorId().equals("B2") && second >= 3
                ? null : PipelineRunner.quiet(sensor, second));
        assertTrue(runner.statusChanges.stream().anyMatch(c -> c.sensorId().equals("B2") && c.newStatus() == SensorStatus.OFFLINE));
        assertTrue(runner.statusChanges.stream().noneMatch(c -> c.newStatus() == SensorStatus.FAULTY));
    }
}
