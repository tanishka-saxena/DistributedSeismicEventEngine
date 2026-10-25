package com.seismic.dataset;

import com.seismic.model.CatalogEarthquake;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a public earthquake catalog in the USGS CSV export format
 * (https://earthquake.usgs.gov/earthquakes/feed/v1.0/csv.php).
 *
 * <p>Required columns: time, latitude, longitude, depth, mag. Optional: magType, id, place.
 * Records are kept as {@link CatalogEarthquake}s only. They are never converted into sensor readings,
 * because a catalog contains no raw sensor measurements.
 */
public final class UsgsCatalogLoader {

    public record CatalogLoadResult(String fileName, List<CatalogEarthquake> earthquakes, List<RejectedRecord> rejected) {
        public CatalogLoadResult {
            earthquakes = List.copyOf(earthquakes);
            rejected = List.copyOf(rejected);
        }
    }

    private static final String[] REQUIRED = {"time", "latitude", "longitude", "depth", "mag"};

    public CatalogLoadResult load(Path file) throws IOException, DatasetFormatException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader, file.getFileName().toString());
        }
    }

    public CatalogLoadResult load(Reader source, String fileName) throws IOException, DatasetFormatException {
        BufferedReader reader = source instanceof BufferedReader b ? b : new BufferedReader(source);
        List<CatalogEarthquake> earthquakes = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        Map<String, Integer> columns = null;
        int headerSize = 0;
        int lineNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            List<String> fields;
            try {
                fields = CsvUtils.parseLine(line);
            } catch (IllegalArgumentException e) {
                rejected.add(new RejectedRecord(lineNumber, "malformed line: " + e.getMessage(), line));
                continue;
            }
            if (columns == null) {
                columns = mapHeader(fields, fileName);
                headerSize = fields.size();
                continue;
            }
            if (fields.size() != headerSize) {
                rejected.add(new RejectedRecord(lineNumber, "expected " + headerSize + " columns, found " + fields.size(), line));
                continue;
            }
            try {
                earthquakes.add(parse(fields, columns, lineNumber));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                rejected.add(new RejectedRecord(lineNumber, e.getMessage(), line));
            }
        }
        if (columns == null) {
            throw new DatasetFormatException(fileName + " contains no header row");
        }
        return new CatalogLoadResult(fileName, earthquakes, rejected);
    }

    private static Map<String, Integer> mapHeader(List<String> header, String fileName) throws DatasetFormatException {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(CsvUtils.normaliseHeader(header.get(i)), i);
        }
        List<String> missing = new ArrayList<>();
        for (String required : REQUIRED) {
            if (!columns.containsKey(required.toLowerCase())) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new DatasetFormatException(fileName + " is not a USGS catalog CSV; missing column(s): "
                    + String.join(", ", missing));
        }
        return columns;
    }

    private static CatalogEarthquake parse(List<String> f, Map<String, Integer> c, int lineNumber) {
        Instant time = Instant.parse(f.get(c.get("time")).trim());
        double latitude = parseNumber(f, c, "latitude");
        double longitude = parseNumber(f, c, "longitude");
        if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("coordinates out of range");
        }
        double depth = parseNumber(f, c, "depth");
        double magnitude = parseNumber(f, c, "mag");
        String magType = optional(f, c, "magtype");
        String id = optional(f, c, "id");
        String place = optional(f, c, "place");
        return new CatalogEarthquake(id.isEmpty() ? "line-" + lineNumber : id, time, latitude, longitude,
                depth, magnitude, magType, place);
    }

    private static double parseNumber(List<String> f, Map<String, Integer> c, String column) {
        String text = f.get(c.get(column)).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(column + " missing");
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " '" + text + "' is not a number");
        }
    }

    private static String optional(List<String> f, Map<String, Integer> c, String column) {
        Integer index = c.get(column);
        return index == null ? "" : f.get(index).trim();
    }
}
