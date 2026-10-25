package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.GeoPoint;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SensorParticipation;
import com.seismic.repository.EventRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MySQL implementation of {@link EventRepository}. */
public final class JdbcEventRepository implements EventRepository {

    private static final String EVENT_VALUES = "status = ?, severity = ?, confidence = ?, agreement_score = ?, "
            + "temporal_score = ?, spatial_score = ?, signal_score = ?, start_time_utc = ?, last_activity_utc = ?, "
            + "resolved_at_utc = ?, origin_sensor_id = ?, origin_latitude = ?, origin_longitude = ?, affected_zone = ?, "
            + "mean_frequency_hz = ?, source = ?, run_id = ?, dataset_id = ?, explanation = ?, updated_at_utc = ?";
    private static final String UPDATE = "UPDATE detected_events SET " + EVENT_VALUES + " WHERE event_id = ?";
    private static final String INSERT = "INSERT INTO detected_events (status, severity, confidence, agreement_score, "
            + "temporal_score, spatial_score, signal_score, start_time_utc, last_activity_utc, resolved_at_utc, "
            + "origin_sensor_id, origin_latitude, origin_longitude, affected_zone, mean_frequency_hz, source, run_id, "
            + "dataset_id, explanation, updated_at_utc, event_id) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String DELETE_ASSOCIATIONS = "DELETE FROM event_sensor_associations WHERE event_id = ?";
    private static final String INSERT_ASSOCIATION = "INSERT INTO event_sensor_associations (event_id, sensor_id, "
            + "is_origin, first_trigger_utc, last_trigger_utc, peak_amplitude, peak_filtered_amplitude, "
            + "first_frequency_hz, distance_from_origin_km, candidate_readings) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_RECENT = "SELECT * FROM detected_events ORDER BY start_time_utc DESC LIMIT ?";
    private static final String SELECT_BY_ID = "SELECT * FROM detected_events WHERE event_id = ?";
    /** Joined with sensors to recover each participant's zone. */
    private static final String SELECT_ASSOCIATIONS = "SELECT a.*, s.zone FROM event_sensor_associations a "
            + "JOIN sensors s ON s.sensor_id = a.sensor_id WHERE a.event_id IN (%s) "
            + "ORDER BY a.event_id, a.first_trigger_utc";
    private static final String COUNT_BY_SEVERITY = "SELECT severity, COUNT(*) AS total FROM detected_events GROUP BY severity";
    /** LEFT JOIN keeps sensors that never took part in an event (count 0). */
    private static final String COUNT_PER_SENSOR = "SELECT s.sensor_id, COUNT(a.event_id) AS total FROM sensors s "
            + "LEFT JOIN event_sensor_associations a ON a.sensor_id = s.sensor_id "
            + "GROUP BY s.sensor_id ORDER BY s.sensor_id";

    private final DatabaseConnection database;

    public JdbcEventRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public void save(SeismicEvent event) {
        JdbcSupport.inTransaction(database, "Saving event " + event.getEventId(), connection -> {
            saveInTransaction(connection, event);
            return null;
        });
    }

    @Override
    public void saveAll(List<SeismicEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        JdbcSupport.inTransaction(database, "Saving " + events.size() + " events", connection -> {
            for (SeismicEvent event : events) {
                saveInTransaction(connection, event);
            }
            return null;
        });
    }

    /** Event row and its associations are written together so they can never disagree. */
    private static void saveInTransaction(Connection connection, SeismicEvent event) throws SQLException {
        if (writeEvent(connection, UPDATE, event) == 0) {
            writeEvent(connection, INSERT, event);
        }
        try (PreparedStatement delete = connection.prepareStatement(DELETE_ASSOCIATIONS)) {
            delete.setString(1, event.getEventId());
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_ASSOCIATION)) {
            for (SensorParticipation p : event.getParticipations()) {
                insert.setString(1, event.getEventId());
                insert.setString(2, p.getSensorId());
                insert.setBoolean(3, p.isOrigin());
                JdbcSupport.setInstant(insert, 4, p.getFirstTrigger());
                JdbcSupport.setInstant(insert, 5, p.getLastTrigger());
                insert.setDouble(6, p.getPeakAmplitude());
                insert.setDouble(7, p.getPeakFilteredAmplitude());
                JdbcSupport.setDouble(insert, 8, p.getFirstFrequencyHz());
                JdbcSupport.setDouble(insert, 9, p.getDistanceFromOriginKm());
                insert.setInt(10, p.getCandidateReadings());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static int writeEvent(Connection connection, String sql, SeismicEvent e) throws SQLException {
        ConfidenceBreakdown c = e.getConfidence();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, e.getStatus().name());
            ps.setString(2, e.getSeverity().name());
            JdbcSupport.setDecimal(ps, 3, c.score(), 2);
            JdbcSupport.setDecimal(ps, 4, c.sensorAgreement(), 3);
            JdbcSupport.setDecimal(ps, 5, c.temporalConsistency(), 3);
            JdbcSupport.setDecimal(ps, 6, c.spatialConsistency(), 3);
            JdbcSupport.setDecimal(ps, 7, c.signalScore(), 3);
            JdbcSupport.setInstant(ps, 8, e.getStartTime());
            JdbcSupport.setInstant(ps, 9, e.getLastActivityTime());
            JdbcSupport.setInstant(ps, 10, e.getResolvedAt());
            ps.setString(11, e.getOriginSensorId());
            JdbcSupport.setDecimal(ps, 12, e.getOriginLocation().latitude(), 6);
            JdbcSupport.setDecimal(ps, 13, e.getOriginLocation().longitude(), 6);
            ps.setString(14, JdbcSupport.truncate(e.getAffectedZoneSummary(), 120));
            JdbcSupport.setDouble(ps, 15, e.getMeanFrequencyHz());
            ps.setString(16, e.getSource().name());
            JdbcSupport.setLong(ps, 17, e.getRunId());
            JdbcSupport.setLong(ps, 18, e.getDatasetId());
            ps.setString(19, String.join("\n", e.getExplanation()));
            JdbcSupport.setInstant(ps, 20, Instant.now());
            ps.setString(21, e.getEventId());
            return ps.executeUpdate();
        }
    }

