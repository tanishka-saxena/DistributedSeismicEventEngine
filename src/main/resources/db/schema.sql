-- =====================================================================================
-- Distributed Seismic Event Detection Engine - MySQL 8.x schema
--
-- Run once:   mysql -u root -p < src/main/resources/db/schema.sql
-- The application also runs the CREATE TABLE statements at start-up (they are idempotent).
--
-- Conventions
--   * All timestamps are stored in UTC as DATETIME(3) (millisecond precision, no time zone).
--   * Synthetic measurements use DOUBLE; scores shown to users use DECIMAL.
--   * Status / severity values are stored as text so the data is readable in MySQL Workbench;
--     CHECK constraints (enforced by MySQL 8.0.16+) restrict them to the Java enum values.
--   * All data in this database is SIMULATED, except the optional real catalog metadata.
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS seismic_engine CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE seismic_engine;

-- One row per virtual sensor node, with its latest known state.
CREATE TABLE IF NOT EXISTS sensors (
    sensor_id           VARCHAR(16)   NOT NULL,
    sensor_name         VARCHAR(80)   NOT NULL,
    zone                VARCHAR(40)   NOT NULL,
    latitude            DECIMAL(9,6)  NULL,
    longitude           DECIMAL(9,6)  NULL,
    status              VARCHAR(12)   NOT NULL,
    reliability_score   DECIMAL(5,2)  NOT NULL,
    last_heartbeat_utc  DATETIME(3)   NULL,
    updated_at_utc      DATETIME(3)   NOT NULL,
    PRIMARY KEY (sensor_id),
    INDEX idx_sensors_zone (zone),
    CONSTRAINT chk_sensors_status CHECK (status IN ('ACTIVE', 'OFFLINE', 'FAULTY', 'MAINTENANCE')),
    CONSTRAINT chk_sensors_reliability CHECK (reliability_score BETWEEN 0 AND 100)
);

-- One row per start/stop period of live monitoring.
CREATE TABLE IF NOT EXISTS simulation_runs (
    run_id              BIGINT        NOT NULL AUTO_INCREMENT,
    started_at_utc      DATETIME(3)   NOT NULL,
    ended_at_utc        DATETIME(3)   NULL,
    random_seed         BIGINT        NOT NULL,
    sample_interval_ms  INT           NOT NULL,
    sensor_count        INT           NOT NULL,
    readings_processed  BIGINT        NOT NULL DEFAULT 0,
    events_detected     INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id),
    INDEX idx_runs_started (started_at_utc),
    CONSTRAINT chk_runs_interval CHECK (sample_interval_ms > 0)
);

-- One row per dataset file that was analysed and saved.
CREATE TABLE IF NOT EXISTS dataset_metadata (
    dataset_id          BIGINT        NOT NULL AUTO_INCREMENT,
    file_name           VARCHAR(255)  NOT NULL,
    source_type         VARCHAR(16)   NOT NULL,
    loaded_at_utc       DATETIME(3)   NOT NULL,
    total_records       INT           NOT NULL,
    valid_records       INT           NOT NULL,
    suspicious_records  INT           NOT NULL,
    invalid_records     INT           NOT NULL,
    rejected_records    INT           NOT NULL,
    first_reading_utc   DATETIME(3)   NULL,
    last_reading_utc    DATETIME(3)   NULL,
    notes               VARCHAR(500)  NULL,
    PRIMARY KEY (dataset_id),
    CONSTRAINT chk_dataset_source CHECK (source_type IN ('BUNDLED', 'IMPORTED', 'USGS_CATALOG')),
    CONSTRAINT chk_dataset_counts CHECK (total_records = valid_records + suspicious_records + invalid_records + rejected_records)
);

-- Every processed reading (live simulation or dataset). Raw and filtered amplitude are both kept.
-- Exactly one of run_id / dataset_id is normally set, to show where the reading came from.
CREATE TABLE IF NOT EXISTS readings (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    reading_id          VARCHAR(64)   NOT NULL,
    sensor_id           VARCHAR(16)   NOT NULL,
    run_id              BIGINT        NULL,
    dataset_id          BIGINT        NULL,
    reading_time_utc    DATETIME(3)   NOT NULL,
    amplitude           DOUBLE        NULL,
    filtered_amplitude  DOUBLE        NULL,
    frequency_hz        DOUBLE        NULL,
    duration_s          DOUBLE        NULL,
    noise_level         DOUBLE        NULL,
    quality             VARCHAR(12)   NOT NULL,
    quality_note        VARCHAR(255)  NULL,
    source              VARCHAR(12)   NOT NULL,
    scenario_label      VARCHAR(40)   NULL,
    PRIMARY KEY (id),
    INDEX idx_readings_sensor_time (sensor_id, reading_time_utc),
    INDEX idx_readings_time (reading_time_utc),
    INDEX idx_readings_run (run_id),
    INDEX idx_readings_dataset (dataset_id),
    CONSTRAINT fk_readings_sensor FOREIGN KEY (sensor_id) REFERENCES sensors (sensor_id),
    CONSTRAINT fk_readings_run FOREIGN KEY (run_id) REFERENCES simulation_runs (run_id) ON DELETE SET NULL,
    CONSTRAINT fk_readings_dataset FOREIGN KEY (dataset_id) REFERENCES dataset_metadata (dataset_id) ON DELETE CASCADE,
    CONSTRAINT chk_readings_quality CHECK (quality IN ('UNCHECKED', 'VALID', 'SUSPICIOUS', 'INVALID')),
    CONSTRAINT chk_readings_source CHECK (source IN ('SIMULATION', 'DATASET'))
);

