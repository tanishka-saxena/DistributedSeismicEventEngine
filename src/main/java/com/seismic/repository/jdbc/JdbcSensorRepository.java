package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.GeoPoint;
import com.seismic.model.Sensor;
import com.seismic.model.SensorStatus;
import com.seismic.repository.SensorRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** MySQL implementation of {@link SensorRepository}. */
public final class JdbcSensorRepository implements SensorRepository {

    private static final String UPDATE_ALL = "UPDATE sensors SET sensor_name = ?, zone = ?, latitude = ?, longitude = ?, "
            + "status = ?, reliability_score = ?, last_heartbeat_utc = ?, updated_at_utc = ? WHERE sensor_id = ?";
    private static final String INSERT = "INSERT INTO sensors (sensor_name, zone, latitude, longitude, status, "
            + "reliability_score, last_heartbeat_utc, updated_at_utc, sensor_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String UPDATE_STATE = "UPDATE sensors SET status = ?, reliability_score = ?, "
            + "last_heartbeat_utc = ?, updated_at_utc = ? WHERE sensor_id = ?";
    private static final String SELECT_ALL = "SELECT sensor_id, sensor_name, zone, latitude, longitude, status, "
            + "reliability_score, last_heartbeat_utc FROM sensors ORDER BY sensor_id";

    private final DatabaseConnection database;

    public JdbcSensorRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public void saveAll(Collection<Sensor> sensors) {
        JdbcSupport.inTransaction(database, "Saving sensors", connection -> {
            for (Sensor sensor : sensors) {
                // Portable "upsert": update first; insert only if no row was updated.
                if (write(connection, UPDATE_ALL, sensor) == 0) {
                    write(connection, INSERT, sensor);
                }
            }
            return null;
        });
    }

    private static int write(Connection connection, String sql, Sensor sensor) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sensor.getName());
            ps.setString(2, sensor.getZone());
            JdbcSupport.setDecimal(ps, 3, sensor.getLocation().latitude(), 6);
            JdbcSupport.setDecimal(ps, 4, sensor.getLocation().longitude(), 6);
            ps.setString(5, sensor.getStatus().name());
            JdbcSupport.setDecimal(ps, 6, sensor.getReliabilityScore(), 2);
            JdbcSupport.setInstant(ps, 7, sensor.getLastHeartbeat());
            JdbcSupport.setInstant(ps, 8, Instant.now());
            ps.setString(9, sensor.getSensorId());
            return ps.executeUpdate();
        }
    }

    @Override
    public void updateState(Sensor sensor) {
        JdbcSupport.query(database, "Updating sensor " + sensor.getSensorId(), connection -> {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_STATE)) {
                ps.setString(1, sensor.getStatus().name());
                JdbcSupport.setDecimal(ps, 2, sensor.getReliabilityScore(), 2);
                JdbcSupport.setInstant(ps, 3, sensor.getLastHeartbeat());
                JdbcSupport.setInstant(ps, 4, Instant.now());
                ps.setString(5, sensor.getSensorId());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public List<Sensor> findAll() {
        return JdbcSupport.query(database, "Loading sensors", connection -> {
            List<Sensor> sensors = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double lat = JdbcSupport.getDouble(rs, "latitude");
                    double lon = JdbcSupport.getDouble(rs, "longitude");
                    Sensor sensor = new Sensor(rs.getString("sensor_id"), rs.getString("sensor_name"), rs.getString("zone"),
                            Double.isNaN(lat) || Double.isNaN(lon) ? GeoPoint.UNKNOWN : new GeoPoint(lat, lon));
                    sensor.setStatus(SensorStatus.valueOf(rs.getString("status")));
                    sensor.setReliabilityScore(rs.getDouble("reliability_score"));
                    sensor.setLastHeartbeat(JdbcSupport.getInstant(rs, "last_heartbeat_utc"));
                    sensors.add(sensor);
                }
            }
            return sensors;
        });
    }
}
