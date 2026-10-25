package com.seismic.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of virtual sensors that make up the distributed network.
 * <p>
 * A {@link ConcurrentHashMap} gives O(1) lookup by sensor ID and is safe to read from the UI thread
 * while the processing thread updates sensors. The list of IDs keeps a stable display order.
 */
public final class SensorNetwork {

    private final Map<String, Sensor> sensorsById = new ConcurrentHashMap<>();
    private final List<String> orderedIds;

    public SensorNetwork(Collection<Sensor> sensors) {
        if (sensors == null || sensors.isEmpty()) {
            throw new IllegalArgumentException("A sensor network needs at least one sensor");
        }
        List<String> ids = new ArrayList<>();
        for (Sensor sensor : sensors) {
            if (sensorsById.putIfAbsent(sensor.getSensorId(), sensor) != null) {
                throw new IllegalArgumentException("Duplicate sensor ID: " + sensor.getSensorId());
            }
            ids.add(sensor.getSensorId());
        }
        this.orderedIds = Collections.unmodifiableList(ids);
    }

    public Optional<Sensor> find(String sensorId) {
        return sensorId == null ? Optional.empty() : Optional.ofNullable(sensorsById.get(sensorId));
    }

    public Sensor require(String sensorId) {
        return find(sensorId).orElseThrow(() -> new IllegalArgumentException("Unknown sensor: " + sensorId));
    }

    public boolean contains(String sensorId) {
        return sensorId != null && sensorsById.containsKey(sensorId);
    }

    public int size() {
        return orderedIds.size();
    }

    public List<String> sensorIds() {
        return orderedIds;
    }

    /** Live sensor objects in display order (for the processing thread). */
    public List<Sensor> sensors() {
        List<Sensor> result = new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) {
            result.add(sensorsById.get(id));
        }
        return result;
    }

    /** Independent copies of every sensor (for the UI thread and for persistence). */
    public List<Sensor> snapshot() {
        List<Sensor> result = new ArrayList<>(orderedIds.size());
        for (String id : orderedIds) {
            result.add(sensorsById.get(id).copy());
        }
        return result;
    }

    /** Zone names in the order they first appear. */
    public Set<String> zones() {
        Set<String> zones = new LinkedHashSet<>();
        for (String id : orderedIds) {
            zones.add(sensorsById.get(id).getZone());
        }
        return zones;
    }

    /** Sensors other than {@code centre} whose distance from it is at most {@code radiusKm}. */
    public List<Sensor> neighboursOf(Sensor centre, double radiusKm) {
        List<Sensor> result = new ArrayList<>();
        for (Sensor sensor : sensors()) {
            if (sensor.equals(centre)) {
                continue;
            }
            double distance = centre.getLocation().distanceKm(sensor.getLocation());
            if (!Double.isNaN(distance) && distance <= radiusKm) {
                result.add(sensor);
            }
        }
        return result;
    }

    /** A new network with the same sensors in their initial state (used for offline dataset analysis). */
    public SensorNetwork freshCopy() {
        List<Sensor> copies = new ArrayList<>();
        for (Sensor sensor : sensors()) {
            copies.add(new Sensor(sensor.getSensorId(), sensor.getName(), sensor.getZone(), sensor.getLocation()));
        }
        return new SensorNetwork(copies);
    }

    public void resetAll() {
        sensorsById.values().forEach(Sensor::resetState);
    }

    public long countByStatus(SensorStatus status) {
        return sensorsById.values().stream().filter(s -> s.getStatus() == status).count();
    }
}
