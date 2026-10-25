package com.seismic.dataset;

import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Writes processed readings and detected events to CSV files. */
public final class CsvExporter {

    public static final String READINGS_HEADER = "reading_id,sensor_id,timestamp_utc,amplitude,filtered_amplitude,"
            + "frequency_hz,duration_s,noise_level,quality,quality_note,scenario_label";
    public static final String EVENTS_HEADER = "event_id,status,severity,confidence,agreement,temporal,spatial,signal,"
            + "start_utc,last_activity_utc,participating_sensors,sensor_ids,affected_zone,peak_amplitude,"
            + "peak_filtered_amplitude,mean_frequency_hz,duration_s,affected_radius_km";

    public void exportReadings(List<SeismicReading> readings, Path target) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            out.write(READINGS_HEADER);
            out.newLine();
            for (SeismicReading r : readings) {
                out.write(String.join(",",
                        CsvUtils.escape(r.getReadingId()),
                        CsvUtils.escape(r.getSensorId()),
                        r.getTimestamp().toString(),
                        CsvUtils.number(r.getAmplitude(), 4),
                        CsvUtils.number(r.getFilteredAmplitude(), 4),
                        CsvUtils.number(r.getFrequencyHz(), 3),
                        CsvUtils.number(r.getDurationSeconds(), 3),
                        CsvUtils.number(r.getNoiseLevel(), 3),
                        r.getQuality().name(),
                        CsvUtils.escape(r.getQualityNote()),
                        CsvUtils.escape(r.getScenarioLabel())));
                out.newLine();
            }
        }
    }

    public void exportEvents(List<SeismicEvent> events, Path target) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            out.write(EVENTS_HEADER);
            out.newLine();
            for (SeismicEvent e : events) {
                var c = e.getConfidence();
                out.write(String.join(",",
                        CsvUtils.escape(e.getEventId()),
                        e.getStatus().name(),
                        e.getSeverity().name(),
                        CsvUtils.number(c.score(), 1),
                        CsvUtils.number(c.sensorAgreement(), 3),
                        CsvUtils.number(c.temporalConsistency().orElse(Double.NaN), 3),
                        CsvUtils.number(c.spatialConsistency().orElse(Double.NaN), 3),
                        CsvUtils.number(c.signalScore(), 3),
                        e.getStartTime().toString(),
                        e.getLastActivityTime().toString(),
                        String.valueOf(e.getParticipatingSensorCount()),
                        CsvUtils.escape(String.join(" ", e.getParticipatingSensorIds())),
                        CsvUtils.escape(e.getAffectedZoneSummary()),
                        CsvUtils.number(e.getPeakAmplitude(), 4),
                        CsvUtils.number(e.getPeakFilteredAmplitude(), 4),
                        CsvUtils.number(e.getMeanFrequencyHz(), 2),
                        String.format(Locale.ROOT, "%.1f", e.getDurationSeconds()),
                        CsvUtils.number(e.getAffectedRadiusKm(), 1)));
                out.newLine();
            }
        }
    }
}
