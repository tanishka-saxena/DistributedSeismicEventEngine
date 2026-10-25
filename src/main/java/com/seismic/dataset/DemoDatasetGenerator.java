package com.seismic.dataset;

import com.seismic.config.SimulationConfig;
import com.seismic.model.GeoPoint;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.simulation.ReadingGenerator;
import com.seismic.simulation.ScenarioEffect;
import com.seismic.simulation.ScenarioPlanner;
import com.seismic.simulation.ScenarioType;
import com.seismic.simulation.SeismicWaveEffect;
import com.seismic.simulation.SensorNetworkFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates the bundled SYNTHETIC dataset (data/seismic_sensor_readings.csv) and the small import
 * samples. It replays a fixed scenario timeline through the same {@link ReadingGenerator} and
 * scenario effects as the live simulator, with a fixed seed, so the output is reproducible.
 *
 * <p>Run from the project folder after {@code mvnw compile}:
 * <pre>java -cp target/classes com.seismic.dataset.DemoDatasetGenerator</pre>
 * (Logging jars are not needed; this class does not log.)
 *
 * <p>Timeline (seconds after 2026-10-01T08:00:00Z, one reading per sensor per second, 100 s):
 * <pre>
 *  t=15  MINOR_EVENT in the North zone (8 s)
 *  t=28  ISOLATED_SPIKE on S08
 *  t=30  NOISY_DATA on S14 for 25 s
 *  t=38  MAJOR_EVENT in the Central zone (12 s, reaches the outer zones about 5 s later)
 *  t=55  SENSOR_FAULT on S11 for 25 s
 *  t=60  COMMUNICATION_FAILURE on S05 for 15 s (no rows for S05)
 *  t=66  INVALID_DATA on S13 for 12 s
 *  t=88  WEAK_EVENT felt by only two East sensors (S07, S09)
 * </pre>
 */
public final class DemoDatasetGenerator {

    public static final Instant START = Instant.parse("2026-10-01T08:00:00Z");
    public static final int DURATION_SECONDS = 100;
    public static final String HEADER =
            "reading_id,sensor_id,timestamp_utc,amplitude,frequency_hz,duration_s,noise_level,scenario_label";

    private final SensorNetwork network;
    private final SimulationConfig config;
    private final ReadingGenerator generator = new ReadingGenerator();

    public DemoDatasetGenerator(SensorNetwork network, SimulationConfig config) {
        this.network = network;
        this.config = config;
    }

    public static void main(String[] args) throws IOException {
        Path outputDir = Path.of(args.length > 0 ? args[0] : "data");
        SensorNetwork network = SensorNetworkFactory.load(outputDir.resolve("sensors.csv"));
        DemoDatasetGenerator generator = new DemoDatasetGenerator(network, SimulationConfig.defaults());
        int rows = generator.writeMainDataset(outputDir.resolve("seismic_sensor_readings.csv"));
        generator.writeImportSampleWithErrors(outputDir.resolve("sample_import_with_errors.csv"));
        generator.writeMissingColumnSample(outputDir.resolve("sample_missing_columns.csv"));
        System.out.println("Wrote " + rows + " data rows to " + outputDir.resolve("seismic_sensor_readings.csv"));
    }

    /** Builds the scripted scenario timeline. */
    List<ScenarioEffect> timeline() {
        Random plannerRandom = new Random(config.randomSeed());
        ScenarioPlanner planner = new ScenarioPlanner(config, network);
        List<ScenarioEffect> effects = new ArrayList<>();
        effects.addAll(planner.plan(ScenarioType.MINOR_EVENT, at(15), "S04", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.ISOLATED_SPIKE, at(28), "S08", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.NOISY_DATA, at(30), "S14", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.MAJOR_EVENT, at(38), "S01", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.SENSOR_FAULT, at(55), "S11", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.COMMUNICATION_FAILURE, at(60), "S05", plannerRandom));
        effects.addAll(planner.plan(ScenarioType.INVALID_DATA, at(66), "S13", plannerRandom));
        // A weak event centred between S07 and S09 with a small radius: only those two sensors feel it,
        // so the engine should report it as UNCERTAIN rather than CONFIRMED.
        GeoPoint a = network.require("S07").getLocation();
        GeoPoint b = network.require("S09").getLocation();
        GeoPoint midpoint = new GeoPoint((a.latitude() + b.latitude()) / 2, (a.longitude() + b.longitude()) / 2);
        effects.add(new SeismicWaveEffect(ScenarioType.MINOR_EVENT, midpoint, at(88), 6, 0.42,
                config.attenuationLengthKm(), config.waveSpeedKmPerSecond(), 2.2, "WEAK_EVENT"));
        return effects;
    }

    /** Generates all readings of the timeline in time order. */
    public List<SeismicReading> generateReadings() {
        List<ScenarioEffect> effects = timeline();
        List<Sensor> sensors = network.sensors();
        List<Random> sensorRandoms = new ArrayList<>();
        for (int i = 0; i < sensors.size(); i++) {
            sensorRandoms.add(new Random(config.randomSeed() + 1000L * (i + 1)));
        }
        List<SeismicReading> readings = new ArrayList<>();
        int sequence = 1;
        for (int second = 0; second < DURATION_SECONDS; second++) {
            for (int i = 0; i < sensors.size(); i++) {
                Instant time = at(second);
                String id = String.format("D%05d", sequence);
                var reading = generator.generate(sensors.get(i), time, id, sensorRandoms.get(i), effects,
                        ReadingSource.DATASET);
                if (reading.isPresent()) {
                    readings.add(reading.get());
                    sequence++;
                }
            }
        }
        return readings;
    }

