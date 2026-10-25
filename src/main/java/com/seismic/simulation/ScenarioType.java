package com.seismic.simulation;

/** The eight scenarios the simulator can produce. */
public enum ScenarioType {
    NORMAL("Normal background activity", false),
    MINOR_EVENT("Minor seismic event", false),
    MAJOR_EVENT("Major seismic event", false),
    ISOLATED_SPIKE("Isolated sensor spike", true),
    SENSOR_FAULT("Repeated faulty sensor readings", true),
    COMMUNICATION_FAILURE("Communication failure", true),
    NOISY_DATA("Noisy sensor data", true),
    INVALID_DATA("Invalid or missing data", true);

    private final String displayName;
    private final boolean singleSensor;

    ScenarioType(String displayName, boolean singleSensor) {
        this.displayName = displayName;
        this.singleSensor = singleSensor;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** True if the scenario targets one sensor rather than an area. */
    public boolean isSingleSensor() {
        return singleSensor;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
