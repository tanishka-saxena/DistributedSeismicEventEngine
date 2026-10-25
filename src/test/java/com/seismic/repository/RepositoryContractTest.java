package com.seismic.repository;

import com.seismic.TestData;
import com.seismic.database.DatabaseConnection;
import com.seismic.database.SchemaInitializer;
import com.seismic.model.Alert;
import com.seismic.model.AlertSeverity;
import com.seismic.model.AlertType;
import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.DatasetMetadata;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;
import com.seismic.model.SimulationRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every JDBC repository must have. Subclasses supply the database: H2 in MySQL mode (always runs)
 * or a real MySQL test database (runs only when configured).
 */
abstract class RepositoryContractTest {

    protected abstract DatabaseConnection database();

    private DatabaseConnection database;
    private Repositories repositories;
    private SensorNetwork network;

    @BeforeEach
    void prepareDatabase() throws Exception {
        database = database();
        try (Connection connection = database.open(); Statement statement = connection.createStatement()) {
            SchemaInitializer.createTables(connection);
            for (String table : List.of("alerts", "event_sensor_associations", "detected_events", "readings",
                    "dataset_metadata", "simulation_runs", "sensors")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
        }
        repositories = Repositories.jdbc(database);
        network = TestData.network();
        repositories.sensors().saveAll(network.sensors());
    }

    private SimulationRun newRun() {
        return repositories.runs().create(new SimulationRun(null, TestData.T0, null, 42, 1000, network.size(), 0, 0));
    }

    private static List<SeismicReading> readings(String sensorId, int fromSecond, int count, double amplitude) {
        List<SeismicReading> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(TestData.builder("R-" + sensorId + "-" + (fromSecond + i), sensorId, fromSecond + i, amplitude)
                    .quality(ReadingQuality.VALID).filteredAmplitude(amplitude).build());
        }
        return list;
    }

    private SeismicEvent event(String id, Long runId) {
        SeismicEvent event = new SeismicEvent(id, ReadingSource.SIMULATION, "A1",
                network.require("A1").getLocation(), TestData.at(10));
        for (String sensorId : List.of("A1", "A2", "A3")) {
            Sensor sensor = network.require(sensorId);
            double distance = event.getOriginLocation().distanceKm(sensor.getLocation());
            event.addCandidate(TestData.valid("C-" + sensorId + "-10", sensorId, 10, 0.6), sensor.getZone(), distance);
            event.addCandidate(TestData.valid("C-" + sensorId + "-12", sensorId, 12, 0.7), sensor.getZone(), distance);
        }
        event.setStatus(EventStatus.CONFIRMED);
        event.setSeverity(EventSeverity.MODERATE);
        event.setConfidence(new ConfidenceBreakdown(1.0, OptionalDouble.of(0.9), OptionalDouble.empty(), 0.8, 88.5));
        event.addExplanation("first line");
        event.addExplanation("second line");
        event.setRunId(runId);
        return event;
    }

    @Test
    void sensorsAreUpsertedAndStateIsUpdated() {
        Sensor a1 = network.require("A1");
        a1.setStatus(SensorStatus.FAULTY);
        a1.adjustReliability(-30);
        a1.setLastHeartbeat(TestData.at(5));
        repositories.sensors().updateState(a1);
        repositories.sensors().saveAll(network.sensors());
        List<Sensor> stored = repositories.sensors().findAll();
        assertEquals(network.size(), stored.size(), "saving twice must not duplicate sensors");
        Sensor loaded = stored.stream().filter(s -> s.getSensorId().equals("A1")).findFirst().orElseThrow();
        assertEquals(SensorStatus.FAULTY, loaded.getStatus());
        assertEquals(70.0, loaded.getReliabilityScore(), 1e-9);
        assertEquals(TestData.at(5), loaded.getLastHeartbeat());
        assertEquals(network.require("A1").getLocation().latitude(), loaded.getLocation().latitude(), 1e-6);
    }

    @Test
    void readingsAreSavedInBatchesAndQueriedPerSensor() {
        SimulationRun run = newRun();
        List<SeismicReading> batch = new ArrayList<>(readings("A1", 0, 10, 0.1));
        batch.add(TestData.builder("R-missing", "A2", 3, Double.NaN).quality(ReadingQuality.INVALID)
                .qualityNote("amplitude missing").build());
        repositories.readings().saveBatch(batch, run.runId());

        List<SeismicReading> recent = repositories.readings().findRecentBySensor("A1", 5);
        assertEquals(List.of("R-A1-5", "R-A1-6", "R-A1-7", "R-A1-8", "R-A1-9"),
                recent.stream().map(SeismicReading::getReadingId).toList(), "newest five, oldest first");
        SeismicReading missing = repositories.readings().findRecentBySensor("A2", 1).get(0);
        assertTrue(Double.isNaN(missing.getAmplitude()), "NULL in the database is NaN in Java");
        assertEquals(ReadingQuality.INVALID, missing.getQuality());
        assertEquals(11, repositories.readings().count());
    }

    @Test
    void eventsRoundTripWithAssociationsConfidenceAndExplanation() {
        SimulationRun run = newRun();
        repositories.events().save(event("EV-1", run.runId()));

        SeismicEvent loaded = repositories.events().findById("EV-1").orElseThrow();
        assertEquals(EventStatus.CONFIRMED, loaded.getStatus());
        assertEquals(EventSeverity.MODERATE, loaded.getSeverity());
        assertEquals(3, loaded.getParticipatingSensorCount());
        assertEquals(88.5, loaded.getConfidenceScore(), 1e-9);
        assertEquals(0.9, loaded.getConfidence().temporalConsistency().orElseThrow(), 1e-9);
        assertTrue(loaded.getConfidence().spatialConsistency().isEmpty(), "unavailable factor stays unavailable");
        assertEquals(List.of("first line", "second line"), loaded.getExplanation());
        assertEquals(0.7, loaded.getParticipation("A2").getPeakAmplitude(), 1e-9);
        assertEquals("A", loaded.getAffectedZoneSummary(), "zone recovered through the JOIN with sensors");
        assertEquals(TestData.at(12), loaded.getLastActivityTime());
    }

