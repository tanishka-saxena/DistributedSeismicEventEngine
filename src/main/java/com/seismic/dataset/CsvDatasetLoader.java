package com.seismic.dataset;

import com.seismic.model.DatasetMetadata;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorNetwork;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads sensor readings from a CSV file (the bundled dataset or a user import).
 *
 * <p>Expected header (order does not matter, names are case-insensitive):
 * {@code reading_id, sensor_id, timestamp_utc, amplitude, frequency_hz, duration_s, noise_level[, scenario_label]}.
 * Lines starting with '#' are comments.
 *
 * <p>A line is <b>rejected</b> if it has the wrong number of columns, no reading/sensor ID, a duplicate
 * reading ID, an unknown sensor, or an unparseable/implausible timestamp. Lines with bad numeric values
 * are <b>kept</b> and flagged INVALID by {@link DataValidator}, so data problems stay visible.
 */
public final class CsvDatasetLoader {

    /** Column name and accepted aliases. */
    private static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();
    static {
        COLUMNS.put("reading_id", List.of("reading_id", "id"));
        COLUMNS.put("sensor_id", List.of("sensor_id", "sensor"));
        COLUMNS.put("timestamp_utc", List.of("timestamp_utc", "timestamp", "time"));
        COLUMNS.put("amplitude", List.of("amplitude"));
        COLUMNS.put("frequency_hz", List.of("frequency_hz", "frequency"));
        COLUMNS.put("duration_s", List.of("duration_s", "duration"));
        COLUMNS.put("noise_level", List.of("noise_level", "noise"));
    }
    private static final List<String> OPTIONAL_LABEL = List.of("scenario_label", "label");
    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd[ ]['T']HH:mm:ss[.SSS]");

    private final DataValidator validator;
    private final Clock clock;

    public CsvDatasetLoader(DataValidator validator, Clock clock) {
        this.validator = validator;
        this.clock = clock;
    }

