package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.Alert;
import com.seismic.model.AlertSeverity;
import com.seismic.model.AlertType;
import com.seismic.repository.AlertRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** MySQL implementation of {@link AlertRepository}. */
public final class JdbcAlertRepository implements AlertRepository {

    private static final String INSERT = "INSERT INTO alerts (alert_id, event_id, sensor_id, alert_type, severity, "
            + "description, created_at_utc, acknowledged, acknowledged_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String ACKNOWLEDGE = "UPDATE alerts SET acknowledged = ?, acknowledged_at_utc = ? WHERE alert_id = ?";
    private static final String SELECT_RECENT = "SELECT * FROM alerts ORDER BY created_at_utc DESC, alert_id DESC LIMIT ?";

    private final DatabaseConnection database;

    public JdbcAlertRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public void save(Alert alert) {
        JdbcSupport.query(database, "Saving alert " + alert.getAlertId(), connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT)) {
                ps.setString(1, alert.getAlertId());
                ps.setString(2, alert.getEventId());
                ps.setString(3, alert.getSensorId());
                ps.setString(4, alert.getType().name());
                ps.setString(5, alert.getSeverity().name());
                ps.setString(6, JdbcSupport.truncate(alert.getDescription(), 500));
                JdbcSupport.setInstant(ps, 7, alert.getCreatedAt());
                ps.setBoolean(8, alert.isAcknowledged());
                JdbcSupport.setInstant(ps, 9, alert.getAcknowledgedAt());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void updateAcknowledgement(Alert alert) {
        JdbcSupport.query(database, "Acknowledging alert " + alert.getAlertId(), connection -> {
            try (PreparedStatement ps = connection.prepareStatement(ACKNOWLEDGE)) {
                ps.setBoolean(1, alert.isAcknowledged());
                JdbcSupport.setInstant(ps, 2, alert.getAcknowledgedAt());
                ps.setString(3, alert.getAlertId());
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public List<Alert> findRecent(int limit) {
        return JdbcSupport.query(database, "Loading alerts", connection -> {
            List<Alert> alerts = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_RECENT)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        alerts.add(new Alert(rs.getString("alert_id"), rs.getString("event_id"), rs.getString("sensor_id"),
                                JdbcSupport.getInstant(rs, "created_at_utc"), AlertType.valueOf(rs.getString("alert_type")),
                                AlertSeverity.valueOf(rs.getString("severity")), rs.getString("description"),
                                rs.getBoolean("acknowledged"), JdbcSupport.getInstant(rs, "acknowledged_at_utc")));
                    }
                }
            }
            return alerts;
        });
    }
}
