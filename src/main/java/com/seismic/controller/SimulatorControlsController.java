package com.seismic.controller;

import com.seismic.service.SeismicEventService;
import com.seismic.simulation.ScenarioType;
import com.seismic.ui.FxTasks;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.UiFormat;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;

import java.util.List;

/**
 * Simulator buttons. Every button calls a real service method; nothing here simulates or detects anything.
 * Start/stop/reset can take a moment (thread shutdown, database insert), so they run as background tasks.
 */
public final class SimulatorControlsController {

    private static final String AUTO = "Auto (engine chooses)";

    private final LiveDataBridge bridge;
    private final SeismicEventService service;
    private boolean busy;

    @FXML private Button startButton;
    @FXML private Button stopButton;
    @FXML private Button resetButton;
    @FXML private ComboBox<String> targetCombo;
    @FXML private GridPane scenarioGrid;
    @FXML private Label scenarioStatus;

    public SimulatorControlsController(LiveDataBridge bridge) {
        this.bridge = bridge;
        this.service = bridge.getService();
    }

    @FXML
    private void initialize() {
        targetCombo.getItems().add(AUTO);
        targetCombo.getItems().addAll(service.getNetwork().sensorIds());
        targetCombo.getSelectionModel().selectFirst();
        bridge.runningProperty().addListener((obs, was, running) -> updateButtons());
        bridge.addRefreshListener(this::updateScenarioStatus);
        updateButtons();
    }

    @FXML private void onStart() { lifecycle("Start monitoring", service::start); }
    @FXML private void onStop() { lifecycle("Stop monitoring", service::stop); }

    @FXML
    private void onReset() {
        lifecycle("Reset simulation", () -> {
            service.reset();
            javafx.application.Platform.runLater(bridge::clearLiveData);
        });
    }

    @FXML private void onNormal() { trigger(ScenarioType.NORMAL); }
    @FXML private void onMinorEvent() { trigger(ScenarioType.MINOR_EVENT); }
    @FXML private void onMajorEvent() { trigger(ScenarioType.MAJOR_EVENT); }
    @FXML private void onIsolatedSpike() { trigger(ScenarioType.ISOLATED_SPIKE); }
    @FXML private void onSensorFault() { trigger(ScenarioType.SENSOR_FAULT); }
    @FXML private void onCommunicationFailure() { trigger(ScenarioType.COMMUNICATION_FAILURE); }
    @FXML private void onNoisyData() { trigger(ScenarioType.NOISY_DATA); }
    @FXML private void onInvalidData() { trigger(ScenarioType.INVALID_DATA); }

    private void trigger(ScenarioType type) {
        String selected = targetCombo.getValue();
        String target = selected == null || selected.equals(AUTO) ? null : selected;
        try {
            service.triggerScenario(type, target);
            updateScenarioStatus();
        } catch (IllegalStateException | IllegalArgumentException e) {
            UiFormat.showError("Cannot start scenario", e);
        }
    }

    private void lifecycle(String action, FxTasks.ThrowingRunnable work) {
        busy = true;
        updateButtons();
        scenarioStatus.setText(action + "...");
        FxTasks.runAction(work, () -> {
            busy = false;
            updateButtons();
            updateScenarioStatus();
        }, error -> {
            busy = false;
            updateButtons();
            UiFormat.showError(action + " failed", error);
        });
    }

    private void updateButtons() {
        boolean running = bridge.runningProperty().get();
        startButton.setDisable(busy || running);
        stopButton.setDisable(busy || !running);
        resetButton.setDisable(busy);
        scenarioGrid.setDisable(busy || !running);
    }

    private void updateScenarioStatus() {
        if (busy) {
            return;
        }
        List<String> active = service.getActiveScenarios();
        if (!bridge.runningProperty().get()) {
            scenarioStatus.setText("Start monitoring to enable scenarios.");
        } else if (active.isEmpty()) {
            scenarioStatus.setText("Normal background activity.");
        } else {
            scenarioStatus.setText("Active: " + String.join("; ", active));
        }
    }
}
