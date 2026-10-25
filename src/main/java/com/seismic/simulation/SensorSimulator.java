package com.seismic.simulation;

import com.seismic.config.SimulationConfig;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates the distributed sensor network: every sensor is an independent periodic task on a shared
 * {@link ScheduledExecutorService}, and each delivered reading is put on a {@link BlockingQueue} that the
 * processing thread consumes (producer-consumer).
 *
 * <p>Thread-safety design:
 * <ul>
 *   <li>Active scenario effects live in a {@link CopyOnWriteArrayList}: read by every sampling task,
 *       written rarely (button presses).</li>
 *   <li>Each sensor has its own seeded {@link Random}, used only by that sensor's task, so the readings of a
 *       sensor are reproducible regardless of thread scheduling.</li>
 *   <li>Counters are atomics; start/stop/reset are {@code synchronized} so the executor is never created twice.</li>
 * </ul>
 */
public final class SensorSimulator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SensorSimulator.class);
    private static final long SHUTDOWN_WAIT_MILLIS = 2_000;

    private final SimulationConfig config;
    private final SensorNetwork network;
    private final Clock clock;
    private final BlockingQueue<SeismicReading> output;
    private final ReadingGenerator generator = new ReadingGenerator();
    private final ScenarioPlanner planner;
    private final List<ScenarioEffect> activeEffects = new CopyOnWriteArrayList<>();
    private final Map<String, Random> sensorRandoms = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong droppedByQueue = new AtomicLong();
    private final AtomicLong notDelivered = new AtomicLong();
    private Random plannerRandom;
    private ScheduledExecutorService scheduler;
    private volatile String runKey = "R0";

    public SensorSimulator(SimulationConfig config, SensorNetwork network, Clock clock,
                           BlockingQueue<SeismicReading> output) {
        this.config = config;
        this.network = network;
        this.clock = clock;
        this.output = output;
        this.planner = new ScenarioPlanner(config, network);
        resetRandomness();
    }

    /** Starts one periodic sampling task per sensor. {@code runKey} prefixes reading IDs, e.g. "R12". */
    public synchronized void start(String runKey) {
        if (isRunning()) {
            return;
        }
        this.runKey = runKey;
        scheduler = Executors.newScheduledThreadPool(config.threadPoolSize(), namedDaemonThreads());
        long interval = config.sampleIntervalMillis();
        List<Sensor> sensors = network.sensors();
        for (int i = 0; i < sensors.size(); i++) {
            Sensor sensor = sensors.get(i);
            // Stagger the start so sensors do not all report in the same millisecond.
            long initialDelay = i * interval / sensors.size();
            scheduler.scheduleAtFixedRate(() -> sampleSafely(sensor), initialDelay, interval, TimeUnit.MILLISECONDS);
        }
        log.info("Simulator started: {} sensors every {} ms on {} threads", sensors.size(), interval, config.threadPoolSize());
    }

    /** Stops sampling and waits briefly for running tasks to finish. Safe to call more than once. */
    public synchronized void stop() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        scheduler = null;
        log.info("Simulator stopped after {} readings", generated.get());
    }

    /** Stops, clears all scenarios and restores the initial random seeds and counters. */
    public synchronized void reset() {
        stop();
        activeEffects.clear();
        sequence.set(0);
        generated.set(0);
        droppedByQueue.set(0);
        notDelivered.set(0);
        resetRandomness();
    }

    public synchronized boolean isRunning() {
        return scheduler != null && !scheduler.isShutdown();
    }

    /**
     * Activates a scenario starting now. NORMAL clears every active effect.
     *
     * @param targetSensorId sensor chosen by the user, or null to let the planner choose
     * @return the effects that were activated
     */
    public synchronized List<ScenarioEffect> triggerScenario(ScenarioType type, String targetSensorId) {
        if (type == ScenarioType.NORMAL) {
            activeEffects.clear();
            return List.of();
        }
        List<ScenarioEffect> effects = planner.plan(type, clock.instant(), targetSensorId, plannerRandom);
        activeEffects.addAll(effects);
        effects.forEach(e -> log.info("Scenario activated: {}", e.describe()));
        return effects;
    }

    /** Descriptions of effects that are still running. */
    public List<String> activeEffectDescriptions() {
        Instant now = clock.instant();
        List<String> descriptions = new ArrayList<>();
        for (ScenarioEffect effect : activeEffects) {
            if (!effect.isExpired(now)) {
                descriptions.add(effect.describe());
            }
        }
        return descriptions;
    }

    /**
     * Generates one reading per sensor at {@code time} on the calling thread. Used by tests to check the
     * signal model deterministically without starting threads.
     */
    public List<SeismicReading> sampleAllAt(Instant time) {
        List<SeismicReading> readings = new ArrayList<>();
        for (Sensor sensor : network.sensors()) {
            generateFor(sensor, time).ifPresent(readings::add);
        }
        return readings;
    }

    private void sampleSafely(Sensor sensor) {
        try {
            Instant now = clock.instant();
            activeEffects.removeIf(effect -> effect.isExpired(now));
            Optional<SeismicReading> reading = generateFor(sensor, now);
            if (reading.isEmpty()) {
                notDelivered.incrementAndGet();
            } else if (!output.offer(reading.get())) {
                droppedByQueue.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // An exception would silently cancel this sensor's periodic task, so log it and carry on.
            log.error("Sampling failed for sensor {}", sensor.getSensorId(), e);
        }
    }

    private Optional<SeismicReading> generateFor(Sensor sensor, Instant time) {
        String id = runKey + "-" + sensor.getSensorId() + "-" + sequence.incrementAndGet();
        Random random = sensorRandoms.get(sensor.getSensorId());
        Optional<SeismicReading> reading = generator.generate(sensor, time, id, random, activeEffects, ReadingSource.SIMULATION);
        generated.incrementAndGet();
        return reading;
    }

    private void resetRandomness() {
        List<Sensor> sensors = network.sensors();
        for (int i = 0; i < sensors.size(); i++) {
            sensorRandoms.put(sensors.get(i).getSensorId(), new Random(config.randomSeed() + 1000L * (i + 1)));
        }
        plannerRandom = new Random(config.randomSeed());
    }

    private static ThreadFactory namedDaemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "sensor-sim-" + counter.incrementAndGet());
            // Daemon threads never keep the JVM alive if the window is closed unexpectedly.
            thread.setDaemon(true);
            return thread;
        };
    }

    public long getReadingsGenerated() { return generated.get(); }
    /** Readings lost because the processing queue was full (back-pressure). */
    public long getReadingsDroppedByQueue() { return droppedByQueue.get(); }
    /** Readings suppressed by a simulated communication failure. */
    public long getReadingsNotDelivered() { return notDelivered.get(); }

    @Override
    public void close() {
        stop();
    }
}
