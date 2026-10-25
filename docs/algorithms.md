# Algorithms

Every rule below is implemented in the class named in its heading, and every number is a configurable
default (`config/engine.properties`, `ProcessingConfig`). Amplitudes are **synthetic units**, not magnitudes.
The confidence score is a **project-defined index**, not a probability.

---

## 1. Data validation — `dataset/DataValidator`

**Purpose.** One validation boundary for every reading (bundled CSV, user import, live simulator), so bad
data is flagged and kept visible instead of silently disappearing.

**Inputs.** A `SeismicReading` (missing numbers are `NaN`), plus a `Clock` for the "future timestamp" check.
**Outputs.** `ValidationResult(quality, issues, rejected)`.

**Rules (checked in order).**

| Result | Condition |
|---|---|
| REJECTED | timestamp < 2000-01-01, or timestamp > now + 1 day |
| INVALID | amplitude missing / < 0 / > 2.0; frequency ≤ 0 or > 100 Hz; duration < 0 or > 300 s; noise ∉ [0, 1] |
| SUSPICIOUS | noise ≥ 0.6, or amplitude ≥ 1.8 (near saturation) |
| VALID | otherwise; missing frequency/duration/noise are noted as "not reported" (the factor becomes *unavailable* later) |

The CSV loader (`CsvDatasetLoader`) rejects rows that cannot be attributed to a sensor and time: wrong column
count, missing/duplicate `reading_id`, unknown sensor, unparseable timestamp, or broken quoting.

**Example.** `amplitude = "abc"` → loader stores NaN plus the note "amplitude 'abc' is not a number" → INVALID.
**Complexity.** O(1) per reading. **Limitation.** The ranges are fixed properties of the virtual sensor model.

---

## 2. Moving-average noise filter — `dataset/NoiseFilter`

**Purpose.** Smooth random noise so that severity depends on sustained shaking, not single samples.

**Inputs.** Readings of many sensors in arrival (or timestamp) order. **Output.** The same reading with
`filteredAmplitude` set; the raw `amplitude` is unchanged.

**Formula.** For sensor *s* with its last *N* usable amplitudes a₁…a_N (N = window size, default 3):

```
filtered = (a₁ + … + a_N) / N
```

**Rules that prevent mixing unrelated data.**
* One window per sensor (`Map<sensorId, ArrayDeque>`), so sensors are never averaged together.
* If the gap since that sensor's previous reading is more than 5 s, the window restarts.
* An out-of-order reading is not added; its filtered value equals its raw value.
* INVALID readings are skipped (no trustworthy amplitude).
* Spikes are **not** removed. They stay in the raw data and are later judged by the fault detector.
* Batch mode (`applyAll`) sorts by timestamp first, so each sensor's readings are filtered in the right order.

**Example.** Raw 0.10, 0.10, 1.00 → filtered 0.10, 0.10, **0.40**. The spike stays visible as raw 1.00.
**Complexity.** O(N) per reading, O(sensors × N) memory.
**Limitation.** A simple moving average lags real changes by about N/2 samples.

---

## 3. Threshold detection — `processing/EventDetector`

**Purpose.** Stage 1: mark readings that **might** belong to an event (candidates). Nothing is confirmed here.

| Raw amplitude *a* | Level |
|---|---|
| a < 0.30 | NORMAL (no candidate) |
| 0.30 ≤ a < 0.50 | MINOR candidate |
| 0.50 ≤ a ≤ 0.75 | MODERATE candidate |
| a > 0.75 | HIGH candidate |

The candidate also records the level of the **filtered** amplitude (`sustainedLevel`). A HIGH raw level with
a NORMAL sustained level identifies a short transient. INVALID readings never become candidates.
**Complexity.** O(1).

---

## 4. Multi-sensor correlation — `processing/EventCorrelator`

**Purpose.** Stage 2: group candidates from different sensors into events. A genuine event needs several
sensors that agree in time, space and signal.

**Inputs.** Candidates of VALID readings from ACTIVE sensors. SUSPICIOUS readings and OFFLINE/FAULTY
sensors are skipped, and the reason is recorded.
**Output.** The event the candidate now belongs to (new or existing).

**Join test.** A candidate from sensor *s* at time *t* may join an open event *E* only if all three hold:

| Factor | Rule (defaults) |
|---|---|
| Temporal | `t − E.lastActivity ≤ 5 s` and `t − E.start ≤ 40 s` (and not more than 5 s before E started) |
| Spatial | `distance(s, E.origin) ≤ 40 km` (haversine). Unknown coordinates: only an event from the same zone |
| Signal | `|f − E.meanFrequency| ≤ 4 Hz` and burst duration ≥ 0.5 s (a check is skipped if the value is missing) |

If several events match, the one whose origin is **closest** wins. If none matches, a new CANDIDATE event
starts with this sensor as origin. Two simultaneous events 100 km apart therefore stay separate, and an
electrical 30 Hz spike never joins a 4 Hz earthquake.

