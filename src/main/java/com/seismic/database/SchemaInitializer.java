package com.seismic.database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes the CREATE TABLE statements of {@code /db/schema.sql}. The statements use IF NOT EXISTS, so
 * running this at every start-up is safe. CREATE DATABASE and USE are skipped: the JDBC URL already
 * selects the database (and creating databases usually needs more privileges).
 */
public final class SchemaInitializer {

    public static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private SchemaInitializer() {
    }

    public static int createTables(Connection connection) throws SQLException {
        List<String> statements = tableStatements(readSchema());
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
        return statements.size();
    }

    static String readSchema() {
        try (InputStream in = SchemaInitializer.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + SCHEMA_RESOURCE, e);
        }
    }

    /** Splits the script on ';' after removing '--' comment lines, keeping only table statements. */
    static List<String> tableStatements(String script) {
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        List<String> statements = new ArrayList<>();
        for (String part : withoutComments.toString().split(";")) {
            String sql = part.trim();
            String upper = sql.toUpperCase(Locale.ROOT);
            if (!sql.isEmpty() && !upper.startsWith("CREATE DATABASE") && !upper.startsWith("USE ")) {
                statements.add(sql);
            }
        }
        return statements;
    }
}
