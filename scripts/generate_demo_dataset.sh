#!/bin/sh
# Regenerates data/seismic_sensor_readings.csv and the import samples (macOS/Linux).
# The generator is written in Java (com.seismic.dataset.DemoDatasetGenerator) so that the bundled
# dataset uses exactly the same signal model and scenario effects as the live simulator.
set -e
cd "$(dirname "$0")/.."
./mvnw -q -B compile
java -cp target/classes com.seismic.dataset.DemoDatasetGenerator data