**Closing (on every tick).** An open event closes when it has been quiet for 5 s, when it reaches 40 s, or
(single-sensor events only) when 5 s have passed since it started without a second sensor joining. The last
rule makes repeated spikes from one sensor count as several separate isolated anomalies.

**Lifecycle decided by the pipeline (`EventProcessingPipeline`).**

```
CANDIDATE ──(≥ 3 sensors AND confidence ≥ 60)──► CONFIRMED ──(closed)──► RESOLVED
CANDIDATE ──(closed with ≥ 2 sensors, rules not met)──► UNCERTAIN  ("needs investigation")
CANDIDATE ──(closed with 1 sensor)──► DISMISSED  (isolated anomaly → fault detector)
```

**Example.** A1 triggers at t = 0, A2 (2 km away) at 0.4 s, A3 (2 km) at 0.4 s → one event, 3 sensors,
confidence 97 → CONFIRMED. A2 at t = 20 s → new event (outside the window).
**Complexity.** O(open events) per candidate. Open events close within seconds, so this stays small.
**Limitations.** The origin is the first-triggering sensor, not a located epicentre. Two overlapping events
in the same area at the same time would be merged.

---

## 5. Confidence — `processing/ConfidenceCalculator`

**Purpose.** An explainable 0–100 index showing how strongly the evidence supports a real event.

**Factors (each 0..1).**

| Factor | Weight | Formula |
|---|---|---|
| Sensor agreement | 30 % | `min(1, n / required)`, `required = max(3, ⌈0.5 × eligible⌉)`. *Eligible* = ACTIVE sensors within 40 km of the origin, plus all participants |
| Temporal consistency | 25 % | `max(0, 1 − mean_i |observed_delay_i − distance_i / 6 km/s| / 5 s)`. Real waves reach farther sensors later; this measures how well the trigger times fit that |
| Spatial consistency | 20 % | `n / (eligible sensors no farther from the origin than the farthest participant)`. Silent working sensors **inside** the affected area lower the score |
| Signal | 25 % | mean of strength = `min(1, peak filtered amplitude / 0.75)` and frequency consistency = `1 − min(1, σ_f / 4 Hz)`. Strength only, if fewer than two frequencies are known |

```
confidence = 100 × Σ(wᵢ × factorᵢ) / Σ(wᵢ of AVAILABLE factors),  clamped to [0, 100]
```

**Unavailable versus zero.** A factor is *unavailable* only when its input data is missing (for example,
sensors without coordinates). It is then left out and the remaining weights are renormalised. A
**single-sensor** event gets temporal = spatial = **0**: having no corroborating sensor is evidence against
an event, not missing data. Missing data is never treated as agreement.

**Worked example 1: correlated event** (`ConfidenceCalculatorTest`): A1, A2, A3 at 0 / 0.4 / 0.4 s,
amplitude 0.6, 4 Hz.

```
agreement = min(1, 3 / max(3, ⌈0.5·4⌉)) = 1.000
temporal  = 1 − mean(|0.4 − 2/6|, |0.4 − 2/6|) / 5 = 1 − 0.0667/5 = 0.987
spatial   = 3 / 3 = 1.000      (A4 at 4.2 km is farther than every participant)
signal    = (min(1, 0.6/0.75) + (1 − 0/4)) / 2 = (0.8 + 1) / 2 = 0.900
score     = 100 × (0.30·1 + 0.25·0.987 + 0.20·1 + 0.25·0.9) / 1.00 ≈ 97.2
```

**Worked example 2: one high reading** (A1 = 0.95):
`agreement = 1/3, temporal = 0, spatial = 0, signal = min(1, 0.95/0.75) = 1` →
`100 × (0.30·0.333 + 0.25·1) = 35.0` → never confirmed.

**Complexity.** O(sensors) per evaluation.
**Limitation.** The weights are hand-chosen, so the score is not calibrated against real outcomes.

---

## 6. Severity — `processing/SeverityClassifier`

**Purpose.** Stage 4, separate from detection and confirmation: how serious an event is.
**Inputs.** Peak *filtered* amplitude, number of sensors, duration, affected radius, confidence.

| Step | Rule |
|---|---|
| Base | level of the peak filtered amplitude: <0.30 NORMAL, <0.50 MINOR, ≤0.75 MODERATE, >0.75 MAJOR |
| Critical | MAJOR → **CRITICAL** if peak ≥ 1.0 AND sensors ≥ 6 AND duration ≥ 5 s AND confidence ≥ 75 |
| Widespread | MINOR/MODERATE raised one level if sensors ≥ 6 AND affected radius ≥ 20 km |
| Cap 1 | fewer than 3 sensors → at most MINOR |
| Cap 2 | confidence < 60 → at most MODERATE |

Every applied rule is written into the event's explanation.

**Examples (`SeverityClassifierTest`).** 6 sensors, peak 1.1, 6 s, confidence 90 → CRITICAL; same with 5
sensors → MAJOR. 6 sensors, peak 0.6, radius 25 km → MAJOR (widespread). 1 sensor, peak 1.5 → MINOR (cap).
Using the filtered peak means a single-sample spike cannot raise the severity.

