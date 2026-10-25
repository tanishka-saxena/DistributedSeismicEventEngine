package com.seismic.service;

import com.seismic.alert.AlertManager;
import com.seismic.config.ProcessingConfig;
import com.seismic.config.SimulationConfig;
import com.seismic.dataset.DataValidator;
import com.seismic.model.Alert;
import com.seismic.model.AlertType;
import com.seismic.model.EventStatus;
import com.seismic.model.FaultFinding;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatusChange;
import com.seismic.model.SimulationRun;
import com.seismic.processing.EventProcessingPipeline;
import com.seismic.processing.PipelineResult;
import com.seismic.repository.Repositories;
import com.seismic.repository.RepositoryException;
import com.seismic.simulation.ScenarioEffect;
import com.seismic.simulation.ScenarioType;
import com.seismic.simulation.SensorSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Runs live monitoring: simulator -&gt; queue -&gt; processing thread -&gt; alerts -&gt; persistence -&gt; listeners.
 *
 * <p>Threads:
 * <ul>
 *   <li><b>sensor-sim-N</b> (SensorSimulator): produce readings into a bounded {@link BlockingQueue}.</li>
 *   <li><b>event-processor</b> (one thread): the only thread that touches the pipeline, so detection state
 *       needs no locks. It ticks every {@value #TICK_INTERVAL_MILLIS} ms for timeouts and event closing.</li>
 *   <li><b>persistence</b> (one thread): all database writes, in submission order, so a slow database never
 *       blocks detection and an event row is always written before the alerts that refer to it.</li>
 *   <li>Callers (the JavaFX UI via background tasks) use the synchronized lifecycle methods.</li>
 * </ul>
 */
public final class SeismicEventService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SeismicEventService.class);
    static final long TICK_INTERVAL_MILLIS = 500;
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int HISTORY_LIMIT = 300;
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ProcessingConfig processingConfig;
    private final SimulationConfig simulationConfig;
    private final SensorNetwork network;
    private final Repositories repositories;
    private final AlertManager alertManager;
    private final Clock clock;
    private final DataValidator validator;
    private final IdGenerator eventIds;
    private final BlockingQueue<SeismicReading> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final SensorSimulator simulator;
    private final List<EngineListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "persistence"));
    /** Event history (newest last), guarded by its own lock; bounded so it cannot grow forever. */
    private final LinkedHashMap<String, SeismicEvent> eventHistory = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SeismicEvent> eldest) {
            return size() > HISTORY_LIMIT;
        }
    };
    /** Processing-thread only: what was last written for each open event, to avoid rewriting unchanged events. */
    private final Map<String, String> persistedSignatures = new HashMap<>();
    private final AtomicLong readingsProcessed = new AtomicLong();
    private final AtomicInteger isolatedAnomalies = new AtomicInteger();
    private final AtomicInteger eventsDetected = new AtomicInteger();

    private volatile EventProcessingPipeline pipeline;
    private volatile boolean running;
    private volatile SimulationRun currentRun;
    private long readingsAtRunStart;
    private int eventsAtRunStart;
    private ExecutorService processingExecutor;
    /** Processing-thread only: readings waiting to be written in the next batch. */
    private List<SeismicReading> pendingReadings = new ArrayList<>();

    public SeismicEventService(ProcessingConfig processingConfig, SimulationConfig simulationConfig,
                               SensorNetwork network, Repositories repositories, AlertManager alertManager, Clock clock) {
        this.processingConfig = processingConfig;
        this.simulationConfig = simulationConfig;
        this.network = network;
        this.repositories = repositories;
        this.alertManager = alertManager;
        this.clock = clock;
        this.validator = new DataValidator(clock);
        this.eventIds = new IdGenerator("EVL", clock.instant());
        this.simulator = new SensorSimulator(simulationConfig, network, clock, queue);
        this.pipeline = newPipeline();
    }

    private EventProcessingPipeline newPipeline() {
        return new EventProcessingPipeline(processingConfig, network, validator, ReadingSource.SIMULATION, eventIds);
    }

    public void addListener(EngineListener listener) {
        listeners.add(listener);
    }

    public void removeListener(EngineListener listener) {
        listeners.remove(listener);
    }

    /** Loads event and alert history from storage. Call once at start-up, off the JavaFX thread. */
    public void loadHistory() {
        try {
            List<SeismicEvent> events = repositories.events().findRecent(HISTORY_LIMIT);
            synchronized (eventHistory) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    eventHistory.put(events.get(i).getEventId(), events.get(i));
                }
            }
            List<Alert> alerts = new ArrayList<>(repositories.alerts().findRecent(500));
            Collections.reverse(alerts);
            alertManager.loadHistory(alerts);
            log.info("Loaded {} events and {} alerts from {}", events.size(), alerts.size(), repositories.description());
        } catch (RepositoryException e) {
            log.error("Could not load history", e);
            reportProblem("Database", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the simulator and the processing thread. May do one database insert; call off the JavaFX thread. */
    public synchronized void start() {
        if (running) {
            return;
        }
        Instant now = clock.instant();
        SimulationRun run = new SimulationRun(null, now, null, simulationConfig.randomSeed(),
                simulationConfig.sampleIntervalMillis(), network.size(), 0, 0);
        try {
            run = repositories.runs().create(run);
        } catch (RepositoryException e) {
            reportProblem("Database", e.getMessage());
        }
        currentRun = run;
        readingsAtRunStart = readingsProcessed.get();
        eventsAtRunStart = eventsDetected.get();
        queue.clear();
        pipeline.start(now);
        running = true;
        processingExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "event-processor"));
        processingExecutor.execute(this::processLoop);
        simulator.start(run.runId() == null ? "L" + now.getEpochSecond() : "R" + run.runId());
        notifyListeners(l -> l.onMonitoringStateChanged(true));
        activity("Monitoring started" + (run.runId() == null ? "" : " (run " + run.runId() + ")"));
    }

    /** Stops the simulator, lets the processing thread drain the queue and close open events, then stops it. */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        simulator.stop();
        running = false;
        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Processing thread did not stop in time; interrupting it");
                processingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        processingExecutor = null;
        SimulationRun finished = currentRun.finished(clock.instant(), readingsProcessed.get() - readingsAtRunStart,
                eventsDetected.get() - eventsAtRunStart);
        currentRun = null;
        List<Sensor> sensors = network.snapshot();
        persist(() -> {
            repositories.runs().finish(finished);
            repositories.sensors().saveAll(sensors);
        });
        notifyListeners(l -> l.onMonitoringStateChanged(false));
        activity("Monitoring stopped");
    }

    /** Stops monitoring and returns sensors, scenarios, filters and counters to their initial state. History is kept. */
    public synchronized void reset() {
        stop();
        simulator.reset();
        queue.clear();
        network.resetAll();
        pipeline = newPipeline();
        persistedSignatures.clear();
        alertManager.resetDeduplication();
        readingsProcessed.set(0);
        isolatedAnomalies.set(0);
        synchronized (eventHistory) {
            eventHistory.values().removeIf(e -> e.getStatus() == EventStatus.CANDIDATE);
        }
        List<Sensor> sensors = network.snapshot();
        persist(() -> repositories.sensors().saveAll(sensors));
        activity("Simulation reset: all sensors ACTIVE, reliability 100, scenarios cleared");
        notifyListeners(l -> l.onMonitoringStateChanged(false));
    }

    public List<ScenarioEffect> triggerScenario(ScenarioType type, String targetSensorId) {
        if (!running) {
            throw new IllegalStateException("Start monitoring before triggering a scenario");
        }
        List<ScenarioEffect> effects = simulator.triggerScenario(type, targetSensorId);
        if (type == ScenarioType.NORMAL) {
            activity("Scenario: normal background activity (all effects cleared)");
        } else {
            effects.forEach(e -> activity("Scenario: " + e.describe()));
        }
        return effects;
    }

    @Override
    public void close() {
        stop();
        simulator.close();
        persistenceExecutor.shutdown();
        try {
            if (!persistenceExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Pending database writes were abandoned at shutdown");
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ processing thread

    private void processLoop() {
        long lastTick = 0;
        while (running || !queue.isEmpty()) {
            try {
                SeismicReading reading = queue.poll(200, TimeUnit.MILLISECONDS);
                if (reading != null) {
                    handle(pipeline.process(reading));
                }
                long now = clock.millis();
                if (now - lastTick >= TICK_INTERVAL_MILLIS) {
                    handle(pipeline.tick(clock.instant()));
                    flushReadings();
                    lastTick = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                // One bad reading must not kill the processing thread.
                log.error("Processing error", e);
                reportProblem("Processing", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        try {
            handle(pipeline.finish(clock.instant()));
            flushReadings();
        } catch (RuntimeException e) {
            log.error("Error while closing open events", e);
        }
    }

    private void handle(PipelineResult result) {
        SeismicReading reading = result.getReading();
        if (reading != null) {
            readingsProcessed.incrementAndGet();
            pendingReadings.add(reading);
            notifyListeners(l -> l.onReadingProcessed(reading));
        }
        for (SensorStatusChange change : result.getStatusChanges()) {
            Sensor snapshot = network.require(change.sensorId()).copy();
            persist(() -> repositories.sensors().updateState(snapshot));
            notifyListeners(l -> l.onSensorStatusChanged(change));
            alertManager.onSensorStatusChange(change).ifPresent(this::publishAlert);
            activity(change.sensorId() + ": " + change.oldStatus() + " -> " + change.newStatus() + " (" + change.reason() + ")");
        }
        for (FaultFinding finding : result.getFaultFindings()) {
            alertManager.onFaultFinding(finding).ifPresent(this::publishAlert);
            activity("Fault check " + finding.sensorId() + ": " + finding.description());
        }
        for (SeismicEvent event : result.getUpdatedEvents()) {
            handleEvent(event);
        }
        result.getNotes().forEach(note -> log.debug(note));
    }

    private void handleEvent(SeismicEvent event) {
        SimulationRun run = currentRun;
        if (run != null) {
            event.setRunId(run.runId());
        }
        String id = event.getEventId();
        if (event.getStatus() == EventStatus.DISMISSED) {
            isolatedAnomalies.incrementAndGet();
            persistedSignatures.remove(id);
            synchronized (eventHistory) {
                eventHistory.remove(id);
            }
        } else {
            synchronized (eventHistory) {
                eventHistory.put(id, event);
            }
            if (event.getStatus() != EventStatus.CANDIDATE) {
                persistIfChanged(event);
            }
        }
        // Alerts after the event write: the persistence queue is FIFO, so the alert's foreign key is satisfied.
        alertManager.onEventUpdated(event).forEach(this::publishAlert);
        notifyListeners(l -> l.onEventUpdated(event));
    }

    private void persistIfChanged(SeismicEvent event) {
        String signature = event.getStatus() + "/" + event.getSeverity() + "/" + event.getParticipatingSensorCount();
        String previous = persistedSignatures.get(event.getEventId());
        if (signature.equals(previous)) {
            return;
        }
        if (previous == null) {
            eventsDetected.incrementAndGet();
        }
        if (event.getStatus().isFinal()) {
            persistedSignatures.remove(event.getEventId());
        } else {
            persistedSignatures.put(event.getEventId(), signature);
        }
        persist(() -> repositories.events().save(event));
    }

    private void flushReadings() {
        if (pendingReadings.isEmpty()) {
            return;
        }
        List<SeismicReading> batch = pendingReadings;
        pendingReadings = new ArrayList<>();
        SimulationRun run = currentRun;
        Long runId = run == null ? null : run.runId();
        persist(() -> repositories.readings().saveBatch(batch, runId));
    }

    // ------------------------------------------------------------------ alerts and persistence

    private void publishAlert(Alert alert) {
        if (alert.getType() != AlertType.PROCESSING_ERROR) {
            persist(() -> repositories.alerts().save(alert));
        }
        notifyListeners(l -> l.onAlert(alert));
    }

    public void acknowledgeAlert(String alertId) {
        alertManager.acknowledge(alertId).ifPresent(this::publishAcknowledgement);
    }

    public void acknowledgeAllAlerts() {
        alertManager.acknowledgeAll().forEach(this::publishAcknowledgement);
    }

    private void publishAcknowledgement(Alert alert) {
        persist(() -> repositories.alerts().updateAcknowledgement(alert));
        notifyListeners(l -> l.onAlert(alert));
    }

    /** Queues a database write on the persistence thread; failures become a rate-limited alert, not a crash. */
    private void persist(Runnable write) {
        try {
            persistenceExecutor.execute(() -> {
                try {
                    write.run();
                } catch (RepositoryException e) {
                    log.warn("Persistence failed: {}", e.getMessage());
                    reportProblem("Database", e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Write skipped because the application is shutting down");
        }
    }

    private void reportProblem(String source, String message) {
        alertManager.onProcessingError(source, message).ifPresent(alert -> notifyListeners(l -> l.onAlert(alert)));
        activity(source + " problem: " + message);
    }

    private void activity(String message) {
        log.info(message);
        notifyListeners(l -> l.onActivity(message));
    }

    private void notifyListeners(Consumer<EngineListener> call) {
        for (EngineListener listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                log.error("Listener failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ queries (any thread)

    /** Adds events produced outside the live loop (dataset analysis) to the history shown in the UI. */
    public void addExternalEvents(List<SeismicEvent> events) {
        synchronized (eventHistory) {
            events.forEach(e -> eventHistory.put(e.getEventId(), e.copy()));
        }
        events.forEach(e -> notifyListeners(l -> l.onEventUpdated(e.copy())));
    }

    /** Copies of the history, newest first. */
    public List<SeismicEvent> getEventHistory() {
        List<SeismicEvent> list;
        synchronized (eventHistory) {
            list = new ArrayList<>(eventHistory.values());
        }
        list.sort(Comparator.comparing(SeismicEvent::getStartTime).reversed());
        return list.stream().map(SeismicEvent::copy).toList();
    }

    /** Reads stored events (MySQL or memory). May query the database: call off the JavaFX thread. */
    public List<SeismicEvent> loadStoredEvents(int limit) {
        return repositories.events().findRecent(limit);
    }

    /** May query the database: call off the JavaFX thread. */
    public List<SeismicReading> findRecentReadings(String sensorId, int limit) {
        return repositories.readings().findRecentBySensor(sensorId, limit);
    }

    /** Readings kept with the event in memory, or (for events loaded from MySQL) a JOIN query. */
    public List<SeismicReading> findContributingReadings(SeismicEvent event) {
        if (!event.getContributingReadings().isEmpty()) {
            return event.getContributingReadings();
        }
        return repositories.readings().findContributingReadings(event);
    }

    public Map<String, Long> countEventsPerSensor() {
        return repositories.events().countEventsPerSensor();
    }

    public List<Sensor> getSensorSnapshot() {
        return network.snapshot();
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public boolean isRunning() {
        return running;
    }

    public long getReadingsProcessed() {
        return readingsProcessed.get();
    }

    public int getIsolatedAnomalies() {
        return isolatedAnomalies.get();
    }

    public int getQueueSize() {
        return queue.size();
    }

    public long getReadingsNotDelivered() {
        return simulator.getReadingsNotDelivered();
    }

    public List<String> getActiveScenarios() {
        return simulator.activeEffectDescriptions();
    }

    public boolean isPersistent() {
        return repositories.persistent();
    }

    public String getStorageDescription() {
        return repositories.description();
    }

    public SensorNetwork getNetwork() {
        return network;
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
