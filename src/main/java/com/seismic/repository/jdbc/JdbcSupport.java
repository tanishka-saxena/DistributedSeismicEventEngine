package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.repository.RepositoryException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.OptionalDouble;

/**
 * Shared JDBC helpers.
 * <ul>
 *   <li>Time: Java uses {@link Instant}; the database stores UTC wall-clock time in DATETIME(3).
 *       {@link LocalDateTime} values are passed to the driver, so no time-zone conversion happens in between.</li>
 *   <li>Missing numbers: Java uses NaN; the database uses NULL.</li>
 *   <li>Transactions: {@link #inTransaction} commits on success and rolls back on any SQLException.</li>
 * </ul>
 */
final class JdbcSupport {

    /** A unit of database work that may throw SQLException. */
    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private JdbcSupport() {
    }

    /** Runs {@code work} in one transaction: everything is committed, or nothing is. */
    static <T> T inTransaction(DatabaseConnection database, String action, SqlWork<T> work) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RepositoryException(action + " failed: " + DatabaseConnection.friendlyMessage(e, database.getConfig()), e);
        }
    }

    /** Runs a single statement or query in auto-commit mode. */
    static <T> T query(DatabaseConnection database, String action, SqlWork<T> work) {
        try (Connection connection = database.open()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new RepositoryException(action + " failed: " + DatabaseConnection.friendlyMessage(e, database.getConfig()), e);
        }
    }

    static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setObject(index, LocalDateTime.ofInstant(value, ZoneOffset.UTC));
        }
    }

    static Instant getInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static void setDouble(PreparedStatement ps, int index, double value) throws SQLException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            ps.setNull(index, Types.DOUBLE);
        } else {
            ps.setDouble(index, value);
        }
    }

    static double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? Double.NaN : value;
    }

    /** For DECIMAL columns: rounds to the column's scale; NaN becomes NULL. */
    static void setDecimal(PreparedStatement ps, int index, double value, int scale) throws SQLException {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            ps.setNull(index, Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP));
        }
    }

    static void setDecimal(PreparedStatement ps, int index, OptionalDouble value, int scale) throws SQLException {
        setDecimal(ps, index, value.isPresent() ? value.getAsDouble() : Double.NaN, scale);
    }

    static OptionalDouble getOptional(ResultSet rs, String column) throws SQLException {
        double value = getDouble(rs, column);
        return Double.isNaN(value) ? OptionalDouble.empty() : OptionalDouble.of(value);
    }

    static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    /** "?, ?, ?" for an IN clause with {@code count} parameters. */
    static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
