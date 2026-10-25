package com.seismic.repository;

import com.seismic.model.SimulationRun;

/** Stores the start and end of live monitoring runs. */
public interface SimulationRunRepository {

    /** Inserts the run and returns it with its generated ID. */
    SimulationRun create(SimulationRun run);

    void finish(SimulationRun run);
}
