@echo off
rem Double-click this file to start the Distributed Seismic Event Detection Engine.
rem It runs from the project folder so data\ and config\ are found.
cd /d "%~dp0"

if not exist "target\SeismicEngine.jar" (
    echo Runnable jar not found - building it once with Maven...
    call mvnw.cmd -q -B -DskipTests package
    if errorlevel 1 (
        echo Build failed. See the messages above.
        pause
        exit /b 1
    )
)

java -jar "target\SeismicEngine.jar"
if errorlevel 1 pause
