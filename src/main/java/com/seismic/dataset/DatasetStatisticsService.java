package com.seismic.dataset;

import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;

import java.time.Instant;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Computes summary statistics of a loaded dataset for the Dataset screen. */
public final class DatasetStatisticsService {

    /** Per-sensor summary. Means use only readings whose amplitude is usable (VALID or SUSPICIOUS). */
    public record SensorStats(String sensorId, long readings, long invalidReadings, double meanAmplitude,
                              double maxAmplitude, double meanNoiseLevel) {
    }

    public record DatasetStatistics(int acceptedRecords, int rejectedRecords,
                                    Map<ReadingQuality, Long> countsByQuality,
                                    Map<String, Long> countsByScenarioLabel,
                                    Map<String, SensorStats> perSensor,
                                    double minAmplitude, double meanAmplitude, double maxAmplitude,
                                    Instant firstReading, Instant lastReading) {
    }

    public DatasetStatistics compute(List<SeismicReading> readings, int rejectedRecords) {
        Map<ReadingQuality, Long> byQuality = new EnumMap<>(ReadingQuality.class);
        for (ReadingQuality quality : ReadingQuality.values()) {
            byQuality.put(quality, 0L);
        }
        readings.forEach(r -> byQuality.merge(r.getQuality(), 1L, Long::sum));

        Map<String, Long> byLabel = readings.stream().collect(Collectors.groupingBy(
                r -> r.getScenarioLabel().isBlank() ? "(none)" : r.getScenarioLabel(),
                TreeMap::new, Collectors.counting()));

        Map<String, List<SeismicReading>> bySensor = readings.stream()
                .collect(Collectors.groupingBy(SeismicReading::getSensorId, TreeMap::new, Collectors.toList()));
        Map<String, SensorStats> perSensor = new TreeMap<>();
        bySensor.forEach((sensorId, list) -> perSensor.put(sensorId, sensorStats(sensorId, list)));

        DoubleSummaryStatistics amplitude = readings.stream()
                .filter(r -> r.getQuality().hasUsableAmplitude())
                .mapToDouble(SeismicReading::getAmplitude).summaryStatistics();
        boolean any = amplitude.getCount() > 0;

        Instant first = readings.stream().map(SeismicReading::getTimestamp).min(Instant::compareTo).orElse(null);
        Instant last = readings.stream().map(SeismicReading::getTimestamp).max(Instant::compareTo).orElse(null);
        return new DatasetStatistics(readings.size(), rejectedRecords, Collections.unmodifiableMap(byQuality),
                Collections.unmodifiableMap(byLabel), Collections.unmodifiableMap(perSensor),
                any ? amplitude.getMin() : Double.NaN, any ? amplitude.getAverage() : Double.NaN,
                any ? amplitude.getMax() : Double.NaN, first, last);
    }

    private static SensorStats sensorStats(String sensorId, List<SeismicReading> readings) {
        DoubleSummaryStatistics amplitude = readings.stream()
                .filter(r -> r.getQuality().hasUsableAmplitude())
                .mapToDouble(SeismicReading::getAmplitude).summaryStatistics();
        double meanNoise = readings.stream()
                .filter(r -> r.getQuality().hasUsableAmplitude() && !Double.isNaN(r.getNoiseLevel()))
                .mapToDouble(SeismicReading::getNoiseLevel).average().orElse(Double.NaN);
        long invalid = readings.stream().filter(r -> r.getQuality() == ReadingQuality.INVALID).count();
        boolean any = amplitude.getCount() > 0;
        return new SensorStats(sensorId, readings.size(), invalid,
                any ? amplitude.getAverage() : Double.NaN, any ? amplitude.getMax() : Double.NaN, meanNoise);
    }
}
