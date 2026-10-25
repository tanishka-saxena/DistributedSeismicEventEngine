package com.seismic.model;

/** Operational state of a virtual sensor. */
public enum SensorStatus {
    /** Reporting normally; readings take part in event correlation. */
    ACTIVE,
    /** No reading has arrived within the communication timeout. */
    OFFLINE,
    /** Reporting, but its readings are repeatedly invalid or isolated outliers. */
    FAULTY,
    /** Taken out of service on purpose; ignored by detection and timeout checks. */
    MAINTENANCE;

    /** Only ACTIVE sensors contribute readings to multi-sensor correlation. */
    public boolean isEligibleForCorrelation() {
        return this == ACTIVE;
    }
}
