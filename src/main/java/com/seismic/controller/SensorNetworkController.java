package com.seismic.controller;

import com.seismic.model.EventStatus;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.service.SeismicEventService;
import com.seismic.ui.FxTasks;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.SensorNetworkCanvas;
import com.seismic.ui.UiFormat;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.layout.Pane;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sensor Network screen: map, sensor table, and details/readings of the selected sensor. */
public final class SensorNetworkController {

    private static final int STORED_READINGS = 200;

    private final LiveDataBridge bridge;
    private final SeismicEventService service;
    private final SensorNetworkCanvas canvas = new SensorNetworkCanvas();
    private final ObservableList<SeismicReading> readingRows = FXCollections.observableArrayList();
    private final XYChart.Series<Number, Number> rawSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> filteredSeries = new XYChart.Series<>();
    private Map<String, Long> eventCounts = new HashMap<>();
    private String selectedSensorId;
    /** True while showing readings loaded from storage, so live refreshes do not overwrite them. */
    private boolean showingStoredReadings;
    private boolean updatingTable;

    @FXML private Pane canvasHolder;
    @FXML private TableView<Sensor> sensorTable;
    @FXML private Label selectedTitle;
    @FXML private Label selectedDetail;
    @FXML private Label readingsSourceLabel;
    @FXML private LineChart<Number, Number> sensorChart;
    @FXML private TableView<SeismicReading> readingsTable;

    public SensorNetworkController(LiveDataBridge bridge) {
        this.bridge = bridge;
        this.service = bridge.getService();
    }

