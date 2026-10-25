package com.seismic.controller;

import com.seismic.model.Alert;
import com.seismic.model.AlertSeverity;
import com.seismic.model.EventSeverity;
import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.Sensor;
import com.seismic.model.SensorStatus;
import com.seismic.service.SeismicEventService;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.UiFormat;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dashboard. Every number shown is computed from live application state (sensor snapshots, event history,
 * alert manager); nothing is a fixed value.
 */
public final class DashboardController {

    private final LiveDataBridge bridge;
    private final SeismicEventService service;
    private final Map<EventSeverity, XYChart.Data<String, Number>> severityBars = new EnumMap<>(EventSeverity.class);

    @FXML private Label totalSensorsValue;
    @FXML private Label activeValue;
    @FXML private Label offlineValue;
    @FXML private Label faultyValue;
    @FXML private Label eventsValue;
    @FXML private Label anomaliesValue;
    @FXML private Label readingsValue;
    @FXML private Label alertsValue;
    @FXML private Label systemStatusLabel;
    @FXML private Label systemStatusDetail;
    @FXML private Label latestEventLabel;
    @FXML private Label latestEventDetail;
    @FXML private ProgressBar agreementBar;
    @FXML private ProgressBar temporalBar;
    @FXML private ProgressBar spatialBar;
    @FXML private ProgressBar signalBar;
    @FXML private Label agreementValue;
    @FXML private Label temporalValue;
    @FXML private Label spatialValue;
    @FXML private Label signalValue;
    @FXML private LineChart<Number, Number> amplitudeChart;
    @FXML private BarChart<String, Number> severityChart;
    @FXML private ListView<Alert> recentAlertsList;
    @FXML private ListView<String> activityList;

    public DashboardController(LiveDataBridge bridge) {
        this.bridge = bridge;
        this.service = bridge.getService();
    }

