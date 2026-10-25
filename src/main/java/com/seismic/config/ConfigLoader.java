package com.seismic.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Loads optional overrides from config/engine.properties (relative to the working directory). */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    public static final Path DEFAULT_ENGINE_CONFIG = Path.of("config", "engine.properties");

    private ConfigLoader() {
    }

    /** Returns the properties in {@code file}, or empty properties if the file does not exist. */
    public static Properties loadOptional(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            log.info("No {} found, using built-in defaults", file);
            return properties;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            log.info("Loaded {} setting(s) from {}", properties.size(), file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
        return properties;
    }
}
