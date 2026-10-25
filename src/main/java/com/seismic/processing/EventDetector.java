package com.seismic.processing;

import com.seismic.config.ProcessingConfig.DetectionThresholds;
import com.seismic.model.SeismicReading;
import com.seismic.model.SignalLevel;

import java.util.Objects;
import java.util.Optional;

/**
 * Stage 1 of detection: threshold classification of a single reading.
 *
 * <pre>
 * amplitude &lt; 0.30            -&gt; NORMAL
 * 0.30 &lt;= amplitude &lt; 0.50    -&gt; MINOR candidate
 * 0.50 &lt;= amplitude &lt;= 0.75   -&gt; MODERATE candidate
 * amplitude &gt; 0.75            -&gt; HIGH candidate
 * </pre>
 * These are configurable, arbitrary values for the synthetic amplitude field only. A detection here
 * only marks a candidate; confirmation needs multi-sensor correlation (EventCorrelator).
 */
public final class EventDetector {

    private final DetectionThresholds thresholds;

    public EventDetector(DetectionThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds);
    }

    /** Classifies an amplitude value. NaN (missing) is treated as NORMAL because it carries no evidence. */
    public SignalLevel classify(double amplitude) {
        if (Double.isNaN(amplitude) || amplitude < thresholds.minor()) {
            return SignalLevel.NORMAL;
        }
        if (amplitude < thresholds.moderate()) {
            return SignalLevel.MINOR;
        }
        if (amplitude <= thresholds.high()) {
            return SignalLevel.MODERATE;
        }
        return SignalLevel.HIGH;
    }

    /**
     * Returns a candidate if the reading's raw amplitude is at least MINOR. INVALID readings never
     * produce candidates because their amplitude cannot be trusted.
     */
    public Optional<CandidateDetection> detect(SeismicReading reading) {
        if (!reading.getQuality().hasUsableAmplitude()) {
            return Optional.empty();
        }
        SignalLevel level = classify(reading.getAmplitude());
        if (!level.isCandidate()) {
            return Optional.empty();
        }
        SignalLevel sustained = reading.hasFilteredAmplitude() ? classify(reading.getFilteredAmplitude()) : level;
        return Optional.of(new CandidateDetection(reading, level, sustained));
    }

    public DetectionThresholds getThresholds() {
        return thresholds;
    }
}
