package com.seismic.repository.memory;

import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorParticipation;
import com.seismic.repository.ReadingRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo-mode reading storage: the newest {@value #MAX_PER_SENSOR} readings of each sensor, so a long
 * session cannot exhaust memory. Methods are synchronized because the persistence thread writes while
 * UI background tasks read.
 */
public final class InMemoryReadingRepository implements ReadingRepository {

    static final int MAX_PER_SENSOR = 600;
    private final Map<String, Deque<SeismicReading>> bySensor = new HashMap<>();
    private long total;

    @Override
    public synchronized void saveBatch(List<SeismicReading> readings, Long runId) {
        for (SeismicReading reading : readings) {
            Deque<SeismicReading> deque = bySensor.computeIfAbsent(reading.getSensorId(), id -> new ArrayDeque<>());
            deque.addLast(reading);
            if (deque.size() > MAX_PER_SENSOR) {
                deque.removeFirst();
            }
            total++;
        }
    }

    @Override
    public synchronized List<SeismicReading> findRecentBySensor(String sensorId, int limit) {
        Deque<SeismicReading> deque = bySensor.get(sensorId);
        if (deque == null) {
            return List.of();
        }
        List<SeismicReading> all = new ArrayList<>(deque);
        return new ArrayList<>(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    @Override
    public synchronized List<SeismicReading> findContributingReadings(SeismicEvent event) {
        List<SeismicReading> result = new ArrayList<>();
        for (SensorParticipation p : event.getParticipations()) {
            for (SeismicReading r : bySensor.getOrDefault(p.getSensorId(), new ArrayDeque<>())) {
                if (!r.getTimestamp().isBefore(p.getFirstTrigger()) && !r.getTimestamp().isAfter(p.getLastTrigger())) {
                    result.add(r);
                }
            }
        }
        result.sort(Comparator.comparing(SeismicReading::getTimestamp).thenComparing(SeismicReading::getSensorId));
        return result;
    }

    @Override
    public synchronized long count() {
        return total;
    }
}
