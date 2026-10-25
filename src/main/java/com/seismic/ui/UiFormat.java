package com.seismic.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.beans.property.ReadOnlyObjectWrapper;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.Function;

/** Formatting and small UI helpers shared by the controllers. Times are shown in the computer's local zone. */
public final class UiFormat {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private UiFormat() {
    }

    public static String time(Instant instant) {
        return instant == null ? "-" : TIME.format(instant);
    }

    public static String dateTime(Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant);
    }

    public static String number(double value, int decimals) {
        return Double.isNaN(value) ? "-" : String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    public static String factor(OptionalDouble value) {
        return value.isPresent() ? number(value.getAsDouble(), 2) : "n/a";
    }

    /** Creates a column whose cell value is computed by {@code getter}; avoids reflection-based factories. */
    public static <S, T> TableColumn<S, T> column(String title, double width, Function<S, T> getter) {
        TableColumn<S, T> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(getter.apply(cell.getValue())));
        return column;
    }

    public static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("placeholder");
        label.setWrapText(true);
        return label;
    }

    public static void showError(String title, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        showError(title, message);
    }

    public static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(title);
        alert.setTitle("Seismic Engine");
        alert.showAndWait();
    }

    public static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        alert.setHeaderText(title);
        alert.setTitle("Seismic Engine");
        alert.showAndWait();
    }

    /** CSS class for a severity or status name, e.g. "sev-major", "status-offline". */
    public static String cssClass(String prefix, Enum<?> value) {
        return prefix + "-" + value.name().toLowerCase(Locale.ROOT);
    }
}