    @FXML
    private void initialize() {
        canvasHolder.getChildren().add(canvas);
        canvas.widthProperty().bind(canvasHolder.widthProperty());
        canvas.heightProperty().bind(canvasHolder.heightProperty());
        canvas.setOnSensorClicked(this::select);

        sensorTable.getColumns().setAll(List.of(
                UiFormat.column("ID", 55, Sensor::getSensorId),
                UiFormat.column("Name", 140, Sensor::getName),
                UiFormat.column("Zone", 70, Sensor::getZone),
                UiFormat.column("Status", 90, s -> s.getStatus().name()),
                UiFormat.column("Reliability", 80, s -> UiFormat.number(s.getReliabilityScore(), 0)),
                UiFormat.column("Last heartbeat", 95, s -> UiFormat.time(s.getLastHeartbeat())),
                UiFormat.column("Latest reading", 250, Sensor::getLatestReadingSummary),
                UiFormat.column("Lat, Lon", 140, s -> UiFormat.number(s.getLocation().latitude(), 4) + ", "
                        + UiFormat.number(s.getLocation().longitude(), 4)),
                UiFormat.column("Events", 60, s -> eventCounts.containsKey(s.getSensorId())
                        ? String.valueOf(eventCounts.get(s.getSensorId())) : "-")));
        sensorTable.setItems(bridge.getSensors());
        sensorTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(Sensor sensor, boolean empty) {
                super.updateItem(sensor, empty);
                getStyleClass().removeIf(c -> c.startsWith("status-"));
                if (!empty && sensor != null) {
                    getStyleClass().add(UiFormat.cssClass("status", sensor.getStatus()));
                }
            }
        });
        sensorTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sensor) -> {
            if (!updatingTable && sensor != null && !sensor.getSensorId().equals(selectedSensorId)) {
                select(sensor.getSensorId());
            }
        });

        readingsTable.getColumns().setAll(List.of(
                UiFormat.column("Time", 80, r -> UiFormat.time(r.getTimestamp())),
                UiFormat.column("Amplitude", 80, r -> UiFormat.number(r.getAmplitude(), 3)),
                UiFormat.column("Filtered", 75, r -> UiFormat.number(r.getFilteredAmplitude(), 3)),
                UiFormat.column("Freq Hz", 65, r -> UiFormat.number(r.getFrequencyHz(), 2)),
                UiFormat.column("Duration s", 75, r -> UiFormat.number(r.getDurationSeconds(), 2)),
                UiFormat.column("Noise", 55, r -> UiFormat.number(r.getNoiseLevel(), 2)),
                UiFormat.column("Quality", 85, r -> r.getQuality().name()),
                UiFormat.column("Note", 220, SeismicReading::getQualityNote)));
        readingsTable.setItems(readingRows);
        readingsTable.setPlaceholder(UiFormat.placeholder("Select a sensor in the table or on the map."));

        rawSeries.setName("raw amplitude");
        filteredSeries.setName("moving average");
        sensorChart.getData().addAll(List.of(rawSeries, filteredSeries));
        sensorChart.setAnimated(false);
        sensorChart.setCreateSymbols(false);

        bridge.addRefreshListener(this::refresh);
        refresh();
    }

    private void refresh() {
        // bridge.getSensors() was just replaced with fresh snapshots; restore the selection by ID.
        updatingTable = true;
        if (selectedSensorId != null) {
            bridge.getSensors().stream().filter(s -> s.getSensorId().equals(selectedSensorId)).findFirst()
                    .ifPresent(s -> sensorTable.getSelectionModel().select(s));
        }
        updatingTable = false;
        List<SeismicEvent> active = bridge.getEvents().stream()
                .filter(e -> e.getStatus() == EventStatus.CANDIDATE || e.getStatus() == EventStatus.CONFIRMED)
                .toList();
        canvas.update(bridge.getSensors(), active, selectedSensorId);
        updateDetails();
        if (selectedSensorId != null && !showingStoredReadings) {
            showReadings(bridge.recentReadings(selectedSensorId), "Live readings of this session (newest at bottom)");
        }
    }

    private void select(String sensorId) {
        selectedSensorId = sensorId;
        showingStoredReadings = false;
        refresh();
    }

    private void updateDetails() {
        if (selectedSensorId == null) {
            selectedTitle.setText("No sensor selected");
            selectedDetail.setText("Click a sensor on the map or in the table.");
            return;
        }
        bridge.getSensors().stream().filter(s -> s.getSensorId().equals(selectedSensorId)).findFirst().ifPresent(s -> {
            selectedTitle.setText(s.getSensorId() + " - " + s.getName() + " (" + s.getZone() + ")");
            selectedDetail.setText("Status " + s.getStatus() + " | reliability " + UiFormat.number(s.getReliabilityScore(), 1)
                    + " | last heartbeat " + UiFormat.time(s.getLastHeartbeat()) + " | " + s.getLatestReadingSummary());
        });
    }

    private void showReadings(List<SeismicReading> readings, String source) {
        readingRows.setAll(readings);
        readingsSourceLabel.setText(source + " - " + readings.size() + " reading(s)");
        rawSeries.getData().clear();
        filteredSeries.getData().clear();
        for (int i = 0; i < readings.size(); i++) {
            SeismicReading r = readings.get(i);
            if (!Double.isNaN(r.getAmplitude())) {
                rawSeries.getData().add(new XYChart.Data<>(i, r.getAmplitude()));
            }
            if (r.hasFilteredAmplitude()) {
                filteredSeries.getData().add(new XYChart.Data<>(i, r.getFilteredAmplitude()));
            }
        }
    }

    @FXML
    private void onLoadStoredReadings() {
        if (selectedSensorId == null) {
            UiFormat.showInfo("No sensor selected", "Select a sensor first.");
            return;
        }
        String sensorId = selectedSensorId;
        FxTasks.run(() -> service.findRecentReadings(sensorId, STORED_READINGS), readings -> {
            showingStoredReadings = true;
            showReadings(readings, (service.isPersistent() ? "Stored in MySQL" : "Stored in memory (demo mode)")
                    + ", newest " + STORED_READINGS);
        }, error -> UiFormat.showError("Could not load readings", error));
    }

    @FXML
    private void onShowLiveReadings() {
        showingStoredReadings = false;
        refresh();
    }

    @FXML
    private void onLoadEventCounts() {
        FxTasks.run(service::countEventsPerSensor, counts -> {
            eventCounts = counts;
            sensorTable.refresh();
        }, error -> UiFormat.showError("Could not count events", error));
    }
}
