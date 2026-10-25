package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.repository.ReadingRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** MySQL implementation of {@link ReadingRepository}. */
public final class JdbcReadingRepository implements ReadingRepository {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_CONTRIBUTING = 2_000;

    static final String INSERT = "INSERT INTO readings (reading_id, sensor_id, run_id, dataset_id, reading_time_utc, "
            + "amplitude, filtered_amplitude, frequency_hz, duration_s, noise_level, quality, quality_note, source, "
            + "scenario_label) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String COLUMNS = "r.reading_id, r.sensor_id, r.reading_time_utc, r.amplitude, "
            + "r.filtered_amplitude, r.frequency_hz, r.duration_s, r.noise_level, r.quality, r.quality_note, "
            + "r.source, r.scenario_label";
    private static final String RECENT_BY_SENSOR = "SELECT " + COLUMNS + " FROM readings r WHERE r.sensor_id = ? "
            + "ORDER BY r.reading_time_utc DESC, r.id DESC LIMIT ?";
    /**
     * Readings of every participating sensor between its first and last trigger. Joins the association table
     * (which sensors, which time window) with the event (which run or dataset the readings must belong to).
     */
    private static final String CONTRIBUTING = "SELECT " + COLUMNS + " FROM readings r "
            + "JOIN event_sensor_associations a ON a.sensor_id = r.sensor_id "
            + "JOIN detected_events e ON e.event_id = a.event_id "
            + "WHERE a.event_id = ? "
            + "AND r.reading_time_utc BETWEEN a.first_trigger_utc AND a.last_trigger_utc "
            + "AND ((e.run_id IS NULL AND e.dataset_id IS NULL) OR r.run_id = e.run_id OR r.dataset_id = e.dataset_id) "
            + "ORDER BY r.reading_time_utc, r.sensor_id LIMIT " + MAX_CONTRIBUTING;

    private final DatabaseConnection database;

    public JdbcReadingRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public void saveBatch(List<SeismicReading> readings, Long runId) {
        if (readings.isEmpty()) {
            return;
        }
        JdbcSupport.inTransaction(database, "Saving " + readings.size() + " readings", connection -> {
            insertBatch(connection, readings, runId, null);
            return null;
        });
    }

    /** Batched insert used by this repository and, inside its own transaction, by JdbcDatasetRepository. */
    static void insertBatch(Connection connection, List<SeismicReading> readings, Long runId, Long datasetId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
            int pending = 0;
            for (SeismicReading r : readings) {
                ps.setString(1, r.getReadingId());
                ps.setString(2, r.getSensorId());
                JdbcSupport.setLong(ps, 3, runId);
                JdbcSupport.setLong(ps, 4, datasetId);
                JdbcSupport.setInstant(ps, 5, r.getTimestamp());
                JdbcSupport.setDouble(ps, 6, r.getAmplitude());
                JdbcSupport.setDouble(ps, 7, r.getFilteredAmplitude());
                JdbcSupport.setDouble(ps, 8, r.getFrequencyHz());
                JdbcSupport.setDouble(ps, 9, r.getDurationSeconds());
                JdbcSupport.setDouble(ps, 10, r.getNoiseLevel());
                ps.setString(11, r.getQuality().name());
                ps.setString(12, JdbcSupport.truncate(r.getQualityNote(), 255));
                ps.setString(13, r.getSource().name());
                ps.setString(14, JdbcSupport.truncate(r.getScenarioLabel(), 40));
                ps.addBatch();
                if (++pending == BATCH_SIZE) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    @Override
    public List<SeismicReading> findRecentBySensor(String sensorId, int limit) {
        return JdbcSupport.query(database, "Loading readings of " + sensorId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(RECENT_BY_SENSOR)) {
                ps.setString(1, sensorId);
                ps.setInt(2, limit);
                List<SeismicReading> readings = map(ps);
                Collections.reverse(readings);
                return readings;
            }
        });
    }

    @Override
    public List<SeismicReading> findContributingReadings(SeismicEvent event) {
        return JdbcSupport.query(database, "Loading readings of event " + event.getEventId(), connection -> {
            try (PreparedStatement ps = connection.prepareStatement(CONTRIBUTING)) {
                ps.setString(1, event.getEventId());
                return map(ps);
            }
        });
    }

    @Override
    public long count() {
        return JdbcSupport.query(database, "Counting readings", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM readings");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static List<SeismicReading> map(PreparedStatement ps) throws SQLException {
        List<SeismicReading> readings = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                readings.add(SeismicReading.builder()
                        .readingId(rs.getString("reading_id"))
                        .sensorId(rs.getString("sensor_id"))
                        .timestamp(JdbcSupport.getInstant(rs, "reading_time_utc"))
                        .amplitude(JdbcSupport.getDouble(rs, "amplitude"))
                        .filteredAmplitude(JdbcSupport.getDouble(rs, "filtered_amplitude"))
                        .frequencyHz(JdbcSupport.getDouble(rs, "frequency_hz"))
                        .durationSeconds(JdbcSupport.getDouble(rs, "duration_s"))
                        .noiseLevel(JdbcSupport.getDouble(rs, "noise_level"))
                        .quality(ReadingQuality.valueOf(rs.getString("quality")))
                        .qualityNote(rs.getString("quality_note"))
                        .source(ReadingSource.valueOf(rs.getString("source")))
                        .scenarioLabel(rs.getString("scenario_label"))
                        .build());
            }
        }
        return readings;
    }
}
