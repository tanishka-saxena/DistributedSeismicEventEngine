package com.seismic.ui;

import com.seismic.AppContext;
import com.seismic.service.SeismicEventService;
import com.seismic.simulation.ScenarioType;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Optional scripted UI run, enabled with {@code -Dseismic.smokeTest=<folder>}. It presses the real buttons,
 * triggers scenarios through the real service, saves a PNG screenshot of every screen into the folder and
 * then closes the application. Used to verify that the GUI starts and shows live data; it is not used in
 * normal operation.
 */
public final class UiSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(UiSmokeTest.class);

    private final Scene scene;
    private final SeismicEventService service;
    private final Path outputDir;

    public UiSmokeTest(Scene scene, AppContext context, Path outputDir) {
        this.scene = scene;
        this.service = context.getEventService();
        this.outputDir = outputDir;
    }

    public void run() {
        Timeline script = new Timeline(
                at(1.5, () -> screenshot("01-dashboard-idle")),
                at(2.0, () -> click("#startButton")),
                at(5.0, () -> service.triggerScenario(ScenarioType.MAJOR_EVENT, null)),
                at(6.0, () -> service.triggerScenario(ScenarioType.COMMUNICATION_FAILURE, "S05")),
                at(7.0, () -> service.triggerScenario(ScenarioType.SENSOR_FAULT, "S11")),
                at(13.0, () -> screenshot("02-dashboard-major-event")),
                at(13.5, () -> select("#navNetwork")),
                at(13.8, () -> selectFirstRow("#sensorTable")),
                at(14.5, () -> screenshot("03-sensor-network")),
                at(15.0, () -> select("#navEvents")),
                at(15.5, () -> selectFirstRow("#eventsTable")),
                at(16.5, () -> screenshot("04-event-history")),
                at(17.0, () -> select("#navAlerts")),
                at(17.5, () -> screenshot("05-alerts")),
                at(18.0, () -> select("#navDataset")),
                at(18.5, () -> click("#loadBundledButton")),
                at(21.0, () -> click("#analyzeButton")),
                at(25.0, () -> screenshot("06-dataset-analysis")),
                at(26.0, () -> {
                    log.info("UI smoke test finished; screenshots in {}", outputDir.toAbsolutePath());
                    Platform.exit();
                }));
        script.play();
    }

    private static KeyFrame at(double seconds, Runnable action) {
        return new KeyFrame(Duration.seconds(seconds), e -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.error("Smoke test step failed", ex);
            }
        });
    }

    private void click(String selector) {
        Node node = scene.lookup(selector);
        if (node instanceof ButtonBase button) {
            button.fire();
        } else {
            log.error("Smoke test: no button {}", selector);
        }
    }

    private void select(String selector) {
        if (scene.lookup(selector) instanceof ToggleButton toggle) {
            toggle.setSelected(true);
        }
    }

    private void selectFirstRow(String selector) {
        if (scene.lookup(selector) instanceof TableView<?> table && !table.getItems().isEmpty()) {
            table.getSelectionModel().selectFirst();
        }
    }

    private void screenshot(String name) {
        try {
            Files.createDirectories(outputDir);
            WritableImage image = scene.snapshot(null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            PixelReader pixels = image.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, pixels.getArgb(x, y));
                }
            }
            Path file = outputDir.resolve(name + ".png");
            ImageIO.write(buffered, "png", file.toFile());
            log.info("Saved {}", file);
        } catch (IOException e) {
            log.error("Could not save screenshot {}", name, e);
        }
    }
}
