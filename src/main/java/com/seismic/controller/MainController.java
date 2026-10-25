package com.seismic.controller;

import com.seismic.AppContext;
import com.seismic.service.SeismicEventService;
import com.seismic.ui.ControllerFactory;
import com.seismic.ui.LiveDataBridge;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Main window: navigation, header status pills and status bar. Each screen has its own controller. */
public final class MainController {

    private final AppContext context;
    private final LiveDataBridge bridge;
    private final ControllerFactory factory;
    private final Map<Toggle, Node> views = new HashMap<>();

    @FXML private StackPane contentArea;
    @FXML private ToggleButton navDashboard;
    @FXML private ToggleButton navNetwork;
    @FXML private ToggleButton navDataset;
    @FXML private ToggleButton navEvents;
    @FXML private ToggleButton navAlerts;
    @FXML private Label monitoringPill;
    @FXML private Label storagePill;
    @FXML private HBox demoBanner;
    @FXML private Label demoBannerText;
    @FXML private Label statusLabel;
    @FXML private Label throughputLabel;

    public MainController(AppContext context, LiveDataBridge bridge, ControllerFactory factory) {
        this.context = context;
        this.bridge = bridge;
        this.factory = factory;
    }

    @FXML
    private void initialize() {
        ToggleGroup group = new ToggleGroup();
        register(group, navDashboard, "dashboard.fxml");
        register(group, navNetwork, "sensor_network.fxml");
        register(group, navDataset, "dataset.fxml");
        register(group, navEvents, "event_history.fxml");
        register(group, navAlerts, "alerts.fxml");
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                old.setSelected(true); // keep one screen selected
            } else {
                contentArea.getChildren().setAll(views.get(selected));
            }
        });
        navDashboard.setSelected(true);

        SeismicEventService service = context.getEventService();
        boolean persistent = service.isPersistent();
        storagePill.setText(persistent ? "MySQL connected" : "DEMO MODE - in memory only");
        storagePill.getStyleClass().add(persistent ? "pill-ok" : "pill-warn");
        storagePill.setTooltip(new Tooltip(service.getStorageDescription()));
        demoBanner.setVisible(!persistent);
        demoBanner.setManaged(!persistent);
        demoBannerText.setText("Demo mode: MySQL is not connected, so readings, events and alerts are kept in memory "
                + "only and will be lost when the application closes. Reason: " + service.getStorageDescription());

        bridge.runningProperty().addListener((obs, was, running) -> updateMonitoringPill(running));
        updateMonitoringPill(bridge.runningProperty().get());
        statusLabel.textProperty().bind(bridge.lastActivityProperty());
        bridge.addRefreshListener(this::updateThroughput);
    }

    private void register(ToggleGroup group, ToggleButton button, String fxml) {
        button.setToggleGroup(group);
        try {
            views.put(button, factory.load(fxml));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + fxml, e);
        }
    }

    private void updateMonitoringPill(boolean running) {
        monitoringPill.setText(running ? "MONITORING" : "STOPPED");
        monitoringPill.getStyleClass().removeAll("pill-ok", "pill-idle");
        monitoringPill.getStyleClass().add(running ? "pill-ok" : "pill-idle");
    }

    private void updateThroughput() {
        SeismicEventService service = context.getEventService();
        throughputLabel.setText(String.format("Readings processed: %,d   |   Queue: %d   |   Isolated anomalies: %d",
                service.getReadingsProcessed(), service.getQueueSize(), service.getIsolatedAnomalies()));
    }
}
