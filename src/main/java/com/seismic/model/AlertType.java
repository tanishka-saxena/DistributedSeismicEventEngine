package com.seismic.model;

/** Reason an alert was raised. */
public enum AlertType {
    SEISMIC_EVENT,
    MAJOR_EVENT,
    LOW_CONFIDENCE_EVENT,
    ISOLATED_ANOMALY,
    SENSOR_FAULT,
    COMMUNICATION_FAILURE,
    SENSOR_RECOVERED,
    PROCESSING_ERROR
}
