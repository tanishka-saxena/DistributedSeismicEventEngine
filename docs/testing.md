# Testing

## How to run

```bat
mvnw.cmd clean test                                   :: all tests (MySQL tests skip themselves if not configured)
mvnw.cmd test -Dtest=DatasetReplayTest                :: one class
mvnw.cmd test -Dtest=MySqlRepositoryIntegrationTest   :: needs SEISMIC_TEST_DB_URL (see README, MySQL setup step 7)
```

Surefire writes per-class reports to `target/surefire-reports/` (`*.txt` and `TEST-*.xml`).

All tests are deterministic. Algorithm tests pass explicit timestamps ("now" is a parameter; `Clock.fixed`
is used for validation), random inputs use fixed seeds, and no test waits for a fixed sleep. The two
threaded test classes wait for a **condition** with a deadline (poll every 50 ms, fail after 10–20 s).

## Results of the last run

Environment: Windows 11, OpenJDK 17.0.15 (Microsoft build), Maven 3.9.14 (wrapper), executed 2026-10-13
with `mvnw.cmd -o clean package`.

| Test class | Tests | Passed | Failed | Skipped |
|---|---|---|---|---|
| `alert.AlertManagerTest` | 9 | 9 | 0 | 0 |
| `dataset.CsvDatasetLoaderTest` | 8 | 8 | 0 | 0 |
| `dataset.DataValidatorTest` | 7 | 7 | 0 | 0 |
| `dataset.NoiseFilterTest` | 7 | 7 | 0 | 0 |
| `dataset.UsgsCatalogLoaderTest` | 3 | 3 | 0 | 0 |
| `processing.CommunicationMonitorTest` | 6 | 6 | 0 | 0 |
| `processing.ConfidenceCalculatorTest` | 6 | 6 | 0 | 0 |
| `processing.DatasetReplayTest` | 10 | 10 | 0 | 0 |
| `processing.EventCorrelatorTest` | 8 | 8 | 0 | 0 |
| `processing.EventDetectorTest` | 4 | 4 | 0 | 0 |
| `processing.EventProcessingPipelineTest` | 5 | 5 | 0 | 0 |
| `processing.SensorFaultDetectorTest` | 5 | 5 | 0 | 0 |
| `processing.SeverityClassifierTest` | 5 | 5 | 0 | 0 |
| `repository.H2RepositoryIntegrationTest` | 8 | 8 | 0 | 0 |
| `repository.MySqlRepositoryIntegrationTest` | 8 | 0 | 0 | **8** |
| `service.SeismicEventServiceIntegrationTest` | 3 | 3 | 0 | 0 |
| `simulation.SensorSimulatorTest` | 6 | 6 | 0 | 0 |
| **Total** | **108** | **100** | **0** | **8** |

**Skipped tests.** `MySqlRepositoryIntegrationTest` is skipped because no MySQL server was installed on the
development machine (`SEISMIC_TEST_DB_URL` not set). The same 8 contract tests **did** run against H2 in
MySQL compatibility mode, using the real `schema.sql`. **The JDBC code has therefore not been verified
against a real MySQL server.** Run step 7 of the README's MySQL setup to do that.

**Known failures:** none in the last run.

## Required scenarios and the tests that cover them

| # | Required behaviour | Test(s) | Result |
|---|---|---|---|
| 1 | Normal readings do not generate a confirmed event | `EventProcessingPipelineTest.normalReadingsDoNotGenerateAnyEvent`; `DatasetReplayTest` (t = 0–14 s produce nothing) | pass |
| 2 | A single high-amplitude reading creates a candidate, not a confirmed event | `EventProcessingPipelineTest.singleHighReadingCreatesOnlyACandidateThatIsDismissed`; `DatasetReplayTest.isolatedSpikeIsDismissedNotConfirmed`; `SeverityClassifierTest.singleSensorIsCappedAtMinorEvenWhenVeryStrong` | pass |
| 3 | Nearby correlated sensors produce an event | `EventProcessingPipelineTest.correlatedReadingsFromNearbySensorsConfirmAnEvent`; `EventCorrelatorTest.nearbyCandidatesWithinTheWindowFormOneEvent`; `DatasetReplayTest.minorEventInNorthZoneIsConfirmedAsMinor`, `majorEventInCentralZoneIsConfirmedAsMajorOrCritical` | pass |
| 4 | Readings outside the correlation window are not grouped | `EventCorrelatorTest.candidatesOutsideTheCorrelationWindowAreNotGrouped`, `simultaneousCandidatesFarApartAreSeparateEvents`, `inconsistentFrequencyStartsANewEvent`, `veryShortBurstDoesNotJoinAnEvent` | pass |
| 5 | A repeated isolated abnormal sensor is flagged | `SensorFaultDetectorTest.repeatedIsolatedAnomaliesFlagTheSensorAsFaulty`; `DatasetReplayTest.repeatedFaultySensorIsFlaggedFaulty` | pass |
| 6 | A correlated event is not mistaken for a faulty sensor | `SensorFaultDetectorTest.sensorThatTriggersFirstInARealEventIsNotPenalised` (the first sensor keeps reliability 100 across 3 events); `DatasetReplayTest.noFalseEventsFromFaultyOrNoisySensors` | pass |
| 7 | An offline sensor is detected after the timeout | `CommunicationMonitorTest.sensorGoesOfflineOnlyAfterTheTimeout` (not at 5.0 s, yes at 5.5 s), `offlineSensorRecoversAfterConsecutiveReadings`; `EventProcessingPipelineTest.missingReadingsMakeTheSensorOffline`; `SeismicEventServiceIntegrationTest.communicationFailureMakesASensorOfflineAndItRecovers` (real threads) | pass |
| 8 | Invalid readings are rejected or flagged | `DataValidatorTest` (7 tests); `CsvDatasetLoaderTest.badNumericValuesAreKeptAsInvalidRecords`; `EventProcessingPipelineTest.invalidReadingsStillCountAsCommunication` | pass |
| 9 | Noise filtering uses the same sensor, in order | `NoiseFilterTest.batchFilteringProcessesEachSensorInTimestampOrder`, `differentSensorsAreNeverAveragedTogether`, `largeTimeGapRestartsTheWindow`, `outOfOrderReadingIsNotAddedToTheWindow` | pass |
| 10 | Confidence stays within 0–100 | `ConfidenceCalculatorTest.confidenceAlwaysStaysBetween0And100` (500 random events, seed 42) plus exact worked examples 97.2 and 35.0 | pass |
| 11 | Severity follows the configured rules | `SeverityClassifierTest` (base levels, CRITICAL conditions, widespread rule, both caps) | pass |
| 12 | Duplicate alerts are controlled | `AlertManagerTest.unchangedEventDoesNotRaiseDuplicateAlerts`, `severityDowngradeDoesNotRaiseAnAlert`, `offlineSensorIsAlertedOnceUntilItRecovers`, `repeatedProcessingErrorsAreRateLimited` | pass |
| 13 | CSV import handles malformed rows and missing columns | `CsvDatasetLoaderTest.malformedRowsAreRejectedWithReasons`, `missingRequiredColumnsFailWithAClearMessage`, `bundledDatasetLoadsWithExpectedCounts`; `UsgsCatalogLoaderTest` | pass |
| 14 | JDBC repositories work against a test database | `H2RepositoryIntegrationTest` (8 pass, incl. transaction rollback and "restart" reload); `MySqlRepositoryIntegrationTest` (**skipped: no MySQL**); `SeismicEventServiceIntegrationTest.majorEventIsDetectedAlertedPersistedAndReloadedAfterRestart` | pass on H2 / not run on MySQL |

