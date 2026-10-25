package com.seismic.repository;

import com.seismic.model.DatasetMetadata;
import com.seismic.model.SeismicReading;

import java.util.List;

/** Stores analysed dataset files. */
public interface DatasetRepository {

    /**
     * Saves the metadata and all accepted readings in ONE transaction: either the whole dataset is stored
     * or nothing is. Returns the metadata with its generated ID.
     */
    DatasetMetadata saveDataset(DatasetMetadata metadata, List<SeismicReading> readings);

    /** Newest first. */
    List<DatasetMetadata> findAll();
}
