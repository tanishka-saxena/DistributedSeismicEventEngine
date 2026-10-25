package com.seismic.simulation;

import com.seismic.config.SimulationConfig;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorSimulatorTest {

    private static final Instant T = Instant.parse("2026-10-01T08:00:00Z");
    private SensorNetwork network;
    private LinkedBlockingQueue<SeismicReading> queue;
    private SensorSimulator simulator;

    @BeforeEach
    void setUp() throws Exception {
        network = SensorNetworkFactory.load(Path.of("data", "sensors.csv"));
        queue = new LinkedBlockingQueue<>(10_000);
        SimulationConfig defaults = SimulationConfig.defaults();
        SimulationConfig fast = new SimulationConfig(defaults.randomSeed(), 100, 4,
                defaults.minorEventSeconds(), defaults.minorEventPeakAmplitude(), defaults.majorEventSeconds(),
                defaults.majorEventPeakAmplitude(), defaults.faultSeconds(), defaults.communicationFailureSeconds(),
                defaults.noisySeconds(), defaults.invalidDataSeconds(), defaults.attenuationLengthKm(),
                defaults.waveSpeedKmPerSecond());
        simulator = new SensorSimulator(fast, network, Clock.systemUTC(), queue);
    }

    @AfterEach
    void tearDown() {
        simulator.close();
    }

    @Test
    void allSensorsReportConcurrentlyAndStopCleanly() throws Exception {
        simulator.start("T1");
        assertTrue(simulator.isRunning());
        Map<String, Integer> perSensor = new HashMap<>();
        long deadline = System.currentTimeMillis() + 10_000;
        // Wait for a condition (two readings from every sensor), not for a fixed time.
        while (perSensor.size() < network.size() || perSensor.values().stream().anyMatch(c -> c < 2)) {
            SeismicReading reading = queue.poll(500, TimeUnit.MILLISECONDS);
            if (reading != null) {
                perSensor.merge(reading.getSensorId(), 1, Integer::sum);
            }
            assertTrue(System.currentTimeMillis() < deadline, "sensors did not all report in time: " + perSensor);
        }
        simulator.stop();
        assertFalse(simulator.isRunning());
        queue.clear();
        assertNull(queue.poll(400, TimeUnit.MILLISECONDS), "no readings after stop");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.getName().startsWith("sensor-sim") && t.isAlive()), "worker threads terminated");
        simulator.stop(); // idempotent
    }

    @Test
    void readingsAreReproducibleAfterReset() {
        List<SeismicReading> first = simulator.sampleAllAt(T);
        simulator.reset();
        List<SeismicReading> second = simulator.sampleAllAt(T);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getAmplitude(), second.get(i).getAmplitude());
            assertEquals(first.get(i).getReadingId(), second.get(i).getReadingId());
        }
    }

    @Test
    void normalBackgroundStaysBelowTheMinorThreshold() {
        for (int s = 0; s < 30; s++) {
            simulator.sampleAllAt(T.plusSeconds(s)).forEach(r -> assertTrue(r.getAmplitude() < 0.30, r.toString()));
        }
    }

    @Test
    void communicationFailureSuppressesOnlyTheTargetSensor() {
        simulator.triggerScenario(ScenarioType.COMMUNICATION_FAILURE, "S05");
        List<SeismicReading> readings = simulator.sampleAllAt(Instant.now().plusSeconds(1));
        assertEquals(network.size() - 1, readings.size());
        assertTrue(readings.stream().noneMatch(r -> r.getSensorId().equals("S05")));
    }

    @Test
    void majorEventRaisesManySensorsAndNormalClearsIt() {
        simulator.triggerScenario(ScenarioType.MAJOR_EVENT, null);
        // 8 s after the start the wave has reached the outer zones (30 km at 6 km/s = 5 s).
        long elevated = simulator.sampleAllAt(Instant.now().plusSeconds(8)).stream()
                .filter(r -> r.getAmplitude() >= 0.30).count();
        assertTrue(elevated >= 9, "elevated sensors: " + elevated);
        simulator.triggerScenario(ScenarioType.NORMAL, null);
        assertTrue(simulator.activeEffectDescriptions().isEmpty());
    }

    @Test
    void scenarioTriggersFromManyThreadsWhileRunningAreSafe() throws Exception {
        simulator.start("T2");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch done = new CountDownLatch(40);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ScenarioType type = ScenarioType.values()[i % ScenarioType.values().length];
            pool.submit(() -> {
                try {
                    simulator.triggerScenario(type, null);
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(simulator.isRunning());
        simulator.reset();
        assertFalse(simulator.isRunning());
        assertTrue(simulator.activeEffectDescriptions().isEmpty());
        assertEquals(0, simulator.getReadingsGenerated());
    }
}
