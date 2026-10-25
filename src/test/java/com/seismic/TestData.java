package com.seismic;

import com.seismic.config.ProcessingConfig;
import com.seismic.dataset.DataValidator;
import com.seismic.model.GeoPoint;
import com.seismic.model.ReadingQuality;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Deterministic fixtures shared by the tests.
 * <p>
 * Network layout (km from the base point): zone A has A1 (0,0), A2 (2 N), A3 (2 E), A4 (3 N, 3 E);
 * zone B is 100 km north with B1, B2, B3. So A-sensors correlate with each other but never with B-sensors
 * (default radius 40 km).
 */
public final class TestData {

    public static final Instant T0 = Instant.parse("2026-10-01T08:00:00Z");
    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC);
    public static final GeoPoint BASE = new GeoPoint(27.70, 85.30);

    private TestData() {
    }

    public static SensorNetwork network() {
        return new SensorNetwork(List.of(
                sensor("A1", "A", 0, 0),
                sensor("A2", "A", 2, 0),
                sensor("A3", "A", 0, 2),
                sensor("A4", "A", 3, 3),
                sensor("B1", "B", 100, 0),
                sensor("B2", "B", 102, 0),
                sensor("B3", "B", 100, 2)));
    }

    public static Sensor sensor(String id, String zone, double northKm, double eastKm) {
        return new Sensor(id, "Sensor " + id, zone, BASE.offsetKm(northKm, eastKm));
    }

    public static DataValidator validator() {
        return new DataValidator(CLOCK);
    }

    public static ProcessingConfig config() {
        return ProcessingConfig.defaults();
    }

    /** An unvalidated reading with seismic-looking signal values (4 Hz, 2 s burst, low noise). */
    public static SeismicReading reading(String id, String sensorId, double secondsAfterT0, double amplitude) {
        return builder(id, sensorId, secondsAfterT0, amplitude).build();
    }

    public static SeismicReading.Builder builder(String id, String sensorId, double secondsAfterT0, double amplitude) {
        return SeismicReading.builder()
                .readingId(id)
                .sensorId(sensorId)
                .timestamp(T0.plusMillis(Math.round(secondsAfterT0 * 1000)))
                .amplitude(amplitude)
                .frequencyHz(4.0)
                .durationSeconds(2.0)
                .noiseLevel(0.1)
                .source(ReadingSource.SIMULATION);
    }

    /** A reading already marked VALID with filtered = raw amplitude. */
    public static SeismicReading valid(String id, String sensorId, double secondsAfterT0, double amplitude) {
        return builder(id, sensorId, secondsAfterT0, amplitude)
                .quality(ReadingQuality.VALID).filteredAmplitude(amplitude).build();
    }

    public static Supplier<String> ids(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return () -> prefix + counter.incrementAndGet();
    }

    public static Instant at(double secondsAfterT0) {
        return T0.plusMillis(Math.round(secondsAfterT0 * 1000));
    }
}
