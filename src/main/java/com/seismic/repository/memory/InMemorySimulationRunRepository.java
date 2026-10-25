package com.seismic.repository.memory;

import com.seismic.model.SimulationRun;
import com.seismic.repository.SimulationRunRepository;

import java.util.concurrent.atomic.AtomicLong;

/** Demo-mode run storage: only hands out run numbers. */
public final class InMemorySimulationRunRepository implements SimulationRunRepository {

    private final AtomicLong ids = new AtomicLong();

    @Override
    public SimulationRun create(SimulationRun run) {
        return run.withId(ids.incrementAndGet());
    }

    @Override
    public void finish(SimulationRun run) {
        // nothing to store in demo mode
    }
}
