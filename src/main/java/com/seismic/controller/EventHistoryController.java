package com.seismic.controller;

import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.EventStatus;
import com.seismic.model.ReadingSource;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorParticipation;
import com.seismic.service.SeismicEventService;
import com.seismic.ui.FxTasks;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.UiFormat;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.util.List;

/** Event History screen: all detected events and, for the selected one, why the engine decided what it did. */
public final class EventHistoryController {

    private static final String ALL = "All";
    private static final int RELOAD_LIMIT = 300;

    private final LiveDataBridge bridge;
    private final SeismicEventService service;
    private FilteredList<SeismicEvent> filtered;
    private SeismicEvent shown;

    @FXML private ComboBox<String> statusFilter;
    @FXML private ComboBox<String> sourceFilter;
    @FXML private Label storageLabel;
    @FXML private TableView<SeismicEvent> eventsTable;
    @FXML private Label detailTitle;
    @FXML private Label confidenceLabel;
    @FXML private Label agreementLabel;
    @FXML private Label temporalLabel;
    @FXML private Label spatialLabel;
    @FXML private Label signalLabel;
    @FXML private ListView<String> explanationList;
    @FXML private TableView<SensorParticipation> participantsTable;
    @FXML private TableView<SeismicReading> readingsTable;
    @FXML private Label readingsLabel;

    public EventHistoryController(LiveDataBridge bridge) {
        this.bridge = bridge;
        this.service = bridge.getService();
    }