    public DatasetLoadResult load(Path file, DatasetSourceType sourceType, SensorNetwork network)
            throws IOException, DatasetFormatException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return load(reader, file.getFileName().toString(), sourceType, network);
        }
    }

    public DatasetLoadResult load(Reader source, String fileName, DatasetSourceType sourceType, SensorNetwork network)
            throws IOException, DatasetFormatException {
        BufferedReader reader = source instanceof BufferedReader b ? b : new BufferedReader(source);
        List<SeismicReading> readings = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        Map<String, Integer> columns = null;
        int headerSize = 0;
        int lineNumber = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (columns == null) {
                List<String> header = CsvUtils.parseLine(line);
                columns = mapHeader(header);
                headerSize = header.size();
                continue;
            }
            try {
                SeismicReading parsed = parseRow(line, columns, headerSize, network, seenIds);
                ValidationResult result = validator.validate(parsed);
                if (result.rejected()) {
                    rejected.add(new RejectedRecord(lineNumber, result.summary(), line));
                } else {
                    readings.add(result.applyTo(parsed));
                    seenIds.add(parsed.getReadingId());
                }
            } catch (RowRejectedException e) {
                rejected.add(new RejectedRecord(lineNumber, e.getMessage(), line));
            }
        }
        if (columns == null) {
            throw new DatasetFormatException(fileName + " contains no header row");
        }
        readings.sort(Comparator.comparing(SeismicReading::getTimestamp).thenComparing(SeismicReading::getSensorId));
        return new DatasetLoadResult(buildMetadata(fileName, sourceType, readings, rejected), readings, rejected);
    }

    /** Maps canonical column names to their index; fails if a required column is missing. */
    private Map<String, Integer> mapHeader(List<String> header) throws DatasetFormatException {
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            byName.put(CsvUtils.normaliseHeader(header.get(i)), i);
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> column : COLUMNS.entrySet()) {
            Integer index = findColumn(byName, column.getValue());
            if (index == null) {
                missing.add(column.getKey());
            } else {
                columns.put(column.getKey(), index);
            }
        }
        if (!missing.isEmpty()) {
            throw new DatasetFormatException("Missing required column(s): " + String.join(", ", missing)
                    + ". Expected: " + String.join(", ", COLUMNS.keySet()));
        }
        Integer label = findColumn(byName, OPTIONAL_LABEL);
        if (label != null) {
            columns.put("scenario_label", label);
        }
        return columns;
    }

    private static Integer findColumn(Map<String, Integer> byName, List<String> aliases) {
        for (String alias : aliases) {
            if (byName.containsKey(alias)) {
                return byName.get(alias);
            }
        }
        return null;
    }

    private SeismicReading parseRow(String line, Map<String, Integer> columns, int headerSize,
                                    SensorNetwork network, Set<String> seenIds) throws RowRejectedException {
        List<String> fields;
        try {
            fields = CsvUtils.parseLine(line);
        } catch (IllegalArgumentException e) {
            throw new RowRejectedException("malformed line: " + e.getMessage());
        }
        if (fields.size() != headerSize) {
            throw new RowRejectedException("expected " + headerSize + " columns, found " + fields.size());
        }
        String readingId = field(fields, columns, "reading_id");
        if (readingId.isEmpty()) {
            throw new RowRejectedException("reading_id missing");
        }
        if (seenIds.contains(readingId)) {
            throw new RowRejectedException("duplicate reading_id " + readingId);
        }
        String sensorId = field(fields, columns, "sensor_id").toUpperCase(Locale.ROOT);
        if (sensorId.isEmpty()) {
            throw new RowRejectedException("sensor_id missing");
        }
        if (!network.contains(sensorId)) {
            throw new RowRejectedException("unknown sensor " + sensorId);
        }
        Instant timestamp = parseTimestamp(field(fields, columns, "timestamp_utc"));

        List<String> parseNotes = new ArrayList<>();
        SeismicReading.Builder builder = SeismicReading.builder()
                .readingId(readingId)
                .sensorId(sensorId)
                .timestamp(timestamp)
                .amplitude(number(fields, columns, "amplitude", parseNotes))
                .frequencyHz(number(fields, columns, "frequency_hz", parseNotes))
                .durationSeconds(number(fields, columns, "duration_s", parseNotes))
                .noiseLevel(number(fields, columns, "noise_level", parseNotes))
                .quality(ReadingQuality.UNCHECKED)
                .qualityNote(String.join("; ", parseNotes))
                .source(ReadingSource.DATASET);
        if (columns.containsKey("scenario_label")) {
            builder.scenarioLabel(field(fields, columns, "scenario_label"));
        }
        return builder.build();
    }

    private static String field(List<String> fields, Map<String, Integer> columns, String name) {
        return fields.get(columns.get(name)).trim();
    }

    /** Empty means missing (NaN). Text that is not a number is also NaN, with a note explaining why. */
    private static double number(List<String> fields, Map<String, Integer> columns, String name, List<String> notes) {
        String text = field(fields, columns, name);
        if (text.isEmpty() || text.equalsIgnoreCase("NaN") || text.equalsIgnoreCase("null")) {
            return Double.NaN;
        }
        try {
            double value = Double.parseDouble(text);
            if (Double.isInfinite(value)) {
                notes.add(name + " is infinite");
                return Double.NaN;
            }
            return value;
        } catch (NumberFormatException e) {
            notes.add(name + " '" + text + "' is not a number");
            return Double.NaN;
        }
    }

    /** Accepts ISO-8601 instants (2026-10-01T08:00:00Z) or a local "yyyy-MM-dd HH:mm:ss", interpreted as UTC. */
    static Instant parseTimestamp(String text) throws RowRejectedException {
        if (text.isEmpty()) {
            throw new RowRejectedException("timestamp missing");
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            // not an ISO instant with offset; fall through to the local UTC format
        }
        try {
            return LocalDateTime.parse(text, LOCAL_FORMAT).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new RowRejectedException("invalid timestamp '" + text + "'");
        }
    }

    private DatasetMetadata buildMetadata(String fileName, DatasetSourceType type,
                                          List<SeismicReading> readings, List<RejectedRecord> rejected) {
        int valid = 0;
        int suspicious = 0;
        int invalid = 0;
        for (SeismicReading reading : readings) {
            switch (reading.getQuality()) {
                case VALID -> valid++;
                case SUSPICIOUS -> suspicious++;
                default -> invalid++;
            }
        }
        Instant first = readings.isEmpty() ? null : readings.get(0).getTimestamp();
        Instant last = readings.isEmpty() ? null : readings.get(readings.size() - 1).getTimestamp();
        return new DatasetMetadata(null, fileName, type, clock.instant(), readings.size() + rejected.size(),
                valid, suspicious, invalid, rejected.size(), first, last, "");
    }

    /** Internal signal that one row must be rejected; never escapes this class. */
    static final class RowRejectedException extends Exception {
        RowRejectedException(String message) {
            super(message);
        }
    }
}
