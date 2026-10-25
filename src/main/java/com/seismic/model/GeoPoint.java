package com.seismic.model;

/**
 * A latitude/longitude pair in decimal degrees. A point may be unknown (NaN) so that
 * spatial checks can treat missing coordinates as "unavailable" instead of as a match.
 */
public record GeoPoint(double latitude, double longitude) {

    private static final double EARTH_RADIUS_KM = 6371.0;
    private static final double KM_PER_DEGREE_LATITUDE = 111.32;
    public static final GeoPoint UNKNOWN = new GeoPoint(Double.NaN, Double.NaN);

    public GeoPoint {
        if (!Double.isNaN(latitude) && (latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("Latitude out of range: " + latitude);
        }
        if (!Double.isNaN(longitude) && (longitude < -180 || longitude > 180)) {
            throw new IllegalArgumentException("Longitude out of range: " + longitude);
        }
    }

    public boolean isKnown() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    /** Great-circle distance in kilometres (haversine formula). NaN if either point is unknown. */
    public double distanceKm(GeoPoint other) {
        if (!isKnown() || other == null || !other.isKnown()) {
            return Double.NaN;
        }
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Returns the point reached by moving the given distances north and east (small-distance approximation). */
    public GeoPoint offsetKm(double northKm, double eastKm) {
        double newLat = latitude + northKm / KM_PER_DEGREE_LATITUDE;
        double newLon = longitude + eastKm / (KM_PER_DEGREE_LATITUDE * Math.cos(Math.toRadians(latitude)));
        return new GeoPoint(newLat, newLon);
    }
}
