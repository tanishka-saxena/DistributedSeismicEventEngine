package com.seismic.ui;

import com.seismic.model.EventSeverity;
import com.seismic.model.GeoPoint;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicEvent;
import com.seismic.model.Sensor;
import com.seismic.model.SensorStatus;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Draws the virtual sensor network on a JavaFX {@link Canvas} without any external map service.
 * Coordinates are projected with a simple equirectangular projection (fine for a ~70 km region).
 * Sensors are coloured by status, a ring shows the current filtered amplitude, and active events are drawn
 * as circles around their origin with the affected radius. Clicking a sensor selects it.
 */
public final class SensorNetworkCanvas extends Canvas {

    private static final double PADDING = 40;
    private static final double KM_PER_DEGREE = 111.32;
    private static final Color BACKGROUND = Color.web("#101b2b");
    private static final Color GRID = Color.web("#1d2c42");

    private List<Sensor> sensors = List.of();
    private List<SeismicEvent> activeEvents = List.of();
    private String selectedSensorId;
    private Consumer<String> onSensorClicked = id -> { };
    private final Map<String, double[]> screenPositions = new HashMap<>();

    // projection parameters, recomputed on every redraw
    private double minLon;
    private double maxLat;
    private double cosLat;
    private double pixelsPerKm;
    private double offsetX;
    private double offsetY;

    public SensorNetworkCanvas() {
        widthProperty().addListener(o -> redraw());
        heightProperty().addListener(o -> redraw());
        setOnMouseClicked(e -> {
            String nearest = null;
            double best = 15 * 15;
            for (Map.Entry<String, double[]> entry : screenPositions.entrySet()) {
                double dx = entry.getValue()[0] - e.getX();
                double dy = entry.getValue()[1] - e.getY();
                if (dx * dx + dy * dy < best) {
                    best = dx * dx + dy * dy;
                    nearest = entry.getKey();
                }
            }
            if (nearest != null) {
                onSensorClicked.accept(nearest);
            }
        });
    }

    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public double prefWidth(double height) {
        return getWidth();
    }

    @Override
    public double prefHeight(double width) {
        return getHeight();
    }

    public void setOnSensorClicked(Consumer<String> handler) {
        this.onSensorClicked = handler;
    }

    public void update(List<Sensor> sensors, List<SeismicEvent> activeEvents, String selectedSensorId) {
        this.sensors = List.copyOf(sensors);
        this.activeEvents = List.copyOf(activeEvents);
        this.selectedSensorId = selectedSensorId;
        redraw();
    }

    private void redraw() {
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.setFill(BACKGROUND);
        g.fillRect(0, 0, w, h);
        screenPositions.clear();
        if (w < 50 || h < 50 || !computeProjection(w, h)) {
            g.setFill(Color.LIGHTGRAY);
            g.fillText("No sensor coordinates to draw", 20, 30);
            return;
        }
        drawGrid(g, w, h);
        drawZoneLabels(g);
        for (SeismicEvent event : activeEvents) {
            drawEvent(g, event);
        }
        for (Sensor sensor : sensors) {
            drawSensor(g, sensor);
        }
    }

    private boolean computeProjection(double w, double h) {
        double minLat = Double.MAX_VALUE;
        double maxLatSeen = -Double.MAX_VALUE;
        double minLonSeen = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        for (Sensor sensor : sensors) {
            GeoPoint p = sensor.getLocation();
            if (p.isKnown()) {
                minLat = Math.min(minLat, p.latitude());
                maxLatSeen = Math.max(maxLatSeen, p.latitude());
                minLonSeen = Math.min(minLonSeen, p.longitude());
                maxLon = Math.max(maxLon, p.longitude());
            }
        }
        if (minLat == Double.MAX_VALUE) {
            return false;
        }
        cosLat = Math.cos(Math.toRadians((minLat + maxLatSeen) / 2));
        minLon = minLonSeen;
        maxLat = maxLatSeen;
        double widthKm = Math.max(1, (maxLon - minLonSeen) * KM_PER_DEGREE * cosLat);
        double heightKm = Math.max(1, (maxLatSeen - minLat) * KM_PER_DEGREE);
        pixelsPerKm = Math.min((w - 2 * PADDING) / widthKm, (h - 2 * PADDING) / heightKm);
        offsetX = (w - widthKm * pixelsPerKm) / 2;
        offsetY = (h - heightKm * pixelsPerKm) / 2;
        return true;
    }

    private double[] project(GeoPoint p) {
        double xKm = (p.longitude() - minLon) * KM_PER_DEGREE * cosLat;
        double yKm = (maxLat - p.latitude()) * KM_PER_DEGREE;
        return new double[]{offsetX + xKm * pixelsPerKm, offsetY + yKm * pixelsPerKm};
    }

