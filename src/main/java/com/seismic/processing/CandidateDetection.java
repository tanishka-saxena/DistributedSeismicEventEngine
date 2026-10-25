package com.seismic.processing;

import com.seismic.model.SeismicReading;
import com.seismic.model.SignalLevel;

/**
 * A reading whose amplitude crossed a threshold: a potential event, not a confirmed one.
 *
 * @param level          level of the raw amplitude
 * @param sustainedLevel level of the moving-average amplitude; NORMAL here with a high raw level
 *                       means the signal is a short transient (a spike), not sustained shaking
 */
public record CandidateDetection(SeismicReading reading, SignalLevel level, SignalLevel sustainedLevel) {

    public boolean isSustained() {
        return sustainedLevel.isCandidate();
    }
}
