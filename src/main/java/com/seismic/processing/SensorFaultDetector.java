package com.seismic.processing;

import com.seismic.config.ProcessingConfig.ReliabilityPolicy;
import com.seismic.model.FaultFinding;
import com.seismic.model.FaultKind;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.Sensor;
import com.seismic.model.SensorNetwork;
import com.seismic.model.SensorParticipation;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maintains a 0-100 reliability score per sensor and flags sensors that behave like faulty hardware.
 *
 * <p>Score adjustments (configurable, see ReliabilityPolicy):
 * <ul>
 *   <li>every {@code validStreakForBonus} consecutive valid, non-candidate readings: +validStreakBonus</li>
 *   <li>each INVALID reading: -invalidPenalty</li>
 *   <li>each isolated anomaly that disagrees with neighbours: -outlierPenalty
 *       (-unverifiedOutlierPenalty if there were no active neighbours to compare with)</li>
 *   <li>communication loss is a separate availability penalty applied by CommunicationMonitor</li>
 * </ul>
 * A sensor becomes FAULTY on: {@code repeatedInvalidCount} invalid readings within the last
 * {@code invalidWindowSize}; {@code repeatedOutlierCount} isolated anomalies within
 * {@code outlierMemorySeconds}; or a score below {@code faultyBelowScore}. It returns to ACTIVE after
 * {@code recoveryValidStreak} consecutive valid normal readings.
 *
 * <p>Each reading is judged once, when it arrives; each isolated anomaly is judged once, when its
 * single-sensor event is dismissed. Nothing is re-penalised in later cycles. A sensor that triggered first
 * in an event that other sensors then confirmed is never penalised, because only DISMISSED
 * single-sensor events count as isolated anomalies.
 *
 * <p>Not thread-safe: owned by the processing thread.
 */
public final class SensorFaultDetector {

    private final ReliabilityPolicy policy;
    private final SensorNetwork network;
    private final double neighbourRadiusKm;
    private final Map<String, SensorHealth> health = new HashMap<>();

    public SensorFaultDetector(ReliabilityPolicy policy, SensorNetwork network, double neighbourRadiusKm) {
        this.policy = policy;
        this.network = network;
        this.neighbourRadiusKm = neighbourRadiusKm;
    }

    /** Judges one validated reading. {@code candidate} is true if it crossed a detection threshold. */
    public FaultUpdate onReading(Sensor sensor, SeismicReading reading, boolean candidate) {
        SensorHealth h = healthOf(sensor);
        List<FaultFinding> findings = new ArrayList<>();
        Optional<SensorStatusChange> change = Optional.empty();
        Instant at = reading.getTimestamp();

        if (reading.getQuality() == ReadingQuality.INVALID) {
            h.recordValidity(true, policy.invalidWindowSize());
            h.validStreak = 0;
            sensor.adjustReliability(-policy.invalidPenalty());
            int invalidCount = h.invalidCount();
            if (invalidCount >= policy.repeatedInvalidCount()) {
                FaultFinding finding = new FaultFinding(sensor.getSensorId(), FaultKind.REPEATED_INVALID_READINGS,
                        invalidCount + " invalid readings in the last " + policy.invalidWindowSize()
                                + " (latest: " + reading.getQualityNote() + ")", at);
                findings.add(finding);
                change = markFaulty(sensor, finding.description(), at);
            }
        } else if (reading.getQuality() == ReadingQuality.VALID) {
            h.recordValidity(false, policy.invalidWindowSize());
            if (!candidate) {
                h.validStreak++;
                if (h.validStreak % policy.validStreakForBonus() == 0) {
                    sensor.adjustReliability(policy.validStreakBonus());
                }
                if (sensor.getStatus() == SensorStatus.FAULTY && h.validStreak >= policy.recoveryValidStreak()) {
                    h.clearFaultHistory();
                    change = Optional.of(new SensorStatusChange(sensor.getSensorId(), SensorStatus.FAULTY,
                            SensorStatus.ACTIVE, h.validStreak + " consecutive valid readings", at));
                    sensor.setStatus(SensorStatus.ACTIVE);
                }
            }
        } else {
            // SUSPICIOUS (noisy) readings are neither rewarded nor penalised.
            h.recordValidity(false, policy.invalidWindowSize());
        }
        if (change.isEmpty()) {
            change = checkLowScore(sensor, at);
        }
        h.recordScore(at, sensor.getReliabilityScore());
        return new FaultUpdate(findings, change);
    }

