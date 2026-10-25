package com.seismic.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Opens JDBC connections with {@link DriverManager}. A new connection per repository operation keeps the
 * code simple and is fast enough for this workload (a few batched writes per second); a connection pool
 * such as HikariCP would be the next step for a production system.
 */
public final class DatabaseConnection {

    private static final int VALIDATION_TIMEOUT_SECONDS = 3;
    private final DatabaseConfig config;

    public DatabaseConnection(DatabaseConfig config) {
        this.config = Objects.requireNonNull(config);
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("Database URL is not configured");
        }
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    /** Opens a connection and checks that the server answers. */
    public void verify() throws SQLException {
        try (Connection connection = open()) {
            if (!connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                throw new SQLException("Connection to " + config.safeTarget() + " is not valid");
            }
        }
    }

    public DatabaseConfig getConfig() {
        return config;
    }

    /**
     * Turns common MySQL errors into advice for the user. Driver messages never contain the password,
     * but they can contain the user name, which is acceptable for a local desktop application.
     */
    public static String friendlyMessage(SQLException e, DatabaseConfig config) {
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        int code = e.getErrorCode();
        if (code == 1045 || state.equals("28000")) {
            return "Access denied for user '" + config.user() + "'. Check db.user / db.password in config/database.properties.";
        }
        if (code == 1049) {
            return "Database does not exist. Run: mysql -u root -p < src/main/resources/db/schema.sql";
        }
        if (state.startsWith("08")) {
            return "Cannot reach MySQL at " + config.safeTarget() + ". Is the MySQL server running?";
        }
        return "Database error (" + (state.isEmpty() ? "no SQL state" : "SQL state " + state) + "): " + e.getMessage();
    }
}
