package com.seismic;

import com.seismic.ui.ControllerFactory;
import com.seismic.ui.FxTasks;
import com.seismic.ui.LiveDataBridge;
import com.seismic.ui.UiSmokeTest;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/**
 * JavaFX entry point.
 * <ul>
 *   <li>{@link #init()} runs on the JavaFX launcher thread, so the (possibly slow) database connection is
 *       made before the window appears without freezing the UI thread.</li>
 *   <li>{@link #start(Stage)} builds the window on the JavaFX thread.</li>
 *   <li>{@link #stop()} runs when the window closes and shuts down every background thread.</li>
 * </ul>
 * Run from the project folder (it reads data/ and config/).
 */
public class Main extends Application {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private AppContext context;
    private LiveDataBridge bridge;
    private Exception startupError;

    @Override
    public void init() {
        try {
            context = AppContext.create();
        } catch (Exception e) {
            log.error("Start-up failed", e);
            startupError = e;
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        if (startupError != null) {
            Alert alert = new Alert(Alert.AlertType.ERROR, startupError.getMessage()
                    + "\n\nRun the application from the project folder so that data/sensors.csv is found.");
            alert.setHeaderText("The application could not start");
            alert.showAndWait();
            Platform.exit();
            return;
        }
        bridge = new LiveDataBridge(context.getEventService());
        Parent root = new ControllerFactory(context, bridge).load("main.fxml");
        Scene scene = new Scene(root, 1400, 880);
        scene.getStylesheets().add(Objects.requireNonNull(Main.class.getResource("/css/app.css")).toExternalForm());
        stage.setTitle("Distributed Seismic Event Detection Engine");
        stage.setMinWidth(1150);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();
        bridge.start();

        String smokeTestDir = System.getProperty("seismic.smokeTest");
        if (smokeTestDir != null && !smokeTestDir.isBlank()) {
            new UiSmokeTest(scene, context, Path.of(smokeTestDir)).run();
        }
    }

    @Override
    public void stop() {
        if (bridge != null) {
            bridge.stop();
        }
        FxTasks.shutdown();
        if (context != null) {
            context.close();
        }
        log.info("Application closed");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
