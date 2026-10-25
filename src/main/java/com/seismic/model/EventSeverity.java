package com.seismic.model;

/** Severity of a detected event, from lowest to highest. The declaration order is used for comparisons. */
public enum EventSeverity {
    NORMAL, MINOR, MODERATE, MAJOR, CRITICAL;

    public boolean isAtLeast(EventSeverity other) {
        return compareTo(other) >= 0;
    }

    /** Returns the lower of this severity and {@code cap}. */
    public EventSeverity cappedAt(EventSeverity cap) {
        return compareTo(cap) > 0 ? cap : this;
    }

    /** Returns the next higher severity, or CRITICAL if already at the top. */
    public EventSeverity raisedOneLevel() {
        EventSeverity[] all = values();
        return all[Math.min(ordinal() + 1, all.length - 1)];
    }
}
