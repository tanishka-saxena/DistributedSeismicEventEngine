package com.seismic.service;

import com.seismic.model.Alert;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorStatusChange;

/**
 * Receives notifications from {@link SeismicEventService}. Methods are called on the PROCESSING thread
 * (or the persistence thread), never on the JavaFX thread; a UI listener must hand the data over to the
 * JavaFX thread itself (see LiveDataBridge). Implementations must return quickly.
 */
public interface EngineListener {

    default void onReadingProcessed(SeismicReading reading) {
    }

    /** {@code event} is a snapshot copy. */
    default void onEventUpdated(SeismicEvent event) {
    }

    /** A new alert, or an alert whose acknowledgement changed. */
    default void onAlert(Alert alert) {
    }

    default void onSensorStatusChanged(SensorStatusChange change) {
    }

    default void onMonitoringStateChanged(boolean running) {
    }

    /** A line for the activity log (scenario started, persistence problem, ...). */
    default void onActivity(String message) {
    }
}
