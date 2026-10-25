package com.seismic.repository.jdbc;

import com.seismic.database.DatabaseConnection;
import com.seismic.model.DatasetMetadata;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.SeismicReading;
import com.seismic.repository.DatasetRepository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** MySQL implementation of {@link DatasetRepository}. */
public final class JdbcDatasetRepository implements DatasetRepository {

    private static final String INSERT = "INSERT INTO dataset_metadata (file_name, source_type, loaded_at_utc, "
            + "total_records, valid_records, suspicious_records, invalid_records, rejected_records, first_reading_utc, "
            + "last_reading_utc, notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_ALL = "SELECT * FROM dataset_metadata ORDER BY loaded_at_utc DESC, dataset_id DESC";

    private final DatabaseConnection database;

    public JdbcDatasetRepository(DatabaseConnection database) {
        this.database = database;
    }

    @Override
    public DatasetMetadata saveDataset(DatasetMetadata metadata, List<SeismicReading> readings) {
        return JdbcSupport.inTransaction(database, "Saving dataset " + metadata.fileName(), connection -> {
            long id;
            try (PreparedStatement ps = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, JdbcSupport.truncate(metadata.fileName(), 255));
                ps.setString(2, metadata.sourceType().name());
                JdbcSupport.setInstant(ps, 3, metadata.loadedAt());
                ps.setInt(4, metadata.totalRecords());
                ps.setInt(5, metadata.validRecords());
                ps.setInt(6, metadata.suspiciousRecords());
                ps.setInt(7, metadata.invalidRecords());
                ps.setInt(8, metadata.rejectedRecords());
                JdbcSupport.setInstant(ps, 9, metadata.firstReadingAt());
                JdbcSupport.setInstant(ps, 10, metadata.lastReadingAt());
                ps.setString(11, JdbcSupport.truncate(metadata.notes(), 500));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No dataset_id was generated");
                    }
                    id = keys.getLong(1);
                }
            }
            // Same connection, same transaction: if any reading fails, the metadata row is rolled back too.
            JdbcReadingRepository.insertBatch(connection, readings, null, id);
            return metadata.withId(id);
        });
    }

    @Override
    public List<DatasetMetadata> findAll() {
        return JdbcSupport.query(database, "Loading datasets", connection -> {
            List<DatasetMetadata> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(SELECT_ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new DatasetMetadata(rs.getLong("dataset_id"), rs.getString("file_name"),
                            DatasetSourceType.valueOf(rs.getString("source_type")),
                            JdbcSupport.getInstant(rs, "loaded_at_utc"), rs.getInt("total_records"),
                            rs.getInt("valid_records"), rs.getInt("suspicious_records"), rs.getInt("invalid_records"),
                            rs.getInt("rejected_records"), JdbcSupport.getInstant(rs, "first_reading_utc"),
                            JdbcSupport.getInstant(rs, "last_reading_utc"), rs.getString("notes")));
                }
            }
            return list;
        });
    }
}
