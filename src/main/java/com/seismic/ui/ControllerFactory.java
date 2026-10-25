package com.seismic.ui;

import com.seismic.AppContext;
import com.seismic.controller.AlertsController;
import com.seismic.controller.DashboardController;
import com.seismic.controller.DatasetController;
import com.seismic.controller.EventHistoryController;
import com.seismic.controller.MainController;
import com.seismic.controller.SensorNetworkController;
import com.seismic.controller.SimulatorControlsController;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.util.Callback;

import java.io.IOException;
import java.net.URL;

/**
 * Lets FXMLLoader create controllers through their constructors, so each controller receives exactly the
 * services it needs (constructor dependency injection) instead of reaching for global state.
 * FXMLLoader also uses this factory for {@code fx:include}d files.
 */
public final class ControllerFactory implements Callback<Class<?>, Object> {

    private final AppContext context;
    private final LiveDataBridge bridge;

    public ControllerFactory(AppContext context, LiveDataBridge bridge) {
        this.context = context;
        this.bridge = bridge;
    }

    @Override
    public Object call(Class<?> type) {
        if (type == MainController.class) {
            return new MainController(context, bridge, this);
        }
        if (type == DashboardController.class) {
            return new DashboardController(bridge);
        }
        if (type == SensorNetworkController.class) {
            return new SensorNetworkController(bridge);
        }
        if (type == DatasetController.class) {
            return new DatasetController(context.getDatasetService());
        }
        if (type == EventHistoryController.class) {
            return new EventHistoryController(bridge);
        }
        if (type == AlertsController.class) {
            return new AlertsController(bridge);
        }
        if (type == SimulatorControlsController.class) {
            return new SimulatorControlsController(bridge);
        }
        throw new IllegalArgumentException("No factory entry for controller " + type.getName());
    }

    /** Loads an FXML view from /fxml with this factory. */
    public Parent load(String fxmlName) throws IOException {
        URL resource = ControllerFactory.class.getResource("/fxml/" + fxmlName);
        if (resource == null) {
            throw new IOException("Missing FXML resource /fxml/" + fxmlName);
        }
        FXMLLoader loader = new FXMLLoader(resource);
        loader.setControllerFactory(this);
        return loader.load();
    }
}