    /**
     * Called once when a single-sensor candidate event is dismissed. Compares the sensor's peak with
     * what its active neighbours reported at the same time.
     */
    public FaultUpdate onIsolatedAnomaly(Sensor sensor, SeismicEvent dismissed, Instant now) {
        SensorHealth h = healthOf(sensor);
        SensorParticipation participation = dismissed.getParticipation(sensor.getSensorId());
        double peak = participation == null ? dismissed.getPeakAmplitude() : participation.getPeakAmplitude();
        List<FaultFinding> findings = new ArrayList<>();

        OptionalNeighbourLevel neighbours = neighbourLevel(sensor, dismissed.getLastActivityTime());
        if (neighbours.count() > 0) {
            double ratio = peak / Math.max(neighbours.median(), 0.01);
            if (ratio >= policy.peerDisagreementRatio()) {
                sensor.adjustReliability(-policy.outlierPenalty());
                findings.add(new FaultFinding(sensor.getSensorId(), FaultKind.PEER_DISAGREEMENT,
                        String.format(Locale.ROOT, "peak %.3f is %.1fx the median %.3f of %d active neighbour(s)",
                                peak, ratio, neighbours.median(), neighbours.count()), now));
            } else {
                // Neighbours were also elevated: probably weak real shaking that missed the thresholds.
                sensor.adjustReliability(-policy.unverifiedOutlierPenalty());
            }
        } else {
            sensor.adjustReliability(-policy.unverifiedOutlierPenalty());
        }
        h.outlierTimes.addLast(now);
        h.pruneOutliers(now, policy.outlierMemorySeconds());

        Optional<SensorStatusChange> change = Optional.empty();
        if (h.outlierTimes.size() >= policy.repeatedOutlierCount()) {
            FaultFinding finding = new FaultFinding(sensor.getSensorId(), FaultKind.REPEATED_ISOLATED_OUTLIERS,
                    String.format(Locale.ROOT, "%d isolated anomalies within %.0f s while neighbours stayed normal",
                            h.outlierTimes.size(), policy.outlierMemorySeconds()), now);
            findings.add(finding);
            change = markFaulty(sensor, finding.description(), now);
        }
        if (change.isEmpty()) {
            change = checkLowScore(sensor, now);
        }
        h.validStreak = 0;
        h.recordScore(now, sensor.getReliabilityScore());
        return new FaultUpdate(findings, change);
    }

    /** Reports sensors whose score fell by at least suddenDropPoints within suddenDropWindowSeconds. */
    public List<FaultFinding> checkReliabilityTrends(Instant now) {
        List<FaultFinding> findings = new ArrayList<>();
        for (Sensor sensor : network.sensors()) {
            SensorHealth h = health.get(sensor.getSensorId());
            if (h == null) {
                continue;
            }
            h.recordScore(now, sensor.getReliabilityScore());
            h.pruneScores(now, policy.suddenDropWindowSeconds());
            double max = h.maxScore();
            double current = sensor.getReliabilityScore();
            double drop = max - current;
            if (drop >= policy.suddenDropPoints() && !h.dropReported) {
                h.dropReported = true;
                findings.add(new FaultFinding(sensor.getSensorId(), FaultKind.RELIABILITY_DROP,
                        String.format(Locale.ROOT, "reliability fell %.0f points (%.0f -> %.0f) within %.0f s",
                                drop, max, current, policy.suddenDropWindowSeconds()), now));
            } else if (drop < policy.suddenDropPoints() / 2) {
                h.dropReported = false;
            }
        }
        return findings;
    }

    public void reset() {
        health.clear();
    }

