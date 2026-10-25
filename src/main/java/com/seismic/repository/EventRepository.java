package com.seismic.repository;

import com.seismic.model.EventSeverity;
import com.seismic.model.SeismicEvent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores detected events together with their sensor associations. */
public interface EventRepository {

    /** Inserts or updates the event and replaces its sensor associations, in one transaction. */
    void save(SeismicEvent event);

    /** Saves several events in one transaction. */
    void saveAll(List<SeismicEvent> events);

    /** Newest first, with participations restored. */
    List<SeismicEvent> findRecent(int limit);

    Optional<SeismicEvent> findById(String eventId);

    Map<EventSeverity, Long> countBySeverity();

    /** Number of stored events each sensor took part in (sensors with none are included with 0). */
    Map<String, Long> countEventsPerSensor();
}
