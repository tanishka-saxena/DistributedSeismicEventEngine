package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.SimulationRun;
import com.seismic.repository.SimulationRunRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** MySQL implementation of {@link SimulationRunRepository}. */
public final class JdbcSimulationRunRepository implements SimulationRunRepository {

    private static final String INSERT = "INSERT INTO simulation_runs (started_at_utc, random_seed, sample_interval_ms, "
            + "sensor_count) VALUES (?, ?, ?, ?)";
    private static final String FINISH = "UPDATE simulation_runs SET ended_at_utc = ?, readings_processed = ?, "
            + "events_detected = ? WHERE run_id = ?";

    private final DatabaseConnection database;

    public JdbcSimulationRunRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public SimulationRun create(SimulationRun run) {
        return JdbcSupport.query(database, "Starting simulation run", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                JdbcSupport.setInstant(ps, 1, run.startedAt());
                ps.setLong(2, run.randomSeed());
                ps.setInt(3, run.sampleIntervalMillis());
                ps.setInt(4, run.sensorCount());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No run_id was generated");
                    }
                    return run.withId(keys.getLong(1));
                }
            }
        });
    }

    @Override
    public void finish(SimulationRun run) {
        if (run.runId() == null) {
            return;
        }
        JdbcSupport.query(database, "Finishing simulation run", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(FINISH)) {
                JdbcSupport.setInstant(ps, 1, run.endedAt());
                ps.setLong(2, run.readingsProcessed());
                ps.setInt(3, run.eventsDetected());
                ps.setLong(4, run.runId());
                return ps.executeUpdate();
            }
        });
    }
}
