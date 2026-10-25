package com.seismic.repository;

import com.seismic.model.Sensor;

import java.util.Collection;
import java.util.List;

/** Stores sensor definitions and their latest state. */
public interface SensorRepository {

    /** Inserts new sensors and updates existing ones, in one transaction. */
    void saveAll(Collection<Sensor> sensors);

    /** Updates status, reliability and heartbeat of one sensor. */
    void updateState(Sensor sensor);

    List<Sensor> findAll();
}
