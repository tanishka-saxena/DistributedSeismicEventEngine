package com.seismic.processing;

import com.seismic.model.FaultFinding;
import com.seismic.model.SensorStatusChange;

import java.util.List;
import java.util.Optional;

/** What SensorFaultDetector concluded after one observation. */
public record FaultUpdate(List<FaultFinding> findings, Optional<SensorStatusChange> statusChange) {

    public static final FaultUpdate NONE = new FaultUpdate(List.of(), Optional.empty());

    public FaultUpdate {
        findings = List.copyOf(findings);
    }
}
