package com.seismic.model;

/** Origin of a dataset file. */
public enum DatasetSourceType {
    /** The synthetic dataset shipped in data/. */
    BUNDLED,
    /** A synthetic or user-provided sensor-reading CSV chosen in the UI. */
    IMPORTED,
    /** A public earthquake catalog (real events, no raw sensor data). */
    USGS_CATALOG
}
