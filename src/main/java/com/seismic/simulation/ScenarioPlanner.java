package com.seismic.simulation;

import com.seismic.config.SimulationConfig;
import com.seismic.model.GeoPoint;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Turns a scenario button press into concrete {@link ScenarioEffect}s: where the epicentre is,
 * which sensor misbehaves, and for how long.
 */
public final class ScenarioPlanner {

    /** Minor events only reach the sensors of one zone. */
    static final double MINOR_EVENT_RADIUS_KM = 12;
    /** Major events reach the epicentre zone and the neighbouring zones about 30 km away. */
    static final double MAJOR_EVENT_RADIUS_KM = 35;

    private final SimulationConfig config;
    private final SensorNetwork network;

    public ScenarioPlanner(SimulationConfig config, SensorNetwork network) {
        this.config = config;
        this.network = network;
    }

    /**
     * @param targetSensorId sensor chosen in the UI, or null to let the planner choose
     * @return the effects to activate; empty for NORMAL (which clears effects instead)
     */
    public List<ScenarioEffect> plan(ScenarioType type, Instant start, String targetSensorId, Random random) {
        List<ScenarioEffect> effects = new ArrayList<>();
        switch (type) {
            case NORMAL -> { }
            case MINOR_EVENT -> {
                String zone = targetSensorId != null ? network.require(targetSensorId).getZone() : randomZone(random);
                effects.add(new SeismicWaveEffect(type, jitter(zoneCentre(zone), random), start,
                        config.minorEventSeconds(), config.minorEventPeakAmplitude(), config.attenuationLengthKm(),
                        config.waveSpeedKmPerSecond(), MINOR_EVENT_RADIUS_KM, "MINOR_EVENT"));
            }
            case MAJOR_EVENT -> {
                String zone = targetSensorId != null ? network.require(targetSensorId).getZone() : centralZone();
                effects.add(new SeismicWaveEffect(type, jitter(zoneCentre(zone), random), start,
                        config.majorEventSeconds(), config.majorEventPeakAmplitude(), config.attenuationLengthKm(),
                        config.waveSpeedKmPerSecond(), MAJOR_EVENT_RADIUS_KM, "MAJOR_EVENT"));
            }
            case ISOLATED_SPIKE -> effects.add(new IsolatedSpikeEffect(chooseSensor(targetSensorId, random), start,
                    config.sampleIntervalMillis() * 2 / 1000.0));
            case SENSOR_FAULT -> effects.add(new RepeatedFaultEffect(chooseSensor(targetSensorId, random), start,
                    config.faultSeconds()));
            case COMMUNICATION_FAILURE -> effects.add(new CommunicationDropEffect(chooseSensor(targetSensorId, random),
                    start, config.communicationFailureSeconds()));
            case NOISY_DATA -> effects.add(new NoisyDataEffect(chooseSensor(targetSensorId, random), start,
                    config.noisySeconds()));
            case INVALID_DATA -> effects.add(new InvalidDataEffect(chooseSensor(targetSensorId, random), start,
                    config.invalidDataSeconds()));
        }
        return effects;
    }

    /** Mean position of the sensors in a zone. */
    public GeoPoint zoneCentre(String zone) {
        double lat = 0;
        double lon = 0;
        int count = 0;
        for (Sensor sensor : network.sensors()) {
            if (sensor.getZone().equals(zone) && sensor.getLocation().isKnown()) {
                lat += sensor.getLocation().latitude();
                lon += sensor.getLocation().longitude();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("Zone has no sensors with coordinates: " + zone);
        }
        return new GeoPoint(lat / count, lon / count);
    }

    /** The zone whose centre is closest to the centre of the whole network. */
    String centralZone() {
        GeoPoint networkCentre = networkCentre();
        return network.zones().stream()
                .min(Comparator.comparingDouble(zone -> zoneCentre(zone).distanceKm(networkCentre)))
                .orElseThrow();
    }

    private GeoPoint networkCentre() {
        double lat = 0;
        double lon = 0;
        int count = 0;
        for (Sensor sensor : network.sensors()) {
            if (sensor.getLocation().isKnown()) {
                lat += sensor.getLocation().latitude();
                lon += sensor.getLocation().longitude();
                count++;
            }
        }
        return new GeoPoint(lat / count, lon / count);
    }

    private String randomZone(Random random) {
        List<String> zones = new ArrayList<>(network.zones());
        return zones.get(random.nextInt(zones.size()));
    }

    /** Uses the requested sensor, otherwise a random ACTIVE one (falls back to any sensor). */
    private String chooseSensor(String targetSensorId, Random random) {
        if (targetSensorId != null) {
            return network.require(targetSensorId).getSensorId();
        }
        List<Sensor> candidates = network.sensors().stream()
                .filter(s -> s.getStatus() == SensorStatus.ACTIVE).toList();
        if (candidates.isEmpty()) {
            candidates = network.sensors();
        }
        return candidates.get(random.nextInt(candidates.size())).getSensorId();
    }

    /** Moves the epicentre up to 1 km in a random direction so repeated events are not identical. */
    private static GeoPoint jitter(GeoPoint point, Random random) {
        return point.offsetKm(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1);
    }
}