    public int writeMainDataset(Path file) throws IOException {
        List<SeismicReading> readings = generateReadings();
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# SYNTHETIC DATA generated by com.seismic.dataset.DemoDatasetGenerator (seed "
                    + config.randomSeed() + "). Not real seismic measurements. See data/data_dictionary.md.");
            out.newLine();
            out.write(HEADER);
            out.newLine();
            for (SeismicReading r : readings) {
                writeRow(out, r);
            }
            // Deliberately broken rows so the validation report has something to show.
            out.write("# The rows below are deliberately malformed to demonstrate validation.");
            out.newLine();
            for (String line : List.of(
                    "E00001,S99,2026-10-01T08:00:30Z,0.1100,4.000,1.000,0.080,ERROR_UNKNOWN_SENSOR",
                    "E00002,S02,2026-10-01 25:61:00,0.1200,4.000,1.000,0.080,ERROR_BAD_TIMESTAMP",
                    ",S03,2026-10-01T08:00:31Z,0.1000,4.000,1.000,0.080,ERROR_MISSING_ID",
                    "E00004,S04,2026-10-01T08:00:32Z,0.1000,4.000",
                    "D00010,S06,2026-10-01T08:00:33Z,0.1000,4.000,1.000,0.080,ERROR_DUPLICATE_ID",
                    "E00006,S06,2026-10-01T08:00:34Z,abc,4.000,1.000,0.080,ERROR_NON_NUMERIC",
                    "E00007,S07,1999-12-31T23:59:59Z,0.1000,4.000,1.000,0.080,ERROR_OLD_TIMESTAMP",
                    "E00008,S10,2026-10-01T08:00:35Z,,4.000,1.000,0.080,ERROR_MISSING_AMPLITUDE")) {
                out.write(line);
                out.newLine();
            }
        }
        return readings.size() + 8;
    }

    /** A small user-import sample: valid rows for three sensors plus several typical mistakes. */
    public void writeImportSampleWithErrors(Path file) throws IOException {
        Random random = new Random(7);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("# Example user import (SYNTHETIC). Contains valid rows and deliberate errors.");
            out.newLine();
            out.write(HEADER);
            out.newLine();
            Instant base = Instant.parse("2026-10-02T10:00:00Z");
            int n = 1;
            for (int second = 0; second < 10; second++) {
                for (String sensor : List.of("S01", "S02", "S03")) {
                    double amplitude = 0.07 + Math.abs(random.nextGaussian()) * 0.03;
                    out.write(String.format(java.util.Locale.ROOT, "U%04d,%s,%s,%.4f,%.3f,%.3f,%.3f,NORMAL",
                            n++, sensor, base.plusSeconds(second), amplitude, 1 + random.nextDouble() * 9,
                            0.5 + random.nextDouble() * 1.5, 0.04 + random.nextDouble() * 0.1));
                    out.newLine();
                }
            }
            for (String line : List.of(
                    "U9001,S01,2026-10-02T10:00:11Z,-0.2000,4.000,1.000,0.080,ERROR_NEGATIVE_AMPLITUDE",
                    "U9002,S02,2026-10-02T10:00:11Z,0.1000,0,1.000,0.080,ERROR_ZERO_FREQUENCY",
                    "U9003,S03,2026-10-02T10:00:11Z,0.1000,4.000,1.000,1.700,ERROR_NOISE_ABOVE_ONE",
                    "U9004,S01,2026-10-02T10:00:12Z,0.2000,4.000,1.000,0.720,NOISY",
                    "U9005,S01,not-a-time,0.1000,4.000,1.000,0.080,ERROR_BAD_TIMESTAMP",
                    "U9006,X1,2026-10-02T10:00:12Z,0.1000,4.000,1.000,0.080,ERROR_UNKNOWN_SENSOR",
                    "U9007,S02,2026-10-02T10:00:12Z,0.1000,\"4.0\",1.000",
                    "U9008,S03,2026-10-02T10:00:13Z,0.1000,,,,MISSING_OPTIONAL_FIELDS")) {
                out.write(line);
                out.newLine();
            }
        }
    }

    /** A file without the amplitude and noise_level columns, to demonstrate the format error. */
    public void writeMissingColumnSample(Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("reading_id,sensor_id,timestamp_utc,frequency_hz,duration_s");
            out.newLine();
            out.write("M0001,S01,2026-10-02T11:00:00Z,4.0,1.0");
            out.newLine();
        }
    }

    private static void writeRow(BufferedWriter out, SeismicReading r) throws IOException {
        out.write(String.join(",",
                r.getReadingId(),
                r.getSensorId(),
                r.getTimestamp().toString(),
                CsvUtils.number(r.getAmplitude(), 4),
                CsvUtils.number(r.getFrequencyHz(), 3),
                CsvUtils.number(r.getDurationSeconds(), 3),
                CsvUtils.number(r.getNoiseLevel(), 3),
                r.getScenarioLabel()));
        out.newLine();
    }

    private static Instant at(int second) {
        return START.plusSeconds(second);
    }
}
