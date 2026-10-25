package com.seismic.repository.memory;

import com.seismic.model.Sensor;
import com.seismic.repository.SensorRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/** Demo-mode sensor storage. Keeps copies so callers cannot change stored state by accident. */
public final class InMemorySensorRepository implements SensorRepository {

    private final Map<String, Sensor> sensors = new ConcurrentSkipListMap<>();

    @Override
    public void saveAll(Collection<Sensor> list) {
        list.forEach(s -> sensors.put(s.getSensorId(), s.copy()));
    }

    @Override
    public void updateState(Sensor sensor) {
        sensors.put(sensor.getSensorId(), sensor.copy());
    }

    @Override
    public List<Sensor> findAll() {
        List<Sensor> result = new ArrayList<>();
        sensors.values().forEach(s -> result.add(s.copy()));
        return result;
    }
}