    /** Applies the communication-loss availability penalty and records it in the score history. */
    public void onCommunicationLoss(Sensor sensor, double penalty, Instant at) {
        sensor.adjustReliability(-penalty);
        healthOf(sensor).recordScore(at, sensor.getReliabilityScore());
    }

    private Optional<SensorStatusChange> markFaulty(Sensor sensor, String reason, Instant at) {
        if (sensor.getStatus() != SensorStatus.ACTIVE) {
            return Optional.empty();
        }
        sensor.setStatus(SensorStatus.FAULTY);
        healthOf(sensor).validStreak = 0;
        return Optional.of(new SensorStatusChange(sensor.getSensorId(), SensorStatus.ACTIVE, SensorStatus.FAULTY, reason, at));
    }

    private Optional<SensorStatusChange> checkLowScore(Sensor sensor, Instant at) {
        if (sensor.getReliabilityScore() < policy.faultyBelowScore()) {
            return markFaulty(sensor, String.format(Locale.ROOT, "reliability %.0f below %.0f",
                    sensor.getReliabilityScore(), policy.faultyBelowScore()), at);
        }
        return Optional.empty();
    }

    private record OptionalNeighbourLevel(int count, double median) {
    }

    /** Median latest amplitude of ACTIVE neighbours that reported within the last 5 seconds. */
    private OptionalNeighbourLevel neighbourLevel(Sensor sensor, Instant reference) {
        List<Double> levels = new ArrayList<>();
        for (Sensor neighbour : network.neighboursOf(sensor, neighbourRadiusKm)) {
            Instant latest = neighbour.getLatestReadingTime();
            double amplitude = neighbour.getLatestAmplitude();
            if (neighbour.getStatus() == SensorStatus.ACTIVE && latest != null && !Double.isNaN(amplitude)
                    && Math.abs(Duration.between(latest, reference).toSeconds()) <= 5) {
                levels.add(amplitude);
            }
        }
        if (levels.isEmpty()) {
            return new OptionalNeighbourLevel(0, Double.NaN);
        }
        levels.sort(Double::compare);
        int n = levels.size();
        double median = n % 2 == 1 ? levels.get(n / 2) : (levels.get(n / 2 - 1) + levels.get(n / 2)) / 2;
        return new OptionalNeighbourLevel(n, median);
    }

    private SensorHealth healthOf(Sensor sensor) {
        return health.computeIfAbsent(sensor.getSensorId(), id -> new SensorHealth());
    }

    /** Per-sensor history used by the rules above. */
    private static final class SensorHealth {
        private final Deque<Boolean> recentInvalid = new ArrayDeque<>();
        private final Deque<Instant> outlierTimes = new ArrayDeque<>();
        private final Deque<Instant> scoreTimes = new ArrayDeque<>();
        private final Deque<Double> scoreValues = new ArrayDeque<>();
        private int validStreak;
        private boolean dropReported;

        void recordValidity(boolean invalid, int windowSize) {
            recentInvalid.addLast(invalid);
            while (recentInvalid.size() > windowSize) {
                recentInvalid.removeFirst();
            }
        }

        int invalidCount() {
            int count = 0;
            for (boolean invalid : recentInvalid) {
                if (invalid) {
                    count++;
                }
            }
            return count;
        }

        void pruneOutliers(Instant now, double memorySeconds) {
            while (!outlierTimes.isEmpty()
                    && Duration.between(outlierTimes.peekFirst(), now).toMillis() > memorySeconds * 1000) {
                outlierTimes.removeFirst();
            }
        }

        void recordScore(Instant at, double score) {
            scoreTimes.addLast(at);
            scoreValues.addLast(score);
        }

        void pruneScores(Instant now, double windowSeconds) {
            while (scoreTimes.size() > 1
                    && Duration.between(scoreTimes.peekFirst(), now).toMillis() > windowSeconds * 1000) {
                scoreTimes.removeFirst();
                scoreValues.removeFirst();
            }
        }

        double maxScore() {
            return scoreValues.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        }

        void clearFaultHistory() {
            recentInvalid.clear();
            outlierTimes.clear();
        }
    }
}