    @Override
    public List<SeismicEvent> findRecent(int limit) {
        return JdbcSupport.query(database, "Loading event history", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_RECENT)) {
                ps.setInt(1, limit);
                return loadWithParticipations(connection, ps);
            }
        });
    }

    @Override
    public Optional<SeismicEvent> findById(String eventId) {
        return JdbcSupport.query(database, "Loading event " + eventId, connection -> {
            try (PreparedStatement ps = connection.prepareStatement(SELECT_BY_ID)) {
                ps.setString(1, eventId);
                return loadWithParticipations(connection, ps).stream().findFirst();
            }
        });
    }

    @Override
    public Map<EventSeverity, Long> countBySeverity() {
        return JdbcSupport.query(database, "Counting events", connection -> {
            Map<EventSeverity, Long> counts = new EnumMap<>(EventSeverity.class);
            Arrays.stream(EventSeverity.values()).forEach(s -> counts.put(s, 0L));
            try (PreparedStatement ps = connection.prepareStatement(COUNT_BY_SEVERITY);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(EventSeverity.valueOf(rs.getString("severity")), rs.getLong("total"));
                }
            }
            return counts;
        });
    }

    @Override
    public Map<String, Long> countEventsPerSensor() {
        return JdbcSupport.query(database, "Counting events per sensor", connection -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            try (PreparedStatement ps = connection.prepareStatement(COUNT_PER_SENSOR);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString("sensor_id"), rs.getLong("total"));
                }
            }
            return counts;
        });
    }

    private static List<SeismicEvent> loadWithParticipations(Connection connection, PreparedStatement eventQuery)
            throws SQLException {
        Map<String, SeismicEvent> events = new LinkedHashMap<>();
        try (ResultSet rs = eventQuery.executeQuery()) {
            while (rs.next()) {
                SeismicEvent event = mapEvent(rs);
                events.put(event.getEventId(), event);
            }
        }
        if (events.isEmpty()) {
            return List.of();
        }
        String sql = String.format(SELECT_ASSOCIATIONS, JdbcSupport.placeholders(events.size()));
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int index = 1;
            for (String id : events.keySet()) {
                ps.setString(index++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    events.get(rs.getString("event_id")).restoreParticipation(new SensorParticipation(
                            rs.getString("sensor_id"), rs.getString("zone"),
                            JdbcSupport.getDouble(rs, "distance_from_origin_km"), rs.getBoolean("is_origin"),
                            JdbcSupport.getInstant(rs, "first_trigger_utc"), JdbcSupport.getInstant(rs, "last_trigger_utc"),
                            rs.getDouble("peak_amplitude"), rs.getDouble("peak_filtered_amplitude"),
                            JdbcSupport.getDouble(rs, "first_frequency_hz"), rs.getInt("candidate_readings")));
                }
            }
        }
        return new ArrayList<>(events.values());
    }

    private static SeismicEvent mapEvent(ResultSet rs) throws SQLException {
        double lat = JdbcSupport.getDouble(rs, "origin_latitude");
        double lon = JdbcSupport.getDouble(rs, "origin_longitude");
        SeismicEvent event = new SeismicEvent(rs.getString("event_id"), ReadingSource.valueOf(rs.getString("source")),
                rs.getString("origin_sensor_id"),
                Double.isNaN(lat) || Double.isNaN(lon) ? GeoPoint.UNKNOWN : new GeoPoint(lat, lon),
                JdbcSupport.getInstant(rs, "start_time_utc"));
        event.setLastActivityTime(JdbcSupport.getInstant(rs, "last_activity_utc"));
        event.setResolvedAt(JdbcSupport.getInstant(rs, "resolved_at_utc"));
        event.setStatus(EventStatus.valueOf(rs.getString("status")));
        event.setSeverity(EventSeverity.valueOf(rs.getString("severity")));
        event.setConfidence(new ConfidenceBreakdown(rs.getDouble("agreement_score"),
                JdbcSupport.getOptional(rs, "temporal_score"), JdbcSupport.getOptional(rs, "spatial_score"),
                rs.getDouble("signal_score"), rs.getDouble("confidence")));
        event.setStoredMeanFrequencyHz(JdbcSupport.getDouble(rs, "mean_frequency_hz"));
        event.setRunId(JdbcSupport.getLong(rs, "run_id"));
        event.setDatasetId(JdbcSupport.getLong(rs, "dataset_id"));
        String explanation = rs.getString("explanation");
        if (explanation != null && !explanation.isBlank()) {
            event.replaceExplanation(Arrays.asList(explanation.split("\n")));
        }
        return event;
    }
}
