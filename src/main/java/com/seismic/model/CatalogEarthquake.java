package com.seismic.model;

import java.time.Instant;

/**
 * One record of a public earthquake catalog (for example a USGS CSV export).
 * <p>
 * This is real catalog data and is deliberately kept separate from {@link SeismicReading}:
 * a catalog lists events, it does not contain raw sensor measurements, so no sensor IDs,
 * amplitudes, frequencies or noise levels are derived from it.
 */
public record CatalogEarthquake(String eventId, Instant time, double latitude, double longitude,
                                double depthKm, double magnitude, String magnitudeType, String place) {
}
