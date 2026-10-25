package com.seismic.database;

import com.seismic.config.ConfigLoader;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC connection settings. Sources, highest priority first:
 * <ol>
 *   <li>environment variables SEISMIC_DB_URL, SEISMIC_DB_USER, SEISMIC_DB_PASSWORD</li>
 *   <li>config/database.properties (git-ignored; copy config/database.properties.example)</li>
 * </ol>
 * No password is ever hard-coded, and {@link #toString()} never prints it.
 */
public record DatabaseConfig(String url, String user, String password) {

    public static final Path DEFAULT_FILE = Path.of("config", "database.properties");

    public boolean isConfigured() {
        return url != null && !url.isBlank();
    }

    public static DatabaseConfig load() {
        return load(ConfigLoader.loadOptional(DEFAULT_FILE), System.getenv());
    }

    static DatabaseConfig load(Properties file, Map<String, String> environment) {
        return new DatabaseConfig(
                pick(environment.get("SEISMIC_DB_URL"), file.getProperty("db.url")),
                pick(environment.get("SEISMIC_DB_USER"), file.getProperty("db.user")),
                pick(environment.get("SEISMIC_DB_PASSWORD"), file.getProperty("db.password")));
    }

    private static String pick(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    /** The URL without any query parameters, for status messages. */
    public String safeTarget() {
        if (!isConfigured()) {
            return "(not configured)";
        }
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    @Override
    public String toString() {
        return "DatabaseConfig[url=" + safeTarget() + ", user=" + user + ", password=" + (password.isEmpty() ? "<empty>" : "****") + "]";
    }
}
