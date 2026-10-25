package com.seismic.processing;

import com.seismic.config.ProcessingConfig.ConfidenceSettings;
import com.seismic.config.ProcessingConfig.CorrelationSettings;
import com.seismic.config.ProcessingConfig.DetectionThresholds;
import com.seismic.model.ConfidenceBreakdown;
import com.seismic.model.SeismicEvent;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorParticipation;

import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Computes an explainable confidence index from 0 to 100 for an event.
 *
 * <pre>
 * confidence = 100 * sum(weight_i * factor_i) / sum(weight_i over AVAILABLE factors)
 *
 * sensor agreement (30%)  = min(1, participants / required),
 *                           required = max(minSensors, ceil(0.5 * eligible sensors near the origin))
 * temporal (25%)          = max(0, 1 - mean |observed delay - distance / wave speed| / window)
 * spatial (20%)           = participants / eligible sensors that are no farther from the origin than the
 *                           farthest participant ("no silent sensors inside the affected area")
 * signal (25%)            = mean of strength = min(1, peak filtered amplitude / high threshold)
 *                           and frequency consistency = 1 - min(1, std-dev of frequencies / tolerance)
 * </pre>
 * With a single sensor, temporal and spatial consistency are 0: there is no corroborating sensor, and
 * that is evidence AGAINST an event, not missing data. A factor is "unavailable" (left out and the
 * weights renormalised) only when the needed data is missing, e.g. sensors without coordinates.
 * <p>
 * This is a project-defined index, NOT a calibrated probability that an earthquake occurred.
 */
public final class ConfidenceCalculator {

    private final ConfidenceSettings weights;
    private final CorrelationSettings correlation;
    private final DetectionThresholds thresholds;

    public ConfidenceCalculator(ConfidenceSettings weights, CorrelationSettings correlation,
                                DetectionThresholds thresholds) {
        this.weights = weights;
        this.correlation = correlation;
        this.thresholds = thresholds;
    }

    public ConfidenceBreakdown calculate(SeismicEvent event, SensorNetwork network) {
        Set<String> eligible = eligibleSensors(event, network);
        double agreement = sensorAgreement(event, eligible.size());
        OptionalDouble temporal = temporalConsistency(event);
        OptionalDouble spatial = spatialConsistency(event, network, eligible);
        double signal = signalScore(event);

        double weighted = weights.agreementWeight() * agreement + weights.signalWeight() * signal;
        double weightSum = weights.agreementWeight() + weights.signalWeight();
        if (temporal.isPresent()) {
            weighted += weights.temporalWeight() * temporal.getAsDouble();
            weightSum += weights.temporalWeight();
        }
        if (spatial.isPresent()) {
            weighted += weights.spatialWeight() * spatial.getAsDouble();
            weightSum += weights.spatialWeight();
        }
        double score = weightSum > 0 ? 100.0 * weighted / weightSum : 0.0;
        score = Math.max(0.0, Math.min(100.0, score));
        return new ConfidenceBreakdown(agreement, temporal, spatial, signal, score);
    }

    /** ACTIVE sensors within the correlation radius of the origin, plus every participant. */
    private Set<String> eligibleSensors(SeismicEvent event, SensorNetwork network) {
        Set<String> eligible = new HashSet<>(event.getParticipatingSensorIds());
        for (Sensor sensor : network.sensors()) {
            double distance = event.getOriginLocation().distanceKm(sensor.getLocation());
            if (sensor.getStatus().isEligibleForCorrelation() && !Double.isNaN(distance)
                    && distance <= correlation.radiusKm()) {
                eligible.add(sensor.getSensorId());
            }
        }
        return eligible;
    }

    double sensorAgreement(SeismicEvent event, int eligibleCount) {
        int required = Math.max(correlation.minSensors(), (int) Math.ceil(0.5 * eligibleCount));
        return Math.min(1.0, event.getParticipatingSensorCount() / (double) required);
    }

    OptionalDouble temporalConsistency(SeismicEvent event) {
        if (event.getParticipatingSensorCount() < 2) {
            return OptionalDouble.of(0.0);
        }
        SensorParticipation origin = event.getParticipation(event.getOriginSensorId());
        double residualSum = 0;
        int count = 0;
        for (SensorParticipation p : event.getParticipations()) {
            if (p.isOrigin() || Double.isNaN(p.getDistanceFromOriginKm())) {
                continue;
            }
            double expected = p.getDistanceFromOriginKm() / correlation.waveSpeedKmPerSecond();
            double observed = EventCorrelator.seconds(origin.getFirstTrigger(), p.getFirstTrigger());
            residualSum += Math.abs(observed - expected);
            count++;
        }
        if (count == 0) {
            return OptionalDouble.empty();
        }
        double meanResidual = residualSum / count;
        return OptionalDouble.of(Math.max(0.0, 1.0 - meanResidual / correlation.windowSeconds()));
    }

    OptionalDouble spatialConsistency(SeismicEvent event, SensorNetwork network, Set<String> eligible) {
        if (event.getParticipatingSensorCount() < 2) {
            return OptionalDouble.of(0.0);
        }
        double farthest = 0;
        for (SensorParticipation p : event.getParticipations()) {
            if (Double.isNaN(p.getDistanceFromOriginKm())) {
                return OptionalDouble.empty();
            }
            farthest = Math.max(farthest, p.getDistanceFromOriginKm());
        }
        int insideArea = 0;
        for (String sensorId : eligible) {
            double distance = event.getOriginLocation().distanceKm(network.require(sensorId).getLocation());
            if (!Double.isNaN(distance) && distance <= farthest + 1e-6) {
                insideArea++;
            }
        }
        return OptionalDouble.of(insideArea == 0 ? 0.0 : Math.min(1.0, event.getParticipatingSensorCount() / (double) insideArea));
    }

    double signalScore(SeismicEvent event) {
        double strength = Math.min(1.0, event.getPeakFilteredAmplitude() / thresholds.high());
        List<Double> frequencies = event.getParticipations().stream()
                .map(SensorParticipation::getFirstFrequencyHz)
                .filter(f -> !Double.isNaN(f)).toList();
        if (frequencies.size() < 2) {
            return strength;
        }
        double mean = frequencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = frequencies.stream().mapToDouble(f -> (f - mean) * (f - mean)).average().orElse(0);
        double consistency = 1.0 - Math.min(1.0, Math.sqrt(variance) / correlation.frequencyToleranceHz());
        return (strength + consistency) / 2.0;
    }
}
