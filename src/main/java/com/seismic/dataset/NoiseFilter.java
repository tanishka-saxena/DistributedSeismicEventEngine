package com.seismic.dataset;

import com.seismic.config.ProcessingConfig.FilterSettings;
import com.seismic.model.SeismicReading;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moving-average filter applied separately to each sensor's stream of readings.
 *
 * <p>filtered = mean of the last N usable amplitudes of the SAME sensor (N = window size, default 3).
 * Rules that keep the average meaningful:
 * <ul>
 *   <li>Each sensor has its own window, so unrelated sensors are never averaged together.</li>
 *   <li>If the gap since the sensor's previous reading exceeds {@code maxGapSeconds}, the window restarts,
 *       so readings from unrelated time windows are not mixed.</li>
 *   <li>A reading older than the sensor's previous reading (out of order) is not added to the window;
 *       its filtered value equals its raw value.</li>
 *   <li>INVALID readings are skipped (they have no trustworthy amplitude) and keep a NaN filtered value.</li>
 * </ul>
 * The raw amplitude is never changed and spikes are never deleted: they stay in the data for fault detection.
 *
 * <p>Not thread-safe: an instance belongs to a single processing thread.
 * Cost: O(N) per reading, O(sensors x N) memory.
 */
public final class NoiseFilter {

    private final int windowSize;
    private final Duration maxGap;
    private final Map<String, SensorWindow> windows = new HashMap<>();

    public NoiseFilter(FilterSettings settings) {
        this.windowSize = settings.windowSize();
        this.maxGap = Duration.ofMillis(Math.round(settings.maxGapSeconds() * 1000));
    }

    /** Filters one reading in arrival order and returns a copy carrying the filtered amplitude. */
    public SeismicReading apply(SeismicReading reading) {
        if (!reading.getQuality().hasUsableAmplitude()) {
            return reading;
        }
        SensorWindow window = windows.computeIfAbsent(reading.getSensorId(), id -> new SensorWindow());
        Instant time = reading.getTimestamp();
        if (window.lastTimestamp != null && time.isBefore(window.lastTimestamp)) {
            return reading.withFilteredAmplitude(reading.getAmplitude());
        }
        if (window.lastTimestamp != null && Duration.between(window.lastTimestamp, time).compareTo(maxGap) > 0) {
            window.values.clear();
        }
        window.values.addLast(reading.getAmplitude());
        if (window.values.size() > windowSize) {
            window.values.removeFirst();
        }
        window.lastTimestamp = time;
        return reading.withFilteredAmplitude(window.average());
    }

    /**
     * Filters a whole batch with fresh state: readings are processed in timestamp order so each
     * sensor's window sees its readings in the right sequence. The result is in timestamp order.
     */
    public static List<SeismicReading> applyAll(List<SeismicReading> readings, FilterSettings settings) {
        List<SeismicReading> ordered = new ArrayList<>(readings);
        ordered.sort(Comparator.comparing(SeismicReading::getTimestamp).thenComparing(SeismicReading::getSensorId));
        NoiseFilter filter = new NoiseFilter(settings);
        List<SeismicReading> result = new ArrayList<>(ordered.size());
        for (SeismicReading reading : ordered) {
            result.add(filter.apply(reading));
        }
        return result;
    }

    public void reset() {
        windows.clear();
    }

    public int getWindowSize() {
        return windowSize;
    }

    private static final class SensorWindow {
        private final Deque<Double> values = new ArrayDeque<>();
        private Instant lastTimestamp;

        double average() {
            double sum = 0;
            for (double value : values) {
                sum += value;
            }
            return sum / values.size();
        }
    }
}
