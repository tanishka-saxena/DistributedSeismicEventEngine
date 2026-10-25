package com.seismic.repository.memory;

import com.seismic.model.DatasetMetadata;
import com.seismic.model.SeismicReading;
import com.seismic.repository.DatasetRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo-mode dataset storage. Only the metadata is kept; the readings themselves stay in the Dataset
 * screen's memory for the current session.
 */
public final class InMemoryDatasetRepository implements DatasetRepository {

    private final AtomicLong ids = new AtomicLong();
    private final List<DatasetMetadata> datasets = new ArrayList<>();

    @Override
    public synchronized DatasetMetadata saveDataset(DatasetMetadata metadata, List<SeismicReading> readings) {
        DatasetMetadata saved = metadata.withId(ids.incrementAndGet());
        datasets.add(saved);
        return saved;
    }

    @Override
    public synchronized List<DatasetMetadata> findAll() {
        List<DatasetMetadata> list = new ArrayList<>(datasets);
        Collections.reverse(list);
        return list;
    }
}
