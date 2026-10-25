# Five-minute demonstration script

**Before the review** (2 minutes, not part of the five):
1. Optional: start MySQL and check `config/database.properties` (README → MySQL setup). Without it the app
   runs in demo mode, which is fine, but say so.
2. `mvnw.cmd clean package` once (tests run; about 30 s), so `target\SeismicEngine.jar` exists.
3. Start with `run.bat`, `mvnw.cmd javafx:run`, or `java -jar target\SeismicEngine.jar` **from the project folder**.
4. Keep MySQL Workbench (or a `mysql` console) open on `seismic_engine` if MySQL is used.

---

### 0:00 – 0:30 Opening (Dashboard)
* Point at the header: **"Educational simulation… not a warning system"** and the storage pill (green
  *MySQL connected*, or orange *DEMO MODE* with the reason in the banner).
* Explain the idea in one sentence: *"Fifteen virtual sensors report concurrently. One abnormal reading is
  only a candidate; an event needs several nearby sensors that agree in time, space and signal."*

### 0:30 – 1:30 Normal activity and a major event
1. Click **Start** (left panel). The status changes to *MONITORING*, *Readings processed* climbs, and the
   *Live amplitude by zone* chart shows quiet lines (~0.1).
   *Java:* `SensorSimulator` runs 15 periodic tasks on a 4-thread `ScheduledExecutorService`, and readings go
   through a `BlockingQueue` to one processing thread.
2. Click **Major event** (Target: *Auto*). Within about 5 s:
   * The Central zone line rises first and the outer zones about 5 s later (wave travel time).
   * The system status becomes **SEISMIC EVENT IN PROGRESS**; the latest-event card shows CONFIRMED, severity
     MAJOR/CRITICAL, and the four confidence factor bars.
   * *Recent alerts* shows the confirmation and the escalation alerts.
   *Java:* `EventCorrelator` (temporal + spatial + frequency checks), `ConfidenceCalculator` (weighted formula),
   `SeverityClassifier` (separate rules); `LiveDataBridge` moves data to the JavaFX thread with a `Timeline`.

### 1:30 – 2:15 Sensor network and a fault
1. Open **Sensor Network**. Show the map: rings sized by amplitude, and the red event circle with its
   status, severity and confidence.
2. Set *Target sensor* to **S11** and click **Sensor fault**. Click S11 on the map: its chart shows raw
   spikes (orange line) and the smoother moving average.
3. After about 10 s, S11 turns **red / FAULTY** in the table, and an alert reads "3 isolated anomalies…
   while neighbours stayed normal".
   *Point:* its neighbours S10/S12 stayed normal, so the spikes were never confirmed as an event.
   *Java:* `SensorFaultDetector` reliability rules; `NoiseFilter` keeps the raw value and the filtered value.

### 2:15 – 2:45 Communication failure versus faulty data
1. Target **S05**, click **Comm. failure**. After about 5 s, S05 turns **grey / OFFLINE**, with a WARNING
   alert "no reading for 5.x s".
2. About 15 s later it returns to ACTIVE with a *SENSOR_RECOVERED* alert.
   *Point:* OFFLINE (no data arrives) is different from FAULTY (bad data arrives). `CommunicationMonitor`
   uses heartbeat times; invalid readings still count as heartbeats.
   (Optional: **Invalid data** on another sensor turns it FAULTY, not OFFLINE.)

### 2:45 – 3:30 Event history and the explanation
1. Open **Event History** and select the major event.
2. Left panel: the confidence and its four factors ("project-defined index, not a probability").
3. Tab **Decision explanation**: each line is a real decision ("S04 joined +5.0 s after origin, 30.1 km…",
   "CONFIRMED: 3 sensors ≥ 3 and confidence…", "Severity CRITICAL: raised… because…").
4. Tab **Participating sensors**: distance from the origin and first trigger time of each sensor.
5. Tab **Contributing readings**: the readings themselves.
   *With MySQL:* show the same event in Workbench:
   ```sql
   SELECT e.event_id, e.severity, e.confidence, a.sensor_id, a.distance_from_origin_km
   FROM detected_events e JOIN event_sensor_associations a ON a.event_id = e.event_id
   ORDER BY e.start_time_utc DESC, a.first_trigger_utc;
   ```

### 3:30 – 4:15 Dataset screen (repeatable result)
1. Open **Dataset** → **Load bundled dataset**: 1,493 records, **1,456 valid, 20 suspicious, 11 invalid,
   6 rejected**.
2. Tab **Rejected rows**: line numbers and reasons (unknown sensor S99, bad timestamp, missing ID, wrong column
   count, duplicate ID, timestamp before 2000).
3. Type `S14` in the search box: noisy readings are marked SUSPICIOUS with "high noise level".
4. Click **Analyse dataset** → tab **Detection results**: MINOR event (North), CRITICAL event (Central),
   **UNCERTAIN** two-sensor event (East, "needs investigation"), the dismissed spike on S08, S11 and S13 FAULTY,
   and S05 OFFLINE → ACTIVE. The summary line says where the results were saved (MySQL dataset ID, or "demo mode").
5. Optional: **Import CSV…** → `data/sample_import_with_errors.csv` shows the validation report for a user file.
   **Import USGS catalog…** → `data/usgs_sample_catalog.csv` lists 177 real earthquakes in a separate tab;
   say why they are not converted into sensor readings.

### 4:15 – 4:45 Alerts
1. Open **Alerts**: the most urgent unacknowledged alert is shown on top (*PriorityQueue* head).
2. Select a few rows → **Acknowledge selected**; tick **Unacknowledged only**. The *Open alerts* count on the
   dashboard drops.
3. Mention duplicate control: S05 stayed offline for 15 s but produced one alert, not fifteen.

### 4:45 – 5:00 Persistence and wrap-up
* With MySQL: close the app and start it again. **Event History** and **Alerts** are still there (loaded from MySQL).
* Click **Stop** / **Reset**: threads stop cleanly, sensors return to ACTIVE / reliability 100, history is kept.
* Close with: *"Detection, correlation, fault and communication checks, confidence and severity are separate,
  tested stages (108 JUnit tests; the 8 MySQL-only tests need a MySQL server)."*

---

## Likely questions and where to point

| Question | Answer / file |
|---|---|
| Why is one high reading not an earthquake? | `EventCorrelator` needs ≥ 3 sensors; `SeverityClassifier` caps one sensor at MINOR |
| How do you avoid freezing the UI? | `LiveDataBridge` (concurrent queues + `Timeline`), `FxTasks` (`javafx.concurrent.Task`) |
| How is thread safety handled? | the single-writer processing thread; `synchronized` `Sensor` and `AlertManager`; `CopyOnWriteArrayList`, `ConcurrentHashMap`, atomics; immutable `SeismicReading` and `Alert` |
| What is a transaction here? | `JdbcDatasetRepository.saveDataset`; rollback proven by `datasetIsSavedInOneTransactionOrNotAtAll` |
| Is confidence a probability? | No: a documented weighted index (`docs/algorithms.md` §5) |
| Where are the thresholds? | `ProcessingConfig` defaults, overridable in `config/engine.properties` |
