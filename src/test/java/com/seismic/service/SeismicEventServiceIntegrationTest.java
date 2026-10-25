package com.seismic.service;

import com.seismic.alert.AlertManager;
import com.seismic.config.ProcessingConfig;
import com.seismic.config.SimulationConfig;
import com.seismic.database.DatabaseConfig;
import com.seismic.database.DatabaseConnection;
import com.seismic.database.SchemaInitializer;
import com.seismic.model.Alert;
import com.seismic.model.AlertType;
import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import com.seismic.repository.Repositories;
import com.seismic.simulation.ScenarioType;
import com.seismic.simulation.SensorNetworkFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole live pipeline with real threads: simulator -> queue -> processing thread -> alerts -> JDBC
 * (H2 in MySQL mode) -> listener. Uses a fast simulation (100 ms sampling, short scenarios) and waits for
 * conditions with a deadline instead of fixed sleeps.
 */
class SeismicEventServiceIntegrationTest {

    private static final String URL = "jdbc:h2:mem:service_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private DatabaseConnection database;
    private SeismicEventService service;
    private final List<String> activity = new CopyOnWriteArrayList<>();
    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        database = new DatabaseConnection(new DatabaseConfig(URL, "sa", ""));
        try (Connection c = database.open(); Statement s = c.createStatement()) {
            SchemaInitializer.createTables(c);
            for (String table : List.of("alerts", "event_sensor_associations", "detected_events", "readings",
                    "dataset_metadata", "simulation_runs", "sensors")) {
                s.executeUpdate("DELETE FROM " + table);
            }
        }
        service = newService();
    }

    private SeismicEventService newService() throws Exception {
        SensorNetwork network = SensorNetworkFactory.load(Path.of("data", "sensors.csv"));
        Repositories repositories = Repositories.jdbc(database);
        repositories.sensors().saveAll(network.sensors());
        SimulationConfig d = SimulationConfig.defaults();
        // Fast clock-time version of the default scenarios: 100 ms sampling, 3 s events. The communication
        // failure lasts 7 s so that it exceeds the 5 s offline timeout.
        SimulationConfig fast = new SimulationConfig(d.randomSeed(), 100, 4, 3, d.minorEventPeakAmplitude(), 3,
                d.majorEventPeakAmplitude(), 4, 7, 3, 3, d.attenuationLengthKm(), 60.0);
        Clock clock = Clock.systemUTC();
        SeismicEventService created = new SeismicEventService(ProcessingConfig.defaults(), fast, network, repositories,
                new AlertManager(clock, new IdGenerator("AL", clock.instant()), 500), clock);
        created.addListener(new EngineListener() {
            @Override public void onActivity(String message) { activity.add(message); }
            @Override public void onAlert(Alert alert) { alerts.add(alert); }
        });
        created.loadHistory();
        return created;
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for: " + what);
            Thread.sleep(50); // polling interval while waiting for a condition, not a fixed delay
        }
    }

    @Test
    void majorEventIsDetectedAlertedPersistedAndReloadedAfterRestart() throws Exception {
        service.start();
        waitUntil("first readings", () -> service.getReadingsProcessed() > 30);
        service.triggerScenario(ScenarioType.MAJOR_EVENT, null);
        waitUntil("a confirmed event", () -> service.getEventHistory().stream()
                .anyMatch(e -> e.getStatus() == EventStatus.CONFIRMED || e.getStatus() == EventStatus.RESOLVED));
        waitUntil("an event alert", () -> alerts.stream()
                .anyMatch(a -> a.getType() == AlertType.MAJOR_EVENT || a.getType() == AlertType.SEISMIC_EVENT));
        service.stop();
        assertFalse(service.isRunning());

        // Simulated application restart: new service on the same database.
        service.close();
        service = newService();
        List<SeismicEvent> history = service.getEventHistory();
        assertTrue(history.stream().anyMatch(e -> e.getStatus() == EventStatus.RESOLVED && e.getParticipatingSensorCount() >= 3),
                "event history must survive a restart: " + history);
        assertTrue(service.getAlertManager().history().stream().anyMatch(a -> a.getType() == AlertType.MAJOR_EVENT
                || a.getType() == AlertType.SEISMIC_EVENT), "alerts must survive a restart");
        SeismicEvent stored = history.get(0);
        assertFalse(service.findContributingReadings(stored).isEmpty(), "readings were persisted and joined to the event");
    }

    @Test
    void communicationFailureMakesASensorOfflineAndItRecovers() throws Exception {
        service.start();
        waitUntil("first readings", () -> service.getReadingsProcessed() > 30);
        service.triggerScenario(ScenarioType.COMMUNICATION_FAILURE, "S05");
        waitUntil("S05 offline", () -> alerts.stream().anyMatch(a -> a.getType() == AlertType.COMMUNICATION_FAILURE
                && "S05".equals(a.getSensorId())));
        assertTrue(alerts.stream().noneMatch(a -> a.getType() == AlertType.SENSOR_FAULT && "S05".equals(a.getSensorId())),
                "a silent sensor is OFFLINE, not FAULTY");
        waitUntil("S05 recovered", () -> service.getSensorSnapshot().stream()
                .anyMatch(s -> s.getSensorId().equals("S05") && s.getStatus() == SensorStatus.ACTIVE)
                && alerts.stream().anyMatch(a -> a.getType() == AlertType.SENSOR_RECOVERED));
        service.stop();
    }

    @Test
    void scenariosRequireRunningMonitoringAndResetRestoresSensors() throws Exception {
        assertThrows(IllegalStateException.class, () -> service.triggerScenario(ScenarioType.MINOR_EVENT, null));
        service.start();
        waitUntil("first readings", () -> service.getReadingsProcessed() > 15);
        service.reset();
        assertFalse(service.isRunning());
        assertEquals(0, service.getReadingsProcessed());
        assertTrue(service.getSensorSnapshot().stream().allMatch(s -> s.getStatus() == SensorStatus.ACTIVE
                && s.getReliabilityScore() == 100.0));
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(t -> t.isAlive() && (t.getName().startsWith("sensor-sim") || t.getName().equals("event-processor"))),
                "no background threads left running after reset");
    }
}
