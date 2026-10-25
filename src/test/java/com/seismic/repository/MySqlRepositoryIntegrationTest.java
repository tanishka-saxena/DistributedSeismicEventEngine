package com.seismic.repository;

import com.seismic.database.DatabaseConfig;
import com.seismic.database.DatabaseConnection;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the repository contract against a real MySQL 8 database. Skipped unless SEISMIC_TEST_DB_URL is set.
 *
 * <pre>
 * CREATE DATABASE seismic_engine_test;
 * set SEISMIC_TEST_DB_URL=jdbc:mysql://localhost:3306/seismic_engine_test
 * set SEISMIC_TEST_DB_USER=root
 * set SEISMIC_TEST_DB_PASSWORD=...
 * mvnw.cmd test -Dtest=MySqlRepositoryIntegrationTest
 * </pre>
 * The tests DELETE all rows of the tables, so the database name must contain "test" (safety check).
 */
@EnabledIfEnvironmentVariable(named = "SEISMIC_TEST_DB_URL", matches = ".+")
class MySqlRepositoryIntegrationTest extends RepositoryContractTest {

    @Override
    protected DatabaseConnection database() {
        String url = System.getenv("SEISMIC_TEST_DB_URL");
        assumeTrue(url.toLowerCase().contains("test"), "Refusing to run: the test database name must contain 'test'");
        String user = System.getenv().getOrDefault("SEISMIC_TEST_DB_USER", "root");
        String password = System.getenv().getOrDefault("SEISMIC_TEST_DB_PASSWORD", "");
        return new DatabaseConnection(new DatabaseConfig(url, user, password));
    }
}