    @Test
    void savingAnEventAgainReplacesItsAssociations() {
        SimulationRun run = newRun();
        SeismicEvent event = event("EV-2", run.runId());
        repositories.events().save(event);
        Sensor a4 = network.require("A4");
        event.addCandidate(TestData.valid("C-A4", "A4", 13, 0.5), a4.getZone(),
                event.getOriginLocation().distanceKm(a4.getLocation()));
        event.setStatus(EventStatus.RESOLVED);
        event.setSeverity(EventSeverity.MAJOR);
        repositories.events().save(event);

        SeismicEvent loaded = repositories.events().findById("EV-2").orElseThrow();
        assertEquals(4, loaded.getParticipatingSensorCount());
        assertEquals(EventStatus.RESOLVED, loaded.getStatus());
        assertEquals(1, repositories.events().findRecent(10).size());
        Map<EventSeverity, Long> bySeverity = repositories.events().countBySeverity();
        assertEquals(1L, bySeverity.get(EventSeverity.MAJOR));
        assertEquals(0L, bySeverity.get(EventSeverity.MINOR));
        Map<String, Long> perSensor = repositories.events().countEventsPerSensor();
        assertEquals(1L, perSensor.get("A4"));
        assertEquals(0L, perSensor.get("B1"), "LEFT JOIN keeps sensors without events");
    }

    @Test
    void contributingReadingsComeFromTheEventRunAndTimeWindow() {
        SimulationRun run = newRun();
        SimulationRun otherRun = newRun();
        repositories.readings().saveBatch(readings("A1", 5, 10, 0.6), run.runId());      // t=5..14
        repositories.readings().saveBatch(readings("B1", 10, 3, 0.6), run.runId());      // not a participant
        repositories.readings().saveBatch(readings("A2", 10, 3, 0.6), otherRun.runId()); // other run
        repositories.events().save(event("EV-3", run.runId()));

        List<SeismicReading> contributing = repositories.readings()
                .findContributingReadings(repositories.events().findById("EV-3").orElseThrow());
        assertEquals(List.of("R-A1-10", "R-A1-11", "R-A1-12"),
                contributing.stream().map(SeismicReading::getReadingId).toList());
    }

    @Test
    void alertsAreSavedAcknowledgedAndListedNewestFirst() {
        SimulationRun run = newRun();
        repositories.events().save(event("EV-4", run.runId()));
        Alert first = Alert.create("AL-1", "EV-4", "A1", TestData.at(1), AlertType.SEISMIC_EVENT,
                AlertSeverity.WARNING, "event confirmed");
        Alert second = Alert.create("AL-2", null, "B1", TestData.at(2), AlertType.COMMUNICATION_FAILURE,
                AlertSeverity.WARNING, "B1 offline");
        repositories.alerts().save(first);
        repositories.alerts().save(second);
        repositories.alerts().updateAcknowledgement(first.acknowledge(TestData.at(3)));

        List<Alert> recent = repositories.alerts().findRecent(10);
        assertEquals(List.of("AL-2", "AL-1"), recent.stream().map(Alert::getAlertId).toList());
        assertTrue(recent.get(1).isAcknowledged());
        assertEquals(TestData.at(3), recent.get(1).getAcknowledgedAt());
        assertFalse(recent.get(0).isAcknowledged());
        assertEquals("EV-4", recent.get(1).getEventId());
    }

    @Test
    void datasetIsSavedInOneTransactionOrNotAtAll() {
        List<SeismicReading> good = readings("A1", 0, 5, 0.1);
        DatasetMetadata metadata = new DatasetMetadata(null, "good.csv", DatasetSourceType.IMPORTED, TestData.T0,
                5, 5, 0, 0, 0, TestData.at(0), TestData.at(4), "test");
        DatasetMetadata saved = repositories.datasets().saveDataset(metadata, good);
        assertTrue(saved.datasetId() != null && saved.datasetId() > 0);
        long readingsBefore = repositories.readings().count();

        // The last reading references a sensor that does not exist: the foreign key fails mid-batch.
        List<SeismicReading> bad = new ArrayList<>(readings("A1", 10, 3, 0.1));
        bad.add(TestData.builder("R-bad", "NOPE", 20, 0.1).quality(ReadingQuality.VALID).build());
        DatasetMetadata badMetadata = new DatasetMetadata(null, "bad.csv", DatasetSourceType.IMPORTED, TestData.T0,
                4, 4, 0, 0, 0, null, null, "");
        assertThrows(RepositoryException.class, () -> repositories.datasets().saveDataset(badMetadata, bad));

        assertEquals(1, repositories.datasets().findAll().size(), "metadata of the failed dataset was rolled back");
        assertEquals(readingsBefore, repositories.readings().count(), "no reading of the failed dataset was kept");
    }

    @Test
    void historySurvivesANewRepositoryInstanceLikeAnApplicationRestart() {
        SimulationRun run = newRun();
        repositories.events().save(event("EV-5", run.runId()));
        repositories.runs().finish(run.finished(TestData.at(60), 100, 1));
        Repositories afterRestart = Repositories.jdbc(database);
        assertEquals("EV-5", afterRestart.events().findRecent(5).get(0).getEventId());
    }
}
