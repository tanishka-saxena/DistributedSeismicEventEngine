package com.seismic.ui;

import com.seismic.model.Alert;
import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.service.EngineListener;
import com.seismic.service.SeismicEventService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.chart.XYChart;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hands engine data from background threads to the JavaFX thread.
 *
 * <p>The engine calls the {@link EngineListener} methods on its own threads; they only add to
 * thread-safe {@link ConcurrentLinkedQueue}s. A JavaFX {@link Timeline} running on the JavaFX thread
 * drains those queues every {@value #REFRESH_MILLIS} ms and updates the observable lists the screens
 * are bound to. This is the "never touch a control from a background thread" rule, and it also
 * batches updates, so 15 readings per second do not flood the UI with 15 separate redraws.
 */
public final class LiveDataBridge implements EngineListener {

    static final long REFRESH_MILLIS = 500;
    private static final int MAX_CHART_POINTS = 120;
    private static final int MAX_EVENTS = 300;
    private static final int MAX_ALERTS = 500;
    private static final int MAX_LOG = 300;
    private static final int MAX_READINGS_PER_SENSOR = 120;
    private static final int MAX_PENDING_READINGS = 20_000;

    private final SeismicEventService service;
    private final Map<String, String> zoneBySensor = new HashMap<>();

    // Filled by background threads.
    private final ConcurrentLinkedQueue<SeismicReading> readingInbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingReadings = new AtomicInteger();
    private final ConcurrentLinkedQueue<SeismicEvent> eventInbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Alert> alertInbox = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> activityInbox = new ConcurrentLinkedQueue<>();

    // Owned by the JavaFX thread.
    private final ObservableList<Sensor> sensors = FXCollections.observableArrayList();
    private final ObservableList<SeismicEvent> events = FXCollections.observableArrayList();
    private final ObservableList<Alert> alerts = FXCollections.observableArrayList();
    private final ObservableList<String> activityLog = FXCollections.observableArrayList();
    private final Map<String, XYChart.Series<Number, Number>> zoneSeries = new LinkedHashMap<>();
    private final Map<String, Deque<SeismicReading>> recentBySensor = new HashMap<>();
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final StringProperty lastActivity = new SimpleStringProperty("Ready");
    private final List<Runnable> refreshListeners = new CopyOnWriteArrayList<>();
    private final long startMillis = System.currentTimeMillis();
    private final Timeline timeline;

    public LiveDataBridge(SeismicEventService service) {
        this.service = service;
        for (Sensor sensor : service.getNetwork().sensors()) {
            zoneBySensor.put(sensor.getSensorId(), sensor.getZone());
        }
        for (String zone : service.getNetwork().zones()) {
            XYChart.Series<Number, Number> series = new XYChart.Series<>();
            series.setName(zone);
            zoneSeries.put(zone, series);
        }
        sensors.setAll(service.getSensorSnapshot());
        events.setAll(service.getEventHistory());
        alerts.setAll(service.getAlertManager().history());
        timeline = new Timeline(new KeyFrame(Duration.millis(REFRESH_MILLIS), e -> refresh()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        service.addListener(this);
    }

    /** Must be called on the JavaFX thread. */
    public void start() {
        timeline.play();
    }

    public void stop() {
        timeline.stop();
        service.removeListener(this);
    }

    // ---------------------------------------------------------------- EngineListener (background threads)

    @Override
    public void onReadingProcessed(SeismicReading reading) {
        if (pendingReadings.incrementAndGet() <= MAX_PENDING_READINGS) {
            readingInbox.add(reading);
        } else {
            pendingReadings.decrementAndGet(); // UI is far behind; drop rather than run out of memory
        }
    }

    @Override
    public void onEventUpdated(SeismicEvent event) {
        eventInbox.add(event);
    }

    @Override
    public void onAlert(Alert alert) {
        alertInbox.add(alert);
    }

    @Override
    public void onActivity(String message) {
        activityInbox.add(UiFormat.time(java.time.Instant.now()) + "  " + message);
    }

    // ---------------------------------------------------------------- JavaFX thread

    private void refresh() {
        List<SeismicReading> readings = new ArrayList<>();
        SeismicReading reading;
        while ((reading = readingInbox.poll()) != null) {
            pendingReadings.decrementAndGet();
            readings.add(reading);
        }
        updateCharts(readings);
        drainEvents();
        drainAlerts();
        drainActivity();
        sensors.setAll(service.getSensorSnapshot());
        running.set(service.isRunning());
        refreshListeners.forEach(Runnable::run);
    }

    private void updateCharts(List<SeismicReading> readings) {
        if (readings.isEmpty()) {
            return;
        }
        double x = (System.currentTimeMillis() - startMillis) / 1000.0;
        Map<String, Double> zoneMax = new LinkedHashMap<>();
        for (SeismicReading r : readings) {
            Deque<SeismicReading> deque = recentBySensor.computeIfAbsent(r.getSensorId(), id -> new ArrayDeque<>());
            deque.addLast(r);
            if (deque.size() > MAX_READINGS_PER_SENSOR) {
                deque.removeFirst();
            }
            if (r.hasFilteredAmplitude()) {
                zoneMax.merge(zoneBySensor.getOrDefault(r.getSensorId(), "?"), r.getFilteredAmplitude(), Math::max);
            }
        }
        zoneMax.forEach((zone, value) -> {
            XYChart.Series<Number, Number> series = zoneSeries.get(zone);
            if (series != null) {
                series.getData().add(new XYChart.Data<>(x, value));
                if (series.getData().size() > MAX_CHART_POINTS) {
                    series.getData().remove(0, series.getData().size() - MAX_CHART_POINTS);
                }
            }
        });
    }

    private void drainEvents() {
        SeismicEvent event;
        while ((event = eventInbox.poll()) != null) {
            upsertEvent(event);
        }
    }

    private void upsertEvent(SeismicEvent event) {
        int index = indexOfEvent(event.getEventId());
        if (event.getStatus() == EventStatus.DISMISSED) {
            if (index >= 0) {
                events.remove(index);
            }
            return;
        }
        if (index >= 0) {
            events.set(index, event);
        } else {
            events.add(0, event);
            if (events.size() > MAX_EVENTS) {
                events.remove(MAX_EVENTS, events.size());
            }
        }
    }

    private int indexOfEvent(String eventId) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getEventId().equals(eventId)) {
                return i;
            }
        }
        return -1;
    }

    /** Replaces or adds events loaded from storage (JavaFX thread). */
    public void mergeEvents(Collection<SeismicEvent> loaded) {
        loaded.forEach(this::upsertEvent);
        FXCollections.sort(events, (a, b) -> b.getStartTime().compareTo(a.getStartTime()));
    }

    private void drainAlerts() {
        Alert alert;
        while ((alert = alertInbox.poll()) != null) {
            int index = -1;
            for (int i = 0; i < alerts.size(); i++) {
                if (alerts.get(i).getAlertId().equals(alert.getAlertId())) {
                    index = i;
                    break;
                }
            }
            if (index >= 0) {
                alerts.set(index, alert);
            } else {
                alerts.add(0, alert);
                if (alerts.size() > MAX_ALERTS) {
                    alerts.remove(MAX_ALERTS, alerts.size());
                }
            }
        }
    }

    private void drainActivity() {
        String line;
        while ((line = activityInbox.poll()) != null) {
            activityLog.add(0, line);
            lastActivity.set(line);
        }
        if (activityLog.size() > MAX_LOG) {
            activityLog.remove(MAX_LOG, activityLog.size());
        }
    }

    /** Clears chart history (used after a simulation reset). */
    public void clearLiveData() {
        zoneSeries.values().forEach(s -> s.getData().clear());
        recentBySensor.clear();
    }

    /** Called after every refresh, on the JavaFX thread. */
    public void addRefreshListener(Runnable listener) {
        refreshListeners.add(listener);
    }

    public List<SeismicReading> recentReadings(String sensorId) {
        Deque<SeismicReading> deque = recentBySensor.get(sensorId);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    public ObservableList<Sensor> getSensors() { return sensors; }
    public ObservableList<SeismicEvent> getEvents() { return events; }
    public ObservableList<Alert> getAlerts() { return alerts; }
    public ObservableList<String> getActivityLog() { return activityLog; }
    public Collection<XYChart.Series<Number, Number>> getZoneSeries() { return zoneSeries.values(); }
    public BooleanProperty runningProperty() { return running; }
    public StringProperty lastActivityProperty() { return lastActivity; }
    public SeismicEventService getService() { return service; }
}