    @FXML
    private void initialize() {
        statusFilter.getItems().add(ALL);
        for (EventStatus status : List.of(EventStatus.CANDIDATE, EventStatus.CONFIRMED, EventStatus.RESOLVED, EventStatus.UNCERTAIN)) {
            statusFilter.getItems().add(status.name());
        }
        statusFilter.getSelectionModel().selectFirst();
        sourceFilter.getItems().addAll(ALL, ReadingSource.SIMULATION.name(), ReadingSource.DATASET.name());
        sourceFilter.getSelectionModel().selectFirst();
        statusFilter.valueProperty().addListener(o -> applyFilter());
        sourceFilter.valueProperty().addListener(o -> applyFilter());
        storageLabel.setText(service.isPersistent()
                ? "History is stored in MySQL and reloaded at start-up."
                : "Demo mode: history exists only in memory for this session.");

        eventsTable.getColumns().setAll(List.of(
                UiFormat.column("Event ID", 210, SeismicEvent::getEventId),
                UiFormat.column("Start (local)", 135, e -> UiFormat.dateTime(e.getStartTime())),
                UiFormat.column("Severity", 80, e -> e.getSeverity().name()),
                UiFormat.column("Confidence", 80, e -> UiFormat.number(e.getConfidenceScore(), 1)),
                UiFormat.column("Sensors", 60, SeismicEvent::getParticipatingSensorCount),
                UiFormat.column("Affected zone", 210, SeismicEvent::getAffectedZoneSummary),
                UiFormat.column("Status", 90, e -> e.getStatus().name()),
                UiFormat.column("Source", 85, e -> e.getSource().name()),
                UiFormat.column("Duration s", 75, e -> UiFormat.number(e.getDurationSeconds(), 1))));
        filtered = new FilteredList<>(bridge.getEvents());
        eventsTable.setItems(filtered);
        eventsTable.setPlaceholder(UiFormat.placeholder("No events yet. Start monitoring and trigger an event, "
                + "or analyse the bundled dataset on the Dataset screen."));
        eventsTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(SeismicEvent event, boolean empty) {
                super.updateItem(event, empty);
                getStyleClass().removeIf(c -> c.startsWith("sev-"));
                if (!empty && event != null) {
                    getStyleClass().add(UiFormat.cssClass("sev", event.getSeverity()));
                }
            }
        });
        eventsTable.getSelectionModel().selectedItemProperty().addListener((obs, old, event) -> {
            if (event != null) {
                showDetails(event);
            }
        });

        participantsTable.getColumns().setAll(List.of(
                UiFormat.column("Sensor", 60, SensorParticipation::getSensorId),
                UiFormat.column("Zone", 70, SensorParticipation::getZone),
                UiFormat.column("Origin", 55, p -> p.isOrigin() ? "yes" : ""),
                UiFormat.column("Distance km", 85, p -> UiFormat.number(p.getDistanceFromOriginKm(), 1)),
                UiFormat.column("First trigger", 85, p -> UiFormat.time(p.getFirstTrigger())),
                UiFormat.column("Peak raw", 70, p -> UiFormat.number(p.getPeakAmplitude(), 3)),
                UiFormat.column("Peak filtered", 85, p -> UiFormat.number(p.getPeakFilteredAmplitude(), 3)),
                UiFormat.column("Freq Hz", 60, p -> UiFormat.number(p.getFirstFrequencyHz(), 2)),
                UiFormat.column("Candidates", 75, SensorParticipation::getCandidateReadings)));
        readingsTable.getColumns().setAll(List.of(
                UiFormat.column("Time", 80, r -> UiFormat.time(r.getTimestamp())),
                UiFormat.column("Sensor", 55, SeismicReading::getSensorId),
                UiFormat.column("Amplitude", 75, r -> UiFormat.number(r.getAmplitude(), 3)),
                UiFormat.column("Filtered", 70, r -> UiFormat.number(r.getFilteredAmplitude(), 3)),
                UiFormat.column("Freq Hz", 60, r -> UiFormat.number(r.getFrequencyHz(), 2)),
                UiFormat.column("Quality", 80, r -> r.getQuality().name()),
                UiFormat.column("Label", 110, SeismicReading::getScenarioLabel)));
        explanationList.setPlaceholder(UiFormat.placeholder("Select an event to see the decision explanation."));
        bridge.addRefreshListener(this::refreshSelected);
    }

    private void applyFilter() {
        String status = statusFilter.getValue();
        String source = sourceFilter.getValue();
        filtered.setPredicate(e -> (status == null || status.equals(ALL) || e.getStatus().name().equals(status))
                && (source == null || source.equals(ALL) || e.getSource().name().equals(source)));
    }

    /** Keeps the details current while a live event is still changing. */
    private void refreshSelected() {
        SeismicEvent selected = eventsTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected != shown) {
            showDetails(selected);
        }
    }

    private void showDetails(SeismicEvent event) {
        boolean sameEvent = shown != null && shown.getEventId().equals(event.getEventId());
        shown = event;
        detailTitle.setText(event.getEventId() + "  |  " + event.getStatus() + "  |  " + event.getSeverity());
        ConfidenceBreakdown c = event.getConfidence();
        confidenceLabel.setText(String.format("Confidence %.1f / 100 (project-defined index, not a probability)", c.score()));
        agreementLabel.setText("Sensor agreement (30%): " + UiFormat.number(c.sensorAgreement(), 2));
        temporalLabel.setText("Temporal consistency (25%): " + UiFormat.factor(c.temporalConsistency()));
        spatialLabel.setText("Spatial consistency (20%): " + UiFormat.factor(c.spatialConsistency()));
        signalLabel.setText("Signal strength/consistency (25%): " + UiFormat.number(c.signalScore(), 2));
        explanationList.getItems().setAll(event.getExplanation());
        participantsTable.getItems().setAll(event.getParticipations());
        if (sameEvent && event.getStatus() == EventStatus.CONFIRMED && !event.getContributingReadings().isEmpty()) {
            readingsTable.getItems().setAll(event.getContributingReadings());
            return;
        }
        readingsLabel.setText("Loading contributing readings...");
        FxTasks.run(() -> service.findContributingReadings(event), readings -> {
            if (shown == event) {
                readingsTable.getItems().setAll(readings);
                readingsLabel.setText(readings.size() + " contributing reading(s)"
                        + (event.getContributingReadings().isEmpty() ? " (loaded from storage with a JOIN query)" : ""));
            }
        }, error -> readingsLabel.setText("Could not load readings: " + error.getMessage()));
    }

    @FXML
    private void onReload() {
        FxTasks.run(() -> service.loadStoredEvents(RELOAD_LIMIT), events -> {
            bridge.mergeEvents(events);
            storageLabel.setText("Reloaded " + events.size() + " stored event(s) from "
                    + (service.isPersistent() ? "MySQL." : "memory (demo mode)."));
        }, error -> UiFormat.showError("Could not reload events", error));
    }
}
