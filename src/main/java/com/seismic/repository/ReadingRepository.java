package com.seismic.repository;

import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;

import java.util.List;

/** Stores processed readings. */
public interface ReadingRepository {

    /** Saves live readings of a simulation run (runId may be null in memory mode), in one transaction. */
    void saveBatch(List<SeismicReading> readings, Long runId);

    /** The newest {@code limit} readings of a sensor, oldest first. */
    List<SeismicReading> findRecentBySensor(String sensorId, int limit);

    /** Readings of the participating sensors during the event, oldest first. */
    List<SeismicReading> findContributingReadings(SeismicEvent event);

    long count();
}
