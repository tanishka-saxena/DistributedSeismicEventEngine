package com.seismic.processing;

import com.seismic.config.ProcessingConfig.CommunicationSettings;
import com.seismic.model.Sensor;
import com.seismic.model.SensorStatus;
import com.seismic.model.SensorStatusChange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Detects communication failures from reading arrival times. Every delivered reading counts as a
 * heartbeat, even an INVALID one: a sensor that sends bad values is still communicating, so it is
 * handled by SensorFaultDetector, not here.
 *
 * <ul>
 *   <li>No heartbeat for more than {@code timeoutSeconds} -&gt; OFFLINE (availability penalty applied once).</li>
 *   <li>An OFFLINE sensor returns to its previous status after {@code recoveryReadings} consecutive readings,
 *       each arriving within the timeout of the previous one.</li>
 * </ul>
 * "Now" is always passed in, so tests and dataset replays control time without sleeping.
 * Not thread-safe: owned by the processing thread.
 */
public final class CommunicationMonitor {

    /** Called when a sensor goes OFFLINE, so the reliability penalty is applied exactly once. */
    public interface OfflinePenalty {
        void apply(Sensor sensor, Instant at);
    }

    private final Duration timeout;
    private final int recoveryReadings;
    private final OfflinePenalty penalty;
    private final Map<String, Instant> lastHeartbeat = new HashMap<>();
    private final Map<String, SensorStatus> statusBeforeOffline = new HashMap<>();
    private final Map<String, Integer> recoveryProgress = new HashMap<>();
    private Instant trackingStartedAt;

    public CommunicationMonitor(CommunicationSettings settings, OfflinePenalty penalty) {
        this.timeout = Duration.ofMillis(Math.round(settings.timeoutSeconds() * 1000));
        this.recoveryReadings = settings.recoveryReadings();
        this.penalty = penalty;
    }

    /**
     * Starts (or restarts after a pause) the timeout clock. Heartbeats from before the pause are forgotten,
     * otherwise every sensor would time out the moment monitoring resumes.
     */
    public void startTracking(Instant now) {
        trackingStartedAt = now;
        lastHeartbeat.clear();
        recoveryProgress.clear();
    }

    public Optional<SensorStatusChange> recordHeartbeat(Sensor sensor, Instant at) {
        String id = sensor.getSensorId();
        Instant previous = lastHeartbeat.get(id);
        if (previous == null || at.isAfter(previous)) {
            lastHeartbeat.put(id, at);
            sensor.setLastHeartbeat(at);
        }
        if (sensor.getStatus() != SensorStatus.OFFLINE) {
            return Optional.empty();
        }
        // Consecutive readings must themselves be on time, otherwise recovery starts again.
        int progress = previous != null && Duration.between(previous, at).compareTo(timeout) <= 0
                ? recoveryProgress.getOrDefault(id, 0) + 1 : 1;
        recoveryProgress.put(id, progress);
        if (progress < recoveryReadings) {
            return Optional.empty();
        }
        SensorStatus restored = statusBeforeOffline.getOrDefault(id, SensorStatus.ACTIVE);
        statusBeforeOffline.remove(id);
        recoveryProgress.remove(id);
        sensor.setStatus(restored);
        return Optional.of(new SensorStatusChange(id, SensorStatus.OFFLINE, restored,
                "communication restored (" + progress + " consecutive readings)", at));
    }

    public List<SensorStatusChange> checkTimeouts(Collection<Sensor> sensors, Instant now) {
        List<SensorStatusChange> changes = new ArrayList<>();
        for (Sensor sensor : sensors) {
            SensorStatus status = sensor.getStatus();
            if (status == SensorStatus.OFFLINE || status == SensorStatus.MAINTENANCE) {
                continue;
            }
            Instant last = lastHeartbeat.getOrDefault(sensor.getSensorId(), trackingStartedAt);
            if (last == null) {
                continue;
            }
            Duration silence = Duration.between(last, now);
            if (silence.compareTo(timeout) > 0) {
                statusBeforeOffline.put(sensor.getSensorId(), status);
                recoveryProgress.remove(sensor.getSensorId());
                sensor.setStatus(SensorStatus.OFFLINE);
                penalty.apply(sensor, now);
                changes.add(new SensorStatusChange(sensor.getSensorId(), status, SensorStatus.OFFLINE,
                        String.format(Locale.ROOT, "no reading for %.1f s (timeout %.1f s)",
                                silence.toMillis() / 1000.0, timeout.toMillis() / 1000.0), now));
            }
        }
        return changes;
    }

    public Optional<Instant> lastHeartbeat(String sensorId) {
        return Optional.ofNullable(lastHeartbeat.get(sensorId));
    }

    public void reset() {
        lastHeartbeat.clear();
        statusBeforeOffline.clear();
        recoveryProgress.clear();
        trackingStartedAt = null;
    }
}
