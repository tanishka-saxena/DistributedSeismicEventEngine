package com.seismic.repository;

import com.seismic.database.DatabaseConnection;
import com.seismic.repository.jdbc.JdbcAlertRepository;
import com.seismic.repository.jdbc.JdbcDatasetRepository;
import com.seismic.repository.jdbc.JdbcEventRepository;
import com.seismic.repository.jdbc.JdbcReadingRepository;
import com.seismic.repository.jdbc.JdbcSensorRepository;
import com.seismic.repository.jdbc.JdbcSimulationRunRepository;
import com.seismic.repository.memory.InMemoryAlertRepository;
import com.seismic.repository.memory.InMemoryDatasetRepository;
import com.seismic.repository.memory.InMemoryEventRepository;
import com.seismic.repository.memory.InMemoryReadingRepository;
import com.seismic.repository.memory.InMemorySensorRepository;
import com.seismic.repository.memory.InMemorySimulationRunRepository;

/**
 * The set of repositories the services use. Services depend only on the interfaces, so the same code
 * runs against MySQL or, in demo mode, against in-memory storage.
 *
 * @param persistent true only when backed by MySQL; the UI uses this to say clearly whether data survives a restart
 */
public record Repositories(SensorRepository sensors, ReadingRepository readings, EventRepository events,
                           AlertRepository alerts, DatasetRepository datasets, SimulationRunRepository runs,
                           boolean persistent, String description) {

    public static Repositories jdbc(DatabaseConnection connection) {
        return new Repositories(
                new JdbcSensorRepository(connection),
                new JdbcReadingRepository(connection),
                new JdbcEventRepository(connection),
                new JdbcAlertRepository(connection),
                new JdbcDatasetRepository(connection),
                new JdbcSimulationRunRepository(connection),
                true,
                "MySQL " + connection.getConfig().safeTarget());
    }

    public static Repositories inMemory(String reason) {
        InMemorySensorRepository sensors = new InMemorySensorRepository();
        return new Repositories(
                sensors,
                new InMemoryReadingRepository(),
                new InMemoryEventRepository(sensors),
                new InMemoryAlertRepository(),
                new InMemoryDatasetRepository(),
                new InMemorySimulationRunRepository(),
                false,
                "DEMO MODE (in memory only, nothing is saved): " + reason);
    }
}