    private void drawGrid(GraphicsContext g, double w, double h) {
        g.setStroke(GRID);
        g.setLineWidth(1);
        double step = 10 * pixelsPerKm; // 10 km grid
        if (step < 8) {
            return;
        }
        for (double x = offsetX % step; x < w; x += step) {
            g.strokeLine(x, 0, x, h);
        }
        for (double y = offsetY % step; y < h; y += step) {
            g.strokeLine(0, y, w, y);
        }
        g.setFill(Color.web("#5d7290"));
        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.LEFT);
        g.fillText("grid: 10 km", 10, h - 10);
    }

    private void drawZoneLabels(GraphicsContext g) {
        Map<String, double[]> sums = new LinkedHashMap<>();
        for (Sensor sensor : sensors) {
            if (sensor.getLocation().isKnown()) {
                double[] xy = project(sensor.getLocation());
                double[] sum = sums.computeIfAbsent(sensor.getZone(), z -> new double[3]);
                sum[0] += xy[0];
                sum[1] += xy[1];
                sum[2]++;
            }
        }
        g.setFont(Font.font("System", FontWeight.BOLD, 13));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.BOTTOM);
        sums.forEach((zone, sum) -> {
            double cx = sum[0] / sum[2];
            double cy = sum[1] / sum[2];
            g.setStroke(Color.web("#2b4161"));
            g.setLineDashes(4, 4);
            g.strokeOval(cx - 6 * pixelsPerKm, cy - 6 * pixelsPerKm, 12 * pixelsPerKm, 12 * pixelsPerKm);
            g.setLineDashes(null);
            g.setFill(Color.web("#8fa8c8"));
            g.fillText(zone.toUpperCase(), cx, cy - 6 * pixelsPerKm - 2);
        });
        g.setTextBaseline(VPos.BASELINE);
    }

    private void drawEvent(GraphicsContext g, SeismicEvent event) {
        if (!event.getOriginLocation().isKnown()) {
            return;
        }
        double[] xy = project(event.getOriginLocation());
        double radius = Math.max(4, event.getAffectedRadiusKm() + 3) * pixelsPerKm;
        Color color = severityColor(event.getSeverity());
        g.setFill(color.deriveColor(0, 1, 1, 0.16));
        g.fillOval(xy[0] - radius, xy[1] - radius, 2 * radius, 2 * radius);
        g.setStroke(color);
        g.setLineWidth(2);
        g.strokeOval(xy[0] - radius, xy[1] - radius, 2 * radius, 2 * radius);
        String label = event.getStatus() + " " + event.getSeverity() + String.format(" (%.0f)", event.getConfidenceScore());
        Font font = Font.font("System", FontWeight.BOLD, 12);
        Text measure = new Text(label);
        measure.setFont(font);
        double textWidth = measure.getLayoutBounds().getWidth();
        double labelY = Math.max(18, xy[1] - radius - 8);
        // Dark box behind the label so it stays readable over zone names and sensors.
        g.setFill(Color.web("#101b2b", 0.85));
        g.fillRoundRect(xy[0] - textWidth / 2 - 6, labelY - 13, textWidth + 12, 18, 6, 6);
        g.setFill(color);
        g.setFont(font);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(label, xy[0], labelY);
    }

    private void drawSensor(GraphicsContext g, Sensor sensor) {
        if (!sensor.getLocation().isKnown()) {
            return;
        }
        double[] xy = project(sensor.getLocation());
        screenPositions.put(sensor.getSensorId(), xy);
        Color color = statusColor(sensor.getStatus());
        double amplitude = sensor.getLatestFilteredAmplitude();
        if (!Double.isNaN(amplitude) && sensor.getStatus() != SensorStatus.OFFLINE) {
            double ring = 8 + Math.min(28, amplitude * 26);
            g.setStroke(amplitudeColor(amplitude));
            g.setLineWidth(2);
            g.strokeOval(xy[0] - ring, xy[1] - ring, 2 * ring, 2 * ring);
        }
        g.setFill(color);
        g.fillOval(xy[0] - 7, xy[1] - 7, 14, 14);
        if (sensor.getLatestQuality() == ReadingQuality.SUSPICIOUS || sensor.getLatestQuality() == ReadingQuality.INVALID) {
            g.setStroke(Color.ORANGE);
            g.setLineWidth(2);
            g.strokeOval(xy[0] - 9, xy[1] - 9, 18, 18);
        }
        if (sensor.getSensorId().equals(selectedSensorId)) {
            g.setStroke(Color.WHITE);
            g.setLineWidth(2.5);
            g.strokeOval(xy[0] - 12, xy[1] - 12, 24, 24);
        }
        g.setFill(Color.WHITE);
        g.setFont(Font.font("System", FontWeight.SEMI_BOLD, 11));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(sensor.getSensorId(), xy[0], xy[1] + 22);
    }

    static Color statusColor(SensorStatus status) {
        return switch (status) {
            case ACTIVE -> Color.web("#2ecc71");
            case OFFLINE -> Color.web("#7f8c8d");
            case FAULTY -> Color.web("#e74c3c");
            case MAINTENANCE -> Color.web("#3498db");
        };
    }

    static Color severityColor(EventSeverity severity) {
        return switch (severity) {
            case NORMAL -> Color.web("#95a5a6");
            case MINOR -> Color.web("#f1c40f");
            case MODERATE -> Color.web("#e67e22");
            case MAJOR -> Color.web("#e74c3c");
            case CRITICAL -> Color.web("#c0392b");
        };
    }

    private static Color amplitudeColor(double amplitude) {
        if (amplitude < 0.30) {
            return Color.web("#3c6e9f");
        }
        if (amplitude < 0.50) {
            return Color.web("#f1c40f");
        }
        return amplitude <= 0.75 ? Color.web("#e67e22") : Color.web("#e74c3c");
    }
}
