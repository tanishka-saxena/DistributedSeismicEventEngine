package com.seismic.dataset;

import com.seismic.model.CatalogEarthquake;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Uses a hand-written snippet in the USGS column layout; the values are test fixtures, not real events. */
class UsgsCatalogLoaderTest {

    private static final String HEADER = "time,latitude,longitude,depth,mag,magType,nst,gap,dmin,rms,net,id,updated,place,type\n";
    private final UsgsCatalogLoader loader = new UsgsCatalogLoader();

    @Test
    void parsesQuotedPlaceAndKeepsRecordsSeparateFromSensorReadings() throws Exception {
        var result = loader.load(new StringReader(HEADER
                + "2026-01-02T03:04:05.678Z,10.5,-20.25,12.3,4.6,mb,,50,1.2,0.8,us,test0001,2026-01-03T00:00:00Z,\"10 km N of Somewhere, Region\",earthquake\n"),
                "catalog.csv");
        assertEquals(1, result.earthquakes().size());
        CatalogEarthquake quake = result.earthquakes().get(0);
        assertEquals(Instant.parse("2026-01-02T03:04:05.678Z"), quake.time());
        assertEquals("10 km N of Somewhere, Region", quake.place());
        assertEquals(4.6, quake.magnitude(), 1e-9);
        assertEquals("test0001", quake.eventId());
    }

    @Test
    void bundledRealUsgsCatalogLoadsWithoutRejections() throws Exception {
        var result = loader.load(java.nio.file.Path.of("data", "usgs_sample_catalog.csv"));
        assertEquals(177, result.earthquakes().size());
        assertEquals(0, result.rejected().size());
    }

    @Test
    void invalidRowsAreRejectedAndMissingColumnsFail() throws Exception {
        var result = loader.load(new StringReader(HEADER
                + "not-a-time,10,20,5,4,mb,,,,,us,x1,,place,earthquake\n"
                + "2026-01-02T03:04:05Z,95,20,5,4,mb,,,,,us,x2,,place,earthquake\n"
                + "2026-01-02T03:04:05Z,10,20,5,,mb,,,,,us,x3,,place,earthquake\n"), "bad.csv");
        assertEquals(0, result.earthquakes().size());
        assertEquals(3, result.rejected().size());
        assertThrows(DatasetFormatException.class,
                () -> loader.load(new StringReader("reading_id,sensor_id\nR1,S01\n"), "readings.csv"));
    }
}
