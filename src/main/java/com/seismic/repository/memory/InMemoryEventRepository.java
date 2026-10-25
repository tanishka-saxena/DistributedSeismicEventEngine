package com.seismic.repository.memory;

import com.seismic.model.EventSeverity;
import com.seismic.model.SeismicEvent;
import com.seismic.model.Sensor;
import com.seismic.repository.EventRepository;
import com.seismic.repository.SensorRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Demo-mode event storage, bounded to the newest {@value #MAX_EVENTS} events. */
public final class InMemoryEventRepository implements EventRepository {

    static final int MAX_EVENTS = 1_000;
    private final SensorRepository sensors;
    private final LinkedHashMap<String, SeismicEvent> events = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SeismicEvent> eldest) {
            return size() > MAX_EVENTS;
        }
    };

    public InMemoryEventRepository(SensorRepository sensors) {
        this.sensors = sensors;
    }

    @Override
    public synchronized void save(SeismicEvent event) {
        events.put(event.getEventId(), event.copy());
    }

    @Override
    public synchronized void saveAll(List<SeismicEvent> list) {
        list.forEach(this::save);
    }

    @Override
    public synchronized List<SeismicEvent> findRecent(int limit) {
        List<SeismicEvent> all = new ArrayList<>();
        events.values().forEach(e -> all.add(e.copy()));
        all.sort(Comparator.comparing(SeismicEvent::getStartTime).reversed());
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized Optional<SeismicEvent> findById(String eventId) {
        SeismicEvent event = events.get(eventId);
        return event == null ? Optional.empty() : Optional.of(event.copy());
    }

    @Override
    public synchronized Map<EventSeverity, Long> countBySeverity() {
        Map<EventSeverity, Long> counts = new EnumMap<>(EventSeverity.class);
        for (EventSeverity severity : EventSeverity.values()) {
            counts.put(severity, 0L);
        }
        events.values().forEach(e -> counts.merge(e.getSeverity(), 1L, Long::sum));
        return counts;
    }

    @Override
    public synchronized Map<String, Long> countEventsPerSensor() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Sensor sensor : sensors.findAll()) {
            counts.put(sensor.getSensorId(), 0L);
        }
        for (SeismicEvent event : events.values()) {
            event.getParticipatingSensorIds().forEach(id -> counts.merge(id, 1L, Long::sum));
        }
        return Collections.unmodifiableMap(counts);
    }
}
