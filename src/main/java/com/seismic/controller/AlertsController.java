package com.seismic.controller;

import com.seismic.model.Alert;
import com.seismic.service.SeismicEventService;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.UiFormat;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;

import java.util.List;

/** Alerts screen: history, filtering, and acknowledgement. */
public final class AlertsController {

    private final LiveDataBridge bridge;
    private final SeismicEventService service;
    private FilteredList<Alert> filtered;

    @FXML private CheckBox unacknowledgedOnly;
    @FXML private Label mostUrgentLabel;
    @FXML private TableView<Alert> alertsTable;

    public AlertsController(LiveDataBridge bridge) {
        this.bridge = bridge;
        this.service = bridge.getService();
    }

    @FXML
    private void initialize() {
        alertsTable.getColumns().setAll(List.of(
                UiFormat.column("Time (local)", 135, a -> UiFormat.dateTime(a.getCreatedAt())),
                UiFormat.column("Severity", 75, a -> a.getSeverity().name()),
                UiFormat.column("Type", 165, a -> a.getType().name()),
                UiFormat.column("Description", 470, Alert::getDescription),
                UiFormat.column("Sensor", 60, a -> a.getSensorId() == null ? "" : a.getSensorId()),
                UiFormat.column("Event", 200, a -> a.getEventId() == null ? "" : a.getEventId()),
                UiFormat.column("Ack", 45, a -> a.isAcknowledged() ? "yes" : ""),
                UiFormat.column("Ack time", 80, a -> UiFormat.time(a.getAcknowledgedAt())),
                UiFormat.column("ID", 200, Alert::getAlertId)));
        filtered = new FilteredList<>(bridge.getAlerts());
        alertsTable.setItems(filtered);
        alertsTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        alertsTable.setPlaceholder(UiFormat.placeholder("No alerts."));
        alertsTable.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(Alert alert, boolean empty) {
                super.updateItem(alert, empty);
                getStyleClass().removeIf(c -> c.startsWith("alert-") || c.equals("acknowledged"));
                if (!empty && alert != null) {
                    getStyleClass().add(UiFormat.cssClass("alert", alert.getSeverity()));
                    if (alert.isAcknowledged()) {
                        getStyleClass().add("acknowledged");
                    }
                }
            }
        });
        unacknowledgedOnly.selectedProperty().addListener(o -> applyFilter());
        bridge.addRefreshListener(this::updateMostUrgent);
        updateMostUrgent();
    }

    private void applyFilter() {
        filtered.setPredicate(a -> !unacknowledgedOnly.isSelected() || !a.isAcknowledged());
    }

    private void updateMostUrgent() {
        mostUrgentLabel.setText(service.getAlertManager().mostUrgent()
                .map(a -> "Most urgent (priority queue head): [" + a.getSeverity() + "] " + a.getDescription())
                .orElse("No unacknowledged alerts."));
    }

    @FXML
    private void onAcknowledgeSelected() {
        List<Alert> selected = List.copyOf(alertsTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            UiFormat.showInfo("Nothing selected", "Select one or more alerts first.");
            return;
        }
        selected.forEach(a -> service.acknowledgeAlert(a.getAlertId()));
    }

    @FXML
    private void onAcknowledgeAll() {
        service.acknowledgeAllAlerts();
    }
}
