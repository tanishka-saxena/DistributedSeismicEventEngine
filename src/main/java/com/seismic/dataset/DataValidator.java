package com.seismic.dataset;

import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicReading;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The validation boundary for readings from every source (bundled CSV, imported CSV, live simulator).
 *
 * <p>Rules, checked in this order:
 * <ol>
 *   <li><b>REJECTED</b> (record dropped): timestamp before 2000-01-01 or more than one day in the future.</li>
 *   <li><b>INVALID</b> (kept, excluded from detection): amplitude missing, negative or above the 2.0 sensor range;
 *       frequency &le; 0 or &gt; 100 Hz; duration &lt; 0 or &gt; 300 s; noise level outside 0..1.</li>
 *   <li><b>SUSPICIOUS</b> (kept and filtered, excluded from correlation): noise level &ge; 0.6 or amplitude
 *       &ge; 1.8 (near saturation).</li>
 *   <li>Otherwise <b>VALID</b>. Missing frequency, duration or noise level are noted but not invalid: the
 *       algorithms treat those factors as "unavailable".</li>
 * </ol>
 */
public final class DataValidator {

    public static final double MAX_AMPLITUDE = 2.0;
    public static final double NEAR_SATURATION_AMPLITUDE = 1.8;
    public static final double MAX_FREQUENCY_HZ = 100.0;
    public static final double MAX_DURATION_SECONDS = 300.0;
    public static final double SUSPICIOUS_NOISE_LEVEL = 0.6;
    public static final Instant EARLIEST_PLAUSIBLE_TIME = Instant.parse("2000-01-01T00:00:00Z");
    public static final Duration MAX_FUTURE_SKEW = Duration.ofDays(1);

    private final Clock clock;

    public DataValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public ValidationResult validate(SeismicReading reading) {
        List<String> issues = new ArrayList<>();
        // Parsing problems recorded by the CSV loader (e.g. "amplitude 'abc' is not a number").
        if (!reading.getQualityNote().isBlank()) {
            issues.add(reading.getQualityNote());
        }

        Instant timestamp = reading.getTimestamp();
        if (timestamp.isBefore(EARLIEST_PLAUSIBLE_TIME)) {
            issues.add("timestamp " + timestamp + " is before " + EARLIEST_PLAUSIBLE_TIME);
            return new ValidationResult(ReadingQuality.INVALID, issues, true);
        }
        if (timestamp.isAfter(clock.instant().plus(MAX_FUTURE_SKEW))) {
            issues.add("timestamp " + timestamp + " is in the future");
            return new ValidationResult(ReadingQuality.INVALID, issues, true);
        }

        boolean invalid = false;
        double amplitude = reading.getAmplitude();
        if (Double.isNaN(amplitude)) {
            issues.add("amplitude missing");
            invalid = true;
        } else if (amplitude < 0) {
            issues.add(fmt("amplitude %.3f is negative", amplitude));
            invalid = true;
        } else if (amplitude > MAX_AMPLITUDE) {
            issues.add(fmt("amplitude %.3f exceeds sensor range %.1f", amplitude, MAX_AMPLITUDE));
            invalid = true;
        }

        double frequency = reading.getFrequencyHz();
        if (Double.isNaN(frequency)) {
            issues.add("frequency not reported");
        } else if (frequency <= 0 || frequency > MAX_FREQUENCY_HZ) {
            issues.add(fmt("frequency %.2f Hz outside 0-%.0f Hz", frequency, MAX_FREQUENCY_HZ));
            invalid = true;
        }

        double duration = reading.getDurationSeconds();
        if (Double.isNaN(duration)) {
            issues.add("duration not reported");
        } else if (duration < 0 || duration > MAX_DURATION_SECONDS) {
            issues.add(fmt("duration %.2f s outside 0-%.0f s", duration, MAX_DURATION_SECONDS));
            invalid = true;
        }

        double noise = reading.getNoiseLevel();
        if (Double.isNaN(noise)) {
            issues.add("noise level not reported");
        } else if (noise < 0 || noise > 1) {
            issues.add(fmt("noise level %.2f outside 0-1", noise));
            invalid = true;
        }

        if (invalid) {
            return new ValidationResult(ReadingQuality.INVALID, issues, false);
        }
        boolean suspicious = false;
        if (!Double.isNaN(noise) && noise >= SUSPICIOUS_NOISE_LEVEL) {
            issues.add(fmt("high noise level %.2f", noise));
            suspicious = true;
        }
        if (amplitude >= NEAR_SATURATION_AMPLITUDE) {
            issues.add(fmt("amplitude %.3f near saturation", amplitude));
            suspicious = true;
        }
        return new ValidationResult(suspicious ? ReadingQuality.SUSPICIOUS : ReadingQuality.VALID, issues, false);
    }

    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
