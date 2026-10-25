package com.seismic.repository;

import com.seismic.database.DatabaseConfig;
import com.seismic.database.DatabaseConnection;

/**
 * Runs the repository contract against an in-memory H2 database in MySQL compatibility mode, using the
 * real schema.sql. This always runs, so the SQL is exercised even on machines without MySQL. H2 is not
 * MySQL, so {@link MySqlRepositoryIntegrationTest} remains the authoritative check when MySQL is available.
 */
class H2RepositoryIntegrationTest extends RepositoryContractTest {

    @Override
    protected DatabaseConnection database() {
        return new DatabaseConnection(new DatabaseConfig(
                "jdbc:h2:mem:seismic_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
    }
}
