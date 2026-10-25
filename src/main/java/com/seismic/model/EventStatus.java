package com.seismic.model;

/**
 * Lifecycle of a seismic event.
 * <pre>
 * CANDIDATE --(enough sensors and confidence)----------------&gt; CONFIRMED --(activity ends)--&gt; RESOLVED
 * CANDIDATE --(activity ends, 2+ sensors but rules not met)--&gt; UNCERTAIN (needs investigation)
 * CANDIDATE --(activity ends, only one sensor)---------------&gt; DISMISSED (isolated anomaly, not an event)
 * </pre>
 */
public enum EventStatus {
    CANDIDATE, CONFIRMED, UNCERTAIN, RESOLVED, DISMISSED;

    /** True once the event window has closed and the status will not change again. */
    public boolean isFinal() {
        return this == UNCERTAIN || this == RESOLVED || this == DISMISSED;
    }
}