-- Correlated events (CONFIRMED / RESOLVED / UNCERTAIN). Dismissed single-sensor candidates are not stored.
CREATE TABLE IF NOT EXISTS detected_events (
    event_id                VARCHAR(40)   NOT NULL,
    status                  VARCHAR(12)   NOT NULL,
    severity                VARCHAR(10)   NOT NULL,
    confidence              DECIMAL(5,2)  NOT NULL,
    agreement_score         DECIMAL(4,3)  NOT NULL,
    temporal_score          DECIMAL(4,3)  NULL,
    spatial_score           DECIMAL(4,3)  NULL,
    signal_score            DECIMAL(4,3)  NOT NULL,
    start_time_utc          DATETIME(3)   NOT NULL,
    last_activity_utc       DATETIME(3)   NOT NULL,
    resolved_at_utc         DATETIME(3)   NULL,
    origin_sensor_id        VARCHAR(16)   NOT NULL,
    origin_latitude         DECIMAL(9,6)  NULL,
    origin_longitude        DECIMAL(9,6)  NULL,
    affected_zone           VARCHAR(120)  NOT NULL,
    mean_frequency_hz       DOUBLE        NULL,
    source                  VARCHAR(12)   NOT NULL,
    run_id                  BIGINT        NULL,
    dataset_id              BIGINT        NULL,
    explanation             TEXT          NULL,
    updated_at_utc          DATETIME(3)   NOT NULL,
    PRIMARY KEY (event_id),
    INDEX idx_events_start (start_time_utc),
    INDEX idx_events_severity_status (severity, status),
    CONSTRAINT fk_events_origin FOREIGN KEY (origin_sensor_id) REFERENCES sensors (sensor_id),
    CONSTRAINT fk_events_run FOREIGN KEY (run_id) REFERENCES simulation_runs (run_id) ON DELETE SET NULL,
    CONSTRAINT fk_events_dataset FOREIGN KEY (dataset_id) REFERENCES dataset_metadata (dataset_id) ON DELETE CASCADE,
    CONSTRAINT chk_events_status CHECK (status IN ('CANDIDATE', 'CONFIRMED', 'UNCERTAIN', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT chk_events_severity CHECK (severity IN ('NORMAL', 'MINOR', 'MODERATE', 'MAJOR', 'CRITICAL')),
    CONSTRAINT chk_events_confidence CHECK (confidence BETWEEN 0 AND 100)
);

-- Many-to-many link between events and the sensors that took part in them.
CREATE TABLE IF NOT EXISTS event_sensor_associations (
    event_id                VARCHAR(40)   NOT NULL,
    sensor_id               VARCHAR(16)   NOT NULL,
    is_origin               BOOLEAN       NOT NULL DEFAULT FALSE,
    first_trigger_utc       DATETIME(3)   NOT NULL,
    last_trigger_utc        DATETIME(3)   NOT NULL,
    peak_amplitude          DOUBLE        NOT NULL,
    peak_filtered_amplitude DOUBLE        NOT NULL,
    first_frequency_hz      DOUBLE        NULL,
    distance_from_origin_km DOUBLE        NULL,
    candidate_readings      INT           NOT NULL,
    PRIMARY KEY (event_id, sensor_id),
    INDEX idx_assoc_sensor (sensor_id),
    CONSTRAINT fk_assoc_event FOREIGN KEY (event_id) REFERENCES detected_events (event_id) ON DELETE CASCADE,
    CONSTRAINT fk_assoc_sensor FOREIGN KEY (sensor_id) REFERENCES sensors (sensor_id)
);

-- Operator alerts and their acknowledgement state.
CREATE TABLE IF NOT EXISTS alerts (
    alert_id            VARCHAR(40)   NOT NULL,
    event_id            VARCHAR(40)   NULL,
    sensor_id           VARCHAR(16)   NULL,
    alert_type          VARCHAR(24)   NOT NULL,
    severity            VARCHAR(10)   NOT NULL,
    description         VARCHAR(500)  NOT NULL,
    created_at_utc      DATETIME(3)   NOT NULL,
    acknowledged        BOOLEAN       NOT NULL DEFAULT FALSE,
    acknowledged_at_utc DATETIME(3)   NULL,
    PRIMARY KEY (alert_id),
    INDEX idx_alerts_created (created_at_utc),
    INDEX idx_alerts_open (acknowledged, severity),
    CONSTRAINT fk_alerts_event FOREIGN KEY (event_id) REFERENCES detected_events (event_id) ON DELETE SET NULL,
    CONSTRAINT fk_alerts_sensor FOREIGN KEY (sensor_id) REFERENCES sensors (sensor_id),
    CONSTRAINT chk_alerts_severity CHECK (severity IN ('INFO', 'WARNING', 'HIGH', 'CRITICAL'))
);
