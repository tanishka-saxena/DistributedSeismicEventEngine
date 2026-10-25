@echo off
rem Regenerates data\seismic_sensor_readings.csv and the import samples (Windows).
rem The generator is written in Java (com.seismic.dataset.DemoDatasetGenerator) so that the bundled
rem dataset uses exactly the same signal model and scenario effects as the live simulator.
cd /d "%~dp0.."
call mvnw.cmd -q -B compile
if errorlevel 1 exit /b 1
java -cp target\classes com.seismic.dataset.DemoDatasetGenerator data
