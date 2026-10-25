package com.seismic.processing;

import com.seismic.model.FaultFinding;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorStatusChange;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that happened while processing one reading or one timer tick. Events are copies
 * (snapshots), so the result can be handed to other threads safely.
 */
public final class PipelineResult {

    private SeismicReading reading;
    private boolean candidate;
    private final List<SeismicEvent> updatedEvents = new ArrayList<>();
    private final List<SensorStatusChange> statusChanges = new ArrayList<>();
    private final List<FaultFinding> faultFindings = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    void setReading(SeismicReading reading, boolean candidate) {
        this.reading = reading;
        this.candidate = candidate;
    }

    void addUpdatedEvent(SeismicEvent snapshot) {
        updatedEvents.removeIf(e -> e.getEventId().equals(snapshot.getEventId()));
        updatedEvents.add(snapshot);
    }

    void addStatusChange(SensorStatusChange change) {
        statusChanges.add(change);
    }

    void addFaultUpdate(FaultUpdate update) {
        faultFindings.addAll(update.findings());
        update.statusChange().ifPresent(statusChanges::add);
    }

    void addFaultFindings(List<FaultFinding> findings) {
        faultFindings.addAll(findings);
    }

    void addNote(String note) {
        notes.add(note);
    }

    /** The processed (validated and filtered) reading, or null for a tick or a rejected reading. */
    public SeismicReading getReading() { return reading; }
    public boolean isCandidate() { return candidate; }
    public List<SeismicEvent> getUpdatedEvents() { return List.copyOf(updatedEvents); }
    public List<SensorStatusChange> getStatusChanges() { return List.copyOf(statusChanges); }
    public List<FaultFinding> getFaultFindings() { return List.copyOf(faultFindings); }
    public List<String> getNotes() { return List.copyOf(notes); }

    public boolean isEmpty() {
        return reading == null && updatedEvents.isEmpty() && statusChanges.isEmpty()
                && faultFindings.isEmpty() && notes.isEmpty();
    }
}