### Additional coverage

| Area | Test |
|---|---|
| End-to-end pipeline on the 1,493-row bundled dataset: minor, major/critical, uncertain, dismissed spike, FAULTY (outliers), FAULTY (invalid), OFFLINE + recovery, noisy ≠ faulty, offline ≠ faulty | `DatasetReplayTest` (10 tests) |
| Concurrency: all 15 sensors report from a 4-thread pool, stop leaves no threads, readings reproducible after reset, 40 concurrent scenario triggers | `SensorSimulatorTest` |
| Live service with threads + H2: major event detected, alerted, persisted, and reloaded by a new service instance (simulated restart); contributing readings via the JOIN | `SeismicEventServiceIntegrationTest` |
| Transaction rollback: a dataset whose last reading violates a foreign key leaves no metadata and no readings | `RepositoryContractTest.datasetIsSavedInOneTransactionOrNotAtAll` |
| CSV export → re-import round trip (quoted fields) | `CsvDatasetLoaderTest.exportedReadingsCanBeLoadedAgain` |
| Alert priority queue order | `AlertManagerTest.mostUrgentAlertComesFirst` |

## GUI verification

The GUI cannot be unit-tested here, so it was checked by running it:

1. **Packaged jar with the scripted smoke test** (`java -Dseismic.smokeTest=docs\screenshots -jar target\SeismicEngine.jar`).
   The app launched on Windows 11, pressed **Start** through the real button, and triggered a major event,
   a communication failure (S05) and a sensor fault (S11) through the service. It switched between all five
   screens, loaded and analysed the bundled dataset with the real buttons, saved six screenshots to
   [`docs/screenshots/`](screenshots), and exited cleanly ("Application closed" in the log). Observed in the
   log and screenshots: a CRITICAL event with 14 sensors and confidence 94, S05 OFFLINE after 5.3 s then
   ACTIVE after 3 readings, S11 FAULTY after 3 isolated anomalies, plus a reliability-drop finding.
2. **`mvnw.cmd -o javafx:run`**: the JVM started with JavaFX on the module path, and the log showed a
   complete initialisation (config loaded, demo mode selected, history loaded). The process was then closed.

**Not verified automatically:** the native file dialogs (Import CSV, Export, Import USGS catalog). The services
behind those buttons are covered by `CsvDatasetLoaderTest`, `UsgsCatalogLoaderTest` and the export round-trip
test. The app was not run on macOS or Linux.

## Problems found and fixed during development

| Problem | Found by | Fix |
|---|---|---|
| Dataset replay ran a final timer tick 6 s after the last reading, so every sensor went OFFLINE | `DatasetReplayTest` (2 failures) | The replay now ends with `finish()` only; the end of data is not silence |
| Restarting monitoring after Stop would time out every sensor instantly (old heartbeats) | code review of the Stop/Start path | `CommunicationMonitor.startTracking` forgets old heartbeats |
| One event raised 4 alerts in 5 s while its severity climbed, and a later downgrade would also have alerted | UI smoke test screenshot | Alerts only on first confirmation and on severity **increases** (`severityDowngradeDoesNotRaiseAnAlert`) |
| The communication-failure integration test used fixed `Thread.sleep` calls | review against the "no arbitrary sleeps" rule | Fast config with a 7 s outage; the test waits only for conditions |
| Dashboard "latest event" showed an unconfirmed single-sensor candidate | UI smoke test screenshot | Prefers the newest correlated event |
