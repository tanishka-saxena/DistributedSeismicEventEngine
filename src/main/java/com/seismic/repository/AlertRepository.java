package com.seismic.repository;

import com.seismic.model.Alert;

import java.util.List;

/** Stores alerts and their acknowledgement state. */
public interface AlertRepository {

    void save(Alert alert);

    void updateAcknowledgement(Alert alert);

    /** Newest first. */
    List<Alert> findRecent(int limit);
}
