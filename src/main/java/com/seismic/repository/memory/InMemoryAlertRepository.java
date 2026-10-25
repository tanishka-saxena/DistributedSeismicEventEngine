package com.seismic.repository.memory;

import com.seismic.model.Alert;
import com.seismic.repository.AlertRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Demo-mode alert storage, bounded to the newest {@value #MAX_ALERTS} alerts. */
public final class InMemoryAlertRepository implements AlertRepository {

    static final int MAX_ALERTS = 2_000;
    private final LinkedHashMap<String, Alert> alerts = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Alert> eldest) {
            return size() > MAX_ALERTS;
        }
    };

    @Override
    public synchronized void save(Alert alert) {
        alerts.put(alert.getAlertId(), alert);
    }

    @Override
    public synchronized void updateAcknowledgement(Alert alert) {
        alerts.computeIfPresent(alert.getAlertId(), (id, old) -> alert);
    }

    @Override
    public synchronized List<Alert> findRecent(int limit) {
        List<Alert> list = new ArrayList<>(alerts.values());
        Collections.reverse(list);
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }
}
