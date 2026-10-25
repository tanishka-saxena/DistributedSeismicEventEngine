package com.seismic.processing;

import com.seismic.config.ProcessingConfig.SeverityRules;
import com.seismic.model.EventSeverity;
import com.seismic.model.SeismicEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stage 4: assigns a severity, separately from candidate detection (stage 1) and confirmation (stage 3).
 *
 * <p>Rules, applied in order:
 * <ol>
 *   <li><b>Base</b>: threshold level of the PEAK FILTERED amplitude (NORMAL / MINOR / MODERATE / MAJOR).
 *       The moving average is used so that a one-sample spike cannot set the severity.</li>
 *   <li><b>Critical</b>: MAJOR becomes CRITICAL when peak filtered amplitude &ge; criticalAmplitude (1.0),
 *       sensors &ge; criticalMinSensors (6), duration &ge; criticalMinDurationSeconds (5 s) and
 *       confidence &ge; criticalMinConfidence (75).</li>
 *   <li><b>Widespread</b>: MINOR or MODERATE is raised one level when sensors &ge; widespreadMinSensors (6)
 *       and affected radius &ge; widespreadRadiusKm (20 km).</li>
 *   <li><b>Caps</b>: fewer sensors than needed for confirmation caps the severity at MINOR; confidence below
 *       the confirmation threshold caps it at MODERATE. So one high reading can never be CRITICAL.</li>
 * </ol>
 */
public final class SeverityClassifier {

    /** The chosen severity and the rules that produced it. */
    public record Decision(EventSeverity severity, List<String> reasons) {
    }

    private final SeverityRules rules;
    private final EventDetector detector;
    private final int minSensorsForConfirmation;
    private final double confirmationThreshold;

    public SeverityClassifier(SeverityRules rules, EventDetector detector, int minSensorsForConfirmation,
                              double confirmationThreshold) {
        this.rules = rules;
        this.detector = detector;
        this.minSensorsForConfirmation = minSensorsForConfirmation;
        this.confirmationThreshold = confirmationThreshold;
    }

    public Decision classify(SeismicEvent event) {
        List<String> reasons = new ArrayList<>();
        double peak = event.getPeakFilteredAmplitude();
        int sensors = event.getParticipatingSensorCount();
        double duration = event.getDurationSeconds();
        double radius = event.getAffectedRadiusKm();
        double confidence = event.getConfidenceScore();

        EventSeverity severity = detector.classify(peak).toBaseSeverity();
        reasons.add(String.format(Locale.ROOT, "base %s from peak filtered amplitude %.3f", severity, peak));

        if (severity == EventSeverity.MAJOR && peak >= rules.criticalAmplitude()
                && sensors >= rules.criticalMinSensors() && duration >= rules.criticalMinDurationSeconds()
                && confidence >= rules.criticalMinConfidence()) {
            severity = EventSeverity.CRITICAL;
            reasons.add(String.format(Locale.ROOT, "raised to CRITICAL: peak %.2f >= %.2f, %d sensors >= %d, "
                            + "%.1f s >= %.1f s, confidence %.0f >= %.0f", peak, rules.criticalAmplitude(), sensors,
                    rules.criticalMinSensors(), duration, rules.criticalMinDurationSeconds(), confidence,
                    rules.criticalMinConfidence()));
        } else if ((severity == EventSeverity.MINOR || severity == EventSeverity.MODERATE)
                && sensors >= rules.widespreadMinSensors() && radius >= rules.widespreadRadiusKm()) {
            severity = severity.raisedOneLevel();
            reasons.add(String.format(Locale.ROOT, "raised to %s: widespread (%d sensors over %.0f km)",
                    severity, sensors, radius));
        }

        if (sensors < minSensorsForConfirmation && severity.isAtLeast(EventSeverity.MODERATE)) {
            severity = EventSeverity.MINOR;
            reasons.add(String.format(Locale.ROOT, "capped at MINOR: only %d sensor(s), %d needed",
                    sensors, minSensorsForConfirmation));
        } else if (confidence < confirmationThreshold && severity.isAtLeast(EventSeverity.MAJOR)) {
            severity = EventSeverity.MODERATE;
            reasons.add(String.format(Locale.ROOT, "capped at MODERATE: confidence %.0f below %.0f",
                    confidence, confirmationThreshold));
        }
        return new Decision(severity, reasons);
    }
}
