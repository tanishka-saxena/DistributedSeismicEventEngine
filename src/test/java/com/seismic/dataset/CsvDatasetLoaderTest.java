package com.seismic.dataset;

import com.seismic.TestData;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SensorNetwork;
import com.seismic.simulation.SensorNetworkFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDatasetLoaderTest {

    private static final String HEADER = "reading_id,sensor_id,timestamp_utc,amplitude,frequency_hz,duration_s,noise_level,scenario_label\n";
    private final CsvDatasetLoader loader = new CsvDatasetLoader(TestData.validator(), TestData.CLOCK);
    private final SensorNetwork network = TestData.network();

    private DatasetLoadResult load(String csv) throws Exception {
        return loader.load(new StringReader(csv), "test.csv", DatasetSourceType.IMPORTED, network);
    }

    @Test
    void bundledDatasetLoadsWithExpectedCounts() throws Exception {
        SensorNetwork real = SensorNetworkFactory.load(Path.of("data", "sensors.csv"));
        DatasetLoadResult result = loader.load(Path.of("data", "seismic_sensor_readings.csv"), DatasetSourceType.BUNDLED, real);
        assertTrue(result.readings().size() >= 500, "at least 500 usable records");
        assertEquals(6, result.rejected().size(), "the six deliberately broken structural rows");
        assertTrue(result.count(ReadingQuality.INVALID) >= 2, "non-numeric and missing amplitude rows are flagged");
        assertTrue(result.count(ReadingQuality.SUSPICIOUS) > 0, "noisy sensor rows");
        assertEquals(result.metadata().totalRecords(), result.readings().size() + result.rejected().size());
    }

    @Test
    void malformedRowsAreRejectedWithReasons() throws Exception {
        DatasetLoadResult result = load(HEADER
                + "R1,A1,2026-10-01T08:00:00Z,0.10,4,1,0.1,ok\n"
                + "R2,A1,2026-10-01T08:00:01Z,0.10,4\n"                  // too few columns
                + "R3,ZZ,2026-10-01T08:00:02Z,0.10,4,1,0.1,x\n"          // unknown sensor
                + "R4,A1,yesterday,0.10,4,1,0.1,x\n"                     // bad timestamp
                + "R1,A2,2026-10-01T08:00:03Z,0.10,4,1,0.1,x\n"          // duplicate id
                + ",A2,2026-10-01T08:00:04Z,0.10,4,1,0.1,x\n"            // missing id
                + "R7,A2,2026-10-01T08:00:05Z,\"0.10,4,1,0.1,x\n");      // unterminated quote
        assertEquals(1, result.readings().size());
        assertEquals(6, result.rejected().size());
        assertTrue(result.rejected().get(0).reason().contains("expected 8 columns"));
        assertTrue(result.rejected().get(1).reason().contains("unknown sensor"));
        assertTrue(result.rejected().get(2).reason().contains("invalid timestamp"));
        assertTrue(result.rejected().get(3).reason().contains("duplicate"));
        assertTrue(result.rejected().get(4).reason().contains("reading_id missing"));
        assertTrue(result.rejected().get(5).reason().contains("malformed"));
        assertEquals(3, result.rejected().get(0).lineNumber());
    }

    @Test
    void badNumericValuesAreKeptAsInvalidRecords() throws Exception {
        DatasetLoadResult result = load(HEADER
                + "R1,A1,2026-10-01T08:00:00Z,abc,4,1,0.1,x\n"
                + "R2,A1,2026-10-01T08:00:01Z,,4,1,0.1,x\n");
        assertEquals(2, result.count(ReadingQuality.INVALID));
        assertTrue(result.readings().get(0).getQualityNote().contains("not a number"));
        assertEquals(2, result.metadata().invalidRecords());
    }

    @Test
    void missingRequiredColumnsFailWithAClearMessage() {
        DatasetFormatException e = assertThrows(DatasetFormatException.class,
                () -> load("reading_id,sensor_id,timestamp_utc,frequency_hz\nR1,A1,2026-10-01T08:00:00Z,4\n"));
        assertTrue(e.getMessage().contains("amplitude"));
        assertTrue(e.getMessage().contains("noise_level"));
        assertThrows(DatasetFormatException.class, () -> load("# only a comment\n"));
    }

    @Test
    void headerAliasesExtraColumnsAndLocalTimestampsAreAccepted() throws Exception {
        DatasetLoadResult result = load("ID,Sensor,Timestamp,Amplitude,Frequency,Duration,Noise,comment\n"
                + "R1,a1,2026-10-01 08:00:00,0.10,4,1,0.1,\"quoted, with comma\"\n");
        assertEquals(1, result.readings().size());
        assertEquals("A1", result.readings().get(0).getSensorId());
        assertEquals(Instant.parse("2026-10-01T08:00:00Z"), result.readings().get(0).getTimestamp(), "local time is UTC");
    }

    @Test
    void readingsAreSortedByTime() throws Exception {
        DatasetLoadResult result = load(HEADER
                + "R2,A1,2026-10-01T08:00:05Z,0.10,4,1,0.1,x\n"
                + "R1,A1,2026-10-01T08:00:00Z,0.10,4,1,0.1,x\n");
        assertEquals("R1", result.readings().get(0).getReadingId());
    }

    @Test
    void exportedReadingsCanBeLoadedAgain(@TempDir Path dir) throws Exception {
        DatasetLoadResult original = load(HEADER
                + "R1,A1,2026-10-01T08:00:00Z,0.10,4,1,0.1,x\n"
                + "R2,A2,2026-10-01T08:00:01Z,0.40,4,1,0.1,\"label, quoted\"\n");
        Path file = dir.resolve("export.csv");
        new CsvExporter().exportReadings(NoiseFilter.applyAll(original.readings(), TestData.config().filter()), file);
        DatasetLoadResult reloaded = loader.load(file, DatasetSourceType.IMPORTED, network);
        assertEquals(2, reloaded.readings().size());
        assertEquals("label, quoted", reloaded.readings().get(1).getScenarioLabel());
        assertTrue(reloaded.rejected().isEmpty());
    }

    @Test
    void statisticsSummariseQualityLabelsAndSensors() throws Exception {
        DatasetLoadResult result = load(HEADER
                + "R1,A1,2026-10-01T08:00:00Z,0.10,4,1,0.1,NORMAL\n"
                + "R2,A1,2026-10-01T08:00:01Z,0.30,4,1,0.1,NORMAL\n"
                + "R3,A2,2026-10-01T08:00:01Z,,4,1,0.1,BROKEN\n");
        var stats = new DatasetStatisticsService().compute(result.readings(), 0);
        assertEquals(2L, stats.countsByQuality().get(ReadingQuality.VALID));
        assertEquals(1L, stats.countsByQuality().get(ReadingQuality.INVALID));
        assertEquals(2L, stats.countsByScenarioLabel().get("NORMAL"));
        assertEquals(0.2, stats.perSensor().get("A1").meanAmplitude(), 1e-9);
        assertEquals(1L, stats.perSensor().get("A2").invalidReadings());
        assertEquals(0.30, stats.maxAmplitude(), 1e-9);
    }
}
