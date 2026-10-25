# Bundled datasets

| File | Rows | Real or synthetic |
|---|---|---|
| `seismic_sensor_readings.csv` | 1,493 data rows (1,485 generated + 8 deliberately broken) | **Synthetic** |
| `sensors.csv` | 15 sensors | Synthetic network, illustrative coordinates |
| `sample_import_with_errors.csv` | 38 rows | Synthetic |
| `sample_missing_columns.csv` | 1 row | Synthetic |
| `usgs_sample_catalog.csv` | 177 earthquakes | **Real** (USGS public feed, downloaded 2026-10-13) |

Column definitions are in [`data_dictionary.md`](data_dictionary.md).

## How the synthetic readings were generated

The readings were produced by `com.seismic.dataset.DemoDatasetGenerator`, a Java program in this
project. It was written in Java rather than Python on purpose: it reuses the **same** signal model
(`ReadingGenerator`) and **same** scenario effects (`SeismicWaveEffect`, `RepeatedFaultEffect`, ...) as the live
simulator. So the dataset and the live simulation behave identically, and the model exists in only one place.

Regenerate it (output is identical every time, because all randomness comes from seed 20261013):

```
scripts\generate_demo_dataset.cmd        (Windows)
./scripts/generate_demo_dataset.sh       (macOS / Linux)
```

### Signal model (all values synthetic)

* **Background**: amplitude 0.07 + |N(0,1)| × 0.03 (≈ 0.07–0.17), frequency 1–10 Hz, burst 0.5–2 s, noise 0.04–0.15.
* **Seismic event**: shaking starts at an epicentre and reaches a sensor `distance / 6 km/s` later, with
  amplitude `peak × exp(−distance / 40 km)`, a short ramp-up and decay, frequency 2–6.5 Hz, burst 1.5–4 s.
* **Isolated spike / faulty sensor**: single high amplitude (0.8–1.4) at 20–45 Hz lasting < 0.3 s on ONE sensor;
  the faulty sensor also sends impossible values (amplitude > 2.0).
* **Noisy sensor**: noise level 0.62–0.90, random frequency, amplitude with extra noise.
* **Invalid data**: missing amplitude, negative amplitude, zero frequency or noise > 1.
* **Communication failure**: the sensor simply sends no rows for the period.

### Timeline (one reading per sensor per second, 100 s starting 2026-10-01 08:00:00 UTC)

| Time (s) | Scenario | Expected engine result (verified by `DatasetReplayTest`) |
|---|---|---|
| 0–14 | Normal background | No events |
| 15 | Minor event, North zone, 8 s | Event confirmed (3 sensors), severity MINOR, then RESOLVED |
| 28 | Isolated spike on S08 | Candidate DISMISSED as an isolated anomaly; not an event |
| 30–55 | Noisy data on S14 | Readings SUSPICIOUS; S14 neither offline nor faulty |
| 38 | Major event, Central zone, 12 s | Confirmed, reaches all zones ~5 s later, severity MAJOR/CRITICAL |
| 55–80 | Repeated faulty readings on S11 | S11 becomes FAULTY after 3 isolated anomalies |
| 60–75 | Communication failure on S05 | S05 OFFLINE after 5 s of silence, back to ACTIVE after 3 readings |
| 66–78 | Invalid data on S13 | S13 FAULTY after 3 invalid readings out of 10 |
| 88 | Weak event felt only by S07 and S09 | UNCERTAIN (2 sensors, 3 needed): "needs investigation" |
| end | 8 broken rows | 6 rejected (structure), 2 kept as INVALID (bad amplitude value) |

### Label counts

| Label | Rows |
|---|---|
| NORMAL | 1,219 |
| MAJOR_EVENT | 179 |
| SENSOR_FAULT | 25 |
| MINOR_EVENT | 24 |
| NOISY_DATA | 13 (the major event overrides the label while it shakes S14) |
| INVALID_DATA | 12 |
| WEAK_EVENT | 12 |
| ISOLATED_SPIKE | 1 |
| ERROR_* | 8 |

## Real data: what it can and cannot be used for

No public dataset of raw readings from a dense, low-cost sensor network was used. The USGS catalog lists
earthquakes (time, location, depth, magnitude); it does **not** contain raw sensor measurements. The
application therefore:

* imports the catalog into its own table view and its own `CatalogEarthquake` type, and
* never derives sensor IDs, amplitudes, frequencies or noise levels from it.

The sensor-level algorithms are demonstrated with the synthetic dataset only.