    @FXML
    private void initialize() {
        amplitudeChart.getData().addAll(bridge.getZoneSeries());
        amplitudeChart.setCreateSymbols(false);
        amplitudeChart.setAnimated(false);

        XYChart.Series<String, Number> severitySeries = new XYChart.Series<>();
        severitySeries.setName("Events");
        for (EventSeverity severity : EventSeverity.values()) {
            XYChart.Data<String, Number> bar = new XYChart.Data<>(severity.name(), 0);
            severityBars.put(severity, bar);
            severitySeries.getData().add(bar);
        }
        severityChart.getData().add(severitySeries);
        severityChart.setAnimated(false);
        severityChart.setLegendVisible(false);
        NumberAxis countAxis = (NumberAxis) severityChart.getYAxis();
        countAxis.setAutoRanging(false); // event counts are whole numbers
        countAxis.setLowerBound(0);
        countAxis.setTickUnit(1);

        recentAlertsList.setItems(bridge.getAlerts());
        recentAlertsList.setPlaceholder(UiFormat.placeholder("No alerts yet."));
        recentAlertsList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Alert alert, boolean empty) {
                super.updateItem(alert, empty);
                getStyleClass().removeIf(c -> c.startsWith("alert-"));
                if (empty || alert == null) {
                    setText(null);
                } else {
                    setText(UiFormat.time(alert.getCreatedAt()) + "  [" + alert.getSeverity() + "] "
                            + (alert.isAcknowledged() ? "(ack) " : "") + alert.getDescription());
                    getStyleClass().add(UiFormat.cssClass("alert", alert.getSeverity()));
                }
            }
        });
        activityList.setItems(bridge.getActivityLog());
        activityList.setPlaceholder(UiFormat.placeholder("Press Start in the simulator panel to begin monitoring."));
        bridge.addRefreshListener(this::refresh);
        refresh();
    }

    private void refresh() {
        List<Sensor> sensors = bridge.getSensors();
        long active = sensors.stream().filter(s -> s.getStatus() == SensorStatus.ACTIVE).count();
        long offline = sensors.stream().filter(s -> s.getStatus() == SensorStatus.OFFLINE).count();
        long faulty = sensors.stream().filter(s -> s.getStatus() == SensorStatus.FAULTY).count();
        totalSensorsValue.setText(String.valueOf(sensors.size()));
        activeValue.setText(String.valueOf(active));
        offlineValue.setText(String.valueOf(offline));
        faultyValue.setText(String.valueOf(faulty));

        List<SeismicEvent> detected = bridge.getEvents().stream()
                .filter(e -> e.getStatus() != EventStatus.CANDIDATE).toList();
        eventsValue.setText(String.valueOf(detected.size()));
        anomaliesValue.setText(String.valueOf(service.getIsolatedAnomalies()));
        readingsValue.setText(String.format("%,d", service.getReadingsProcessed()));
        alertsValue.setText(String.valueOf(service.getAlertManager().unacknowledgedCount()));

        Map<EventSeverity, Integer> counts = new EnumMap<>(EventSeverity.class);
        detected.forEach(e -> counts.merge(e.getSeverity(), 1, Integer::sum));
        severityBars.forEach((severity, bar) -> bar.setYValue(counts.getOrDefault(severity, 0)));
        int max = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        NumberAxis countAxis = (NumberAxis) severityChart.getYAxis();
        countAxis.setUpperBound(Math.max(4, max + 1));

        updateSystemStatus(offline, faulty);
        updateLatestEvent();
    }

    /** Status derived from the current state, most serious condition first. */
    private void updateSystemStatus(long offline, long faulty) {
        Optional<SeismicEvent> ongoing = bridge.getEvents().stream()
                .filter(e -> e.getStatus() == EventStatus.CONFIRMED).findFirst();
        Optional<Alert> urgent = service.getAlertManager().mostUrgent();
        String status;
        String detail;
        String style;
        if (ongoing.isPresent()) {
            status = "SEISMIC EVENT IN PROGRESS";
            detail = ongoing.get().getSeverity() + " event " + ongoing.get().getEventId() + " in "
                    + ongoing.get().getAffectedZoneSummary();
            style = "status-alarm";
        } else if (urgent.isPresent() && urgent.get().getSeverity().compareTo(AlertSeverity.HIGH) >= 0) {
            status = "ATTENTION REQUIRED";
            detail = "Unacknowledged " + urgent.get().getSeverity() + " alert: " + urgent.get().getDescription();
            style = "status-warning";
        } else if (offline + faulty > 0) {
            status = "DEGRADED NETWORK";
            detail = offline + " offline, " + faulty + " suspected faulty sensor(s)";
            style = "status-warning";
        } else if (!service.isRunning()) {
            status = "IDLE";
            detail = "Monitoring is stopped.";
            style = "status-idle";
        } else {
            status = "NORMAL";
            detail = "All sensors reporting; no correlated activity.";
            style = "status-normal";
        }
        systemStatusLabel.setText(status);
        systemStatusDetail.setText(detail);
        systemStatusLabel.getStyleClass().removeIf(c -> c.startsWith("status-"));
        systemStatusLabel.getStyleClass().add(style);
    }

    private void updateLatestEvent() {
        // Prefer the newest event that passed correlation; fall back to a candidate still being assembled.
        Optional<SeismicEvent> latest = bridge.getEvents().stream()
                .filter(e -> e.getStatus() != EventStatus.CANDIDATE).findFirst()
                .or(() -> bridge.getEvents().stream().findFirst());
        if (latest.isEmpty()) {
            latestEventLabel.setText("No events detected yet");
            latestEventDetail.setText("Trigger a minor or major event, or analyse the bundled dataset.");
            setFactor(agreementBar, agreementValue, Double.NaN);
            setFactor(temporalBar, temporalValue, Double.NaN);
            setFactor(spatialBar, spatialValue, Double.NaN);
            setFactor(signalBar, signalValue, Double.NaN);
            return;
        }
        SeismicEvent e = latest.get();
        latestEventLabel.setText(String.format("%s  %s  confidence %.1f", e.getSeverity(), e.getStatus(), e.getConfidenceScore()));
        latestEventDetail.setText(e.getEventId() + " | " + e.getAffectedZoneSummary() + " | "
                + e.getParticipatingSensorCount() + " sensors | started " + UiFormat.time(e.getStartTime()));
        var c = e.getConfidence();
        setFactor(agreementBar, agreementValue, c.sensorAgreement());
        setFactor(temporalBar, temporalValue, c.temporalConsistency().orElse(Double.NaN));
        setFactor(spatialBar, spatialValue, c.spatialConsistency().orElse(Double.NaN));
        setFactor(signalBar, signalValue, c.signalScore());
    }

    private static void setFactor(ProgressBar bar, Label label, double value) {
        bar.setProgress(Double.isNaN(value) ? 0 : value);
        label.setText(Double.isNaN(value) ? "n/a" : UiFormat.number(value, 2));
    }
}
