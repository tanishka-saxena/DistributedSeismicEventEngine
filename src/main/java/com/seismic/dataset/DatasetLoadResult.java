package com.seismic.dataset;

import com.seismic.model.DatasetMetadata;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;

import java.util.List;

/**
 * Everything produced by loading one sensor-reading CSV file.
 *
 * @param readings accepted readings (VALID, SUSPICIOUS or INVALID), sorted by timestamp
 * @param rejected lines that could not be used at all
 */
public record DatasetLoadResult(DatasetMetadata metadata, List<SeismicReading> readings, List<RejectedRecord> rejected) {

    public DatasetLoadResult {
        readings = List.copyOf(readings);
        rejected = List.copyOf(rejected);
    }

    public long count(ReadingQuality quality) {
        return readings.stream().filter(r -> r.getQuality() == quality).count();
    }
}
