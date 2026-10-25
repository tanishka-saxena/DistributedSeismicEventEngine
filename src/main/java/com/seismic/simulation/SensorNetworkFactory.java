package com.seismic.simulation;

import com.seismic.dataset.CsvUtils;
import com.seismic.model.GeoPoint;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the virtual sensor network from data/sensors.csv, so the network layout is data rather
 * than hard-coded arrays. Columns: sensor_id, sensor_name, zone, latitude, longitude.
 */
public final class SensorNetworkFactory {

    public static final Path DEFAULT_SENSOR_FILE = Path.of("data", "sensors.csv");
    private static final String[] REQUIRED = {"sensor_id", "sensor_name", "zone", "latitude", "longitude"};

    private SensorNetworkFactory() {
    }

    public static SensorNetwork loadDefault() throws IOException {
        return load(DEFAULT_SENSOR_FILE);
    }

    public static SensorNetwork load(Path file) throws IOException {
        List<Sensor> sensors = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = null;
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> fields = CsvUtils.parseLine(line);
                if (columns == null) {
                    columns = indexHeader(fields, file);
                    continue;
                }
                sensors.add(parseSensor(fields, columns, file, lineNumber));
            }
        }
        if (sensors.size() < 3) {
            throw new IOException(file + " must define at least 3 sensors, found " + sensors.size());
        }
        return new SensorNetwork(sensors);
    }

    private static Map<String, Integer> indexHeader(List<String> header, Path file) throws IOException {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(CsvUtils.normaliseHeader(header.get(i)), i);
        }
        for (String required : REQUIRED) {
            if (!columns.containsKey(required)) {
                throw new IOException(file + " is missing column '" + required + "'");
            }
        }
        return columns;
    }

    private static Sensor parseSensor(List<String> fields, Map<String, Integer> columns, Path file, int lineNumber)
            throws IOException {
        try {
            String latitude = fields.get(columns.get("latitude")).trim();
            String longitude = fields.get(columns.get("longitude")).trim();
            GeoPoint location = latitude.isEmpty() || longitude.isEmpty()
                    ? GeoPoint.UNKNOWN
                    : new GeoPoint(Double.parseDouble(latitude), Double.parseDouble(longitude));
            return new Sensor(fields.get(columns.get("sensor_id")),
                    fields.get(columns.get("sensor_name")),
                    fields.get(columns.get("zone")),
                    location);
        } catch (RuntimeException e) {
            throw new IOException(file + " line " + lineNumber + ": " + e.getMessage(), e);
        }
    }
}