---

## 7. Sensor fault detection and reliability — `processing/SensorFaultDetector`

**Purpose.** Separate faulty hardware from real events and from communication problems.
**State.** A reliability score of 0–100 per sensor (starts at 100), plus a short history per sensor.

| Observation | Score change | Fault rule |
|---|---|---|
| every 10 consecutive VALID non-candidate readings | +1 | FAULTY sensor returns to ACTIVE after 20 in a row |
| INVALID reading | −4 | 3 invalid in the last 10 → FAULTY ("repeated invalid readings") |
| SUSPICIOUS (noisy) reading | 0 | – |
| Isolated anomaly (DISMISSED single-sensor event) where peak ≥ 3 × median of active neighbours | −8 (*peer disagreement*) | 3 isolated anomalies within 120 s → FAULTY ("repeated isolated outliers") |
| Isolated anomaly without neighbours to compare, or neighbours also elevated | −3 | counts towards the same rule |
| Communication loss (from CommunicationMonitor) | −10, once per outage | – (availability, not hardware) |
| Any time | – | score < 40 → FAULTY |
| Every tick | – | score fell ≥ 20 points within 60 s → RELIABILITY_DROP finding (reported once) |

**Why it does not punish real events.** Only DISMISSED events, where no other sensor confirmed the activity,
count as anomalies. A sensor that triggers first in a real event is part of a confirmed event, so it is never
penalised (`SensorFaultDetectorTest.sensorThatTriggersFirstInARealEventIsNotPenalised`).
**Why nothing is penalised twice.** Each reading is judged once, on arrival. Each anomaly is judged once, when
its event is dismissed.
**Complexity.** O(1) per reading, O(neighbours) per anomaly.

---

## 8. Communication monitoring — `processing/CommunicationMonitor`

**Purpose.** Detect silent sensors, which is a different problem from sensors sending bad data.

* Every delivered reading is a heartbeat, even an INVALID one, because that sensor is still communicating.
* `checkTimeouts(now)`: no heartbeat for more than **5 s** → OFFLINE, availability penalty −10 (once). The
  previous status is remembered.
* Recovery: **3 consecutive** readings, each within 5 s of the previous one → back to the previous status
  (ACTIVE, or FAULTY if it was faulty before).
* "Now" is passed in as a parameter, so tests use fixed times and dataset replays use data time; there is no
  sleeping in tests.
* After a pause (Stop → Start), old heartbeats are forgotten so sensors do not time out instantly.

**Example.** S05 sends nothing after 08:01:00 → OFFLINE at 08:01:05.x → readings at :15, :16, :17 → ACTIVE.

---

## 9. Alert management — `alert/AlertManager`

| Trigger | Alert type | Severity |
|---|---|---|
| Event CONFIRMED/RESOLVED, first time | SEISMIC_EVENT (MAJOR_EVENT if severity ≥ MAJOR) | from event severity |
| Severity escalates (e.g. MAJOR → CRITICAL) | MAJOR_EVENT / SEISMIC_EVENT "escalated" | from new severity |
| Event UNCERTAIN | LOW_CONFIDENCE_EVENT | WARNING |
| Candidate DISMISSED | ISOLATED_ANOMALY | INFO |
| Sensor → FAULTY / OFFLINE | SENSOR_FAULT (HIGH) / COMMUNICATION_FAILURE (WARNING) | |
| Sensor recovers | SENSOR_RECOVERED | INFO |
| Reliability drop finding | SENSOR_FAULT | WARNING |
| Processing / database error | PROCESSING_ERROR, at most once per minute per source | WARNING |

**Duplicate control.** Each condition has a key (`COMM:S05`, `FAULT:S11`, `EVENT:<id>:<severity>`). A key that
is already active produces no new alert. Recovery clears the sensor's keys, so a later failure is alerted again.
For events, only a severity **increase** creates a new alert; an unchanged or downgraded event does not.
**Priority.** Unacknowledged alerts sit in a `PriorityQueue` ordered by severity (CRITICAL first), then age.
`mostUrgent()` is O(1); insert/remove are O(log n). History is bounded (2,000 alerts).

---

## 10. Simulation model — `simulation/*`

* Background: amplitude 0.07 + |N(0,1)|·0.03, frequency 1–10 Hz.
* Seismic wave: arrival delay = distance / 6 km/s; amplitude = peak · e^(−distance / 40 km) · envelope · U(0.92, 1.08).
  Minor event: peak 0.44, 8 s, radius 12 km. Major event: peak 1.15, 12 s, radius 35 km.
* Faulty sensor: 45 % spikes (0.8–1.4, 20–45 Hz, < 0.3 s) and 10 % out-of-range values, for 25 s.
* Each sensor uses its own `Random(seed + 1000·(index+1))`, so its readings are reproducible regardless of thread
  scheduling.

**Limitation.** This is a teaching model: no separate P and S waves, no site amplification, no real
instrument response.
