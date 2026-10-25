package com.seismic.controller;

import com.seismic.dataset.DatasetLoadResult;
import com.seismic.dataset.DatasetStatisticsService.DatasetStatistics;
import com.seismic.dataset.DatasetStatisticsService.SensorStats;
import com.seismic.dataset.NoiseFilter;
import com.seismic.dataset.RejectedRecord;
import com.seismic.dataset.UsgsCatalogLoader.CatalogLoadResult;
import com.seismic.model.CatalogEarthquake;
import com.seismic.model.EventStatus;
import com.seismic.model.ReadingQuality;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.processing.DatasetAnalyzer.AnalysisResult;
import com.seismic.service.DatasetService;
import com.seismic.service.DatasetService.AnalysisOutcome;
import com.seismic.ui.FxTasks;
import com.seismic.ui.UiFormat;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Dataset screen: load, import, validate, preview, filter, analyse and export datasets. */
public final class DatasetController {

    private static final String ALL = "All qualities";

    private final DatasetService datasetService;
    private final ObservableList<SeismicReading> previewRows = FXCollections.observableArrayList();
    private final FilteredList<SeismicReading> filteredRows = new FilteredList<>(previewRows);
    private DatasetLoadResult loaded;
    private AnalysisOutcome analysis;

    @FXML private Button analyzeButton;
    @FXML private Button exportReadingsButton;
    @FXML private Button exportEventsButton;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private Label datasetStatus;
    @FXML private Label fileLabel;
    @FXML private Label totalLabel;
    @FXML private Label validLabel;
    @FXML private Label suspiciousLabel;
    @FXML private Label invalidLabel;
    @FXML private Label rejectedLabel;
    @FXML private Label rangeLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> qualityFilter;
    @FXML private Label shownLabel;
    @FXML private TableView<SeismicReading> previewTable;
    @FXML private TableView<RejectedRecord> rejectedTable;
    @FXML private TableView<SensorStats> statsTable;
    @FXML private ListView<String> labelCountsList;
    @FXML private Label amplitudeStatsLabel;
    @FXML private Label analysisSummary;
    @FXML private TableView<SeismicEvent> analysisTable;
    @FXML private ListView<String> analysisLog;
    @FXML private TableView<CatalogEarthquake> catalogTable;
    @FXML private Label catalogSummary;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @FXML
    private void initialize() {
        previewTable.getColumns().setAll(List.of(
                UiFormat.column("Reading", 75, SeismicReading::getReadingId),
                UiFormat.column("Sensor", 55, SeismicReading::getSensorId),
                UiFormat.column("Time (local)", 135, r -> UiFormat.dateTime(r.getTimestamp())),
                UiFormat.column("Amplitude", 75, r -> UiFormat.number(r.getAmplitude(), 4)),
                UiFormat.column("Filtered", 70, r -> UiFormat.number(r.getFilteredAmplitude(), 4)),
                UiFormat.column("Freq Hz", 60, r -> UiFormat.number(r.getFrequencyHz(), 2)),
                UiFormat.column("Dur s", 50, r -> UiFormat.number(r.getDurationSeconds(), 2)),
                UiFormat.column("Noise", 50, r -> UiFormat.number(r.getNoiseLevel(), 2)),
                UiFormat.column("Quality", 85, r -> r.getQuality().name()),
                UiFormat.column("Label", 115, SeismicReading::getScenarioLabel),
                UiFormat.column("Validation note", 260, SeismicReading::getQualityNote)));
        previewTable.setItems(filteredRows);
        previewTable.setPlaceholder(UiFormat.placeholder("No dataset loaded. Click \"Load bundled dataset\" or \"Import CSV...\"."));

        rejectedTable.getColumns().setAll(List.of(
                UiFormat.column("Line", 50, RejectedRecord::lineNumber),
                UiFormat.column("Reason", 260, RejectedRecord::reason),
                UiFormat.column("Raw line", 520, RejectedRecord::rawLine)));
        rejectedTable.setPlaceholder(UiFormat.placeholder("No rejected rows."));

        statsTable.getColumns().setAll(List.of(
                UiFormat.column("Sensor", 60, SensorStats::sensorId),
                UiFormat.column("Readings", 70, SensorStats::readings),
                UiFormat.column("Invalid", 60, SensorStats::invalidReadings),
                UiFormat.column("Mean amp", 80, s -> UiFormat.number(s.meanAmplitude(), 4)),
                UiFormat.column("Max amp", 80, s -> UiFormat.number(s.maxAmplitude(), 4)),
                UiFormat.column("Mean noise", 80, s -> UiFormat.number(s.meanNoiseLevel(), 3))));
        statsTable.setPlaceholder(UiFormat.placeholder("Load a dataset to see statistics."));

        analysisTable.getColumns().setAll(List.of(
                UiFormat.column("Event", 200, SeismicEvent::getEventId),
                UiFormat.column("Start (local)", 135, e -> UiFormat.dateTime(e.getStartTime())),
                UiFormat.column("Status", 85, e -> e.getStatus().name()),
                UiFormat.column("Severity", 80, e -> e.getSeverity().name()),
                UiFormat.column("Confidence", 80, e -> UiFormat.number(e.getConfidenceScore(), 1)),
                UiFormat.column("Sensors", 60, SeismicEvent::getParticipatingSensorCount),
                UiFormat.column("Zone", 220, SeismicEvent::getAffectedZoneSummary)));
        analysisTable.setPlaceholder(UiFormat.placeholder("Click \"Analyse dataset\" to run the detection engine on the loaded data."));

        catalogTable.getColumns().setAll(List.of(
                UiFormat.column("Id", 100, CatalogEarthquake::eventId),
                UiFormat.column("Time (local)", 135, q -> UiFormat.dateTime(q.time())),
                UiFormat.column("Magnitude", 75, q -> UiFormat.number(q.magnitude(), 1) + " " + q.magnitudeType()),
                UiFormat.column("Depth km", 70, q -> UiFormat.number(q.depthKm(), 1)),
                UiFormat.column("Latitude", 75, q -> UiFormat.number(q.latitude(), 3)),
                UiFormat.column("Longitude", 75, q -> UiFormat.number(q.longitude(), 3)),
                UiFormat.column("Place", 300, CatalogEarthquake::place)));
        catalogTable.setPlaceholder(UiFormat.placeholder("Import a USGS CSV export (e.g. data/usgs_sample_catalog.csv)."));

        qualityFilter.getItems().add(ALL);
        for (ReadingQuality quality : List.of(ReadingQuality.VALID, ReadingQuality.SUSPICIOUS, ReadingQuality.INVALID)) {
            qualityFilter.getItems().add(quality.name());
        }
        qualityFilter.getSelectionModel().selectFirst();
        searchField.textProperty().addListener(o -> applyFilter());
        qualityFilter.valueProperty().addListener(o -> applyFilter());
        busyIndicator.setVisible(false);
        updateButtons();
    }

    // ------------------------------------------------------------------ actions

    @FXML
    private void onLoadBundled() {
        runBusy("Loading bundled dataset", datasetService::loadBundled, this::showLoaded);
    }

    @FXML
    private void onImportCsv() {
        File file = chooseFile("Import sensor-reading CSV", false);
        if (file != null) {
            runBusy("Importing " + file.getName(), () -> datasetService.importCsv(file.toPath()), this::showLoaded);
        }
    }

    @FXML
    private void onAnalyze() {
        if (loaded == null) {
            return;
        }
        DatasetLoadResult current = loaded;
        runBusy("Running detection engine on " + current.readings().size() + " readings",
                () -> datasetService.analyze(current), this::showAnalysis);
    }

    @FXML
    private void onExportReadings() {
        if (loaded == null) {
            return;
        }
        File file = chooseFile("Export processed readings", true);
        if (file != null) {
            List<SeismicReading> rows = processedReadings();
            runBusy("Exporting readings", () -> {
                datasetService.exportReadings(rows, file.toPath());
                return rows.size();
            }, count -> datasetStatus.setText("Exported " + count + " readings to " + file.getName()));
        }
    }

    @FXML
    private void onExportEvents() {
        if (analysis == null) {
            return;
        }
        File file = chooseFile("Export detected events", true);
        if (file != null) {
            List<SeismicEvent> events = analysis.result().events();
            runBusy("Exporting events", () -> {
                datasetService.exportEvents(events, file.toPath());
                return events.size();
            }, count -> datasetStatus.setText("Exported " + count + " events to " + file.getName()));
        }
    }

    @FXML
    private void onImportCatalog() {
        File file = chooseFile("Import USGS earthquake catalog CSV", false);
        if (file != null) {
            runBusy("Importing catalog", () -> datasetService.importCatalog(file.toPath()), this::showCatalog);
        }
    }

    // ------------------------------------------------------------------ display

    private void showLoaded(DatasetLoadResult result) {
        loaded = result;
        analysis = null;
        var m = result.metadata();
        fileLabel.setText(m.fileName() + " (" + m.sourceType() + ")");
        totalLabel.setText(String.valueOf(m.totalRecords()));
        validLabel.setText(String.valueOf(m.validRecords()));
        suspiciousLabel.setText(String.valueOf(m.suspiciousRecords()));
        invalidLabel.setText(String.valueOf(m.invalidRecords()));
        rejectedLabel.setText(String.valueOf(m.rejectedRecords()));
        rangeLabel.setText(UiFormat.dateTime(m.firstReadingAt()) + "  to  " + UiFormat.dateTime(m.lastReadingAt()));
        previewRows.setAll(processedReadings());
        rejectedTable.getItems().setAll(result.rejected());
        showStatistics(datasetService.statistics(result));
        analysisTable.getItems().clear();
        analysisLog.getItems().clear();
        analysisSummary.setText("Not analysed yet.");
        datasetStatus.setText("Loaded " + result.readings().size() + " readings; " + result.rejected().size()
                + " row(s) rejected. Readings are shown with their moving-average value.");
        applyFilter();
        updateButtons();
    }

    private void showStatistics(DatasetStatistics stats) {
        statsTable.getItems().setAll(stats.perSensor().values());
        List<String> labels = new ArrayList<>();
        stats.countsByQuality().forEach((quality, count) -> labels.add("quality " + quality + ": " + count));
        stats.countsByScenarioLabel().forEach((label, count) -> labels.add("label " + label + ": " + count));
        labelCountsList.getItems().setAll(labels);
        amplitudeStatsLabel.setText(String.format(Locale.ROOT, "Usable amplitude: min %s, mean %s, max %s",
                UiFormat.number(stats.minAmplitude(), 4), UiFormat.number(stats.meanAmplitude(), 4),
                UiFormat.number(stats.maxAmplitude(), 4)));
    }

    private void showAnalysis(AnalysisOutcome outcome) {
        analysis = outcome;
        AnalysisResult result = outcome.result();
        previewRows.setAll(result.processedReadings());
        applyFilter();
        analysisTable.getItems().setAll(result.events());
        List<String> log = new ArrayList<>();
        result.dismissedCandidates().forEach(e -> log.add("Dismissed isolated anomaly on " + e.getOriginSensorId()
                + " at " + UiFormat.time(e.getStartTime()) + String.format(Locale.ROOT, " (raw peak %.2f)", e.getPeakAmplitude())));
        result.statusChanges().forEach(c -> log.add(UiFormat.time(c.at()) + "  " + c.sensorId() + ": " + c.oldStatus()
                + " -> " + c.newStatus() + " (" + c.reason() + ")"));
        result.faultFindings().forEach(f -> log.add(UiFormat.time(f.at()) + "  fault check " + f.sensorId() + ": " + f.description()));
        analysisLog.getItems().setAll(log);
        analysisSummary.setText(String.format("%d event(s): %d resolved/confirmed, %d uncertain; %d isolated anomalies dismissed. %s",
                result.events().size(),
                result.events().stream().filter(e -> e.getStatus() == EventStatus.RESOLVED
                        || e.getStatus() == EventStatus.CONFIRMED).count(),
                result.events().stream().filter(e -> e.getStatus() == EventStatus.UNCERTAIN).count(),
                result.dismissedCandidates().size(), outcome.storageMessage()));
        datasetStatus.setText("Analysis complete. Events were also added to the Event History screen.");
        updateButtons();
    }

    private void showCatalog(CatalogLoadResult result) {
        catalogTable.getItems().setAll(result.earthquakes());
        catalogSummary.setText(result.fileName() + ": " + result.earthquakes().size() + " earthquakes, "
                + result.rejected().size() + " rejected row(s). Real catalog events are listed only; they are not "
                + "converted into sensor readings because the catalog has no raw sensor data.");
        datasetStatus.setText("Catalog imported.");
    }

    private void applyFilter() {
        String text = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        String quality = qualityFilter.getValue();
        filteredRows.setPredicate(r -> (quality == null || quality.equals(ALL) || r.getQuality().name().equals(quality))
                && (text.isEmpty() || r.getReadingId().toLowerCase(Locale.ROOT).contains(text)
                || r.getSensorId().toLowerCase(Locale.ROOT).contains(text)
                || r.getScenarioLabel().toLowerCase(Locale.ROOT).contains(text)
                || r.getQualityNote().toLowerCase(Locale.ROOT).contains(text)));
        shownLabel.setText("Showing " + filteredRows.size() + " of " + previewRows.size());
    }

    /** Readings after analysis, or moving-average filtered readings if not analysed yet. */
    private List<SeismicReading> processedReadings() {
        if (analysis != null) {
            return analysis.result().processedReadings();
        }
        return loaded == null ? List.of() : NoiseFilter.applyAll(loaded.readings(), datasetService.getConfig().filter());
    }

    private void updateButtons() {
        analyzeButton.setDisable(loaded == null);
        exportReadingsButton.setDisable(loaded == null);
        exportEventsButton.setDisable(analysis == null);
    }

    private <T> void runBusy(String message, Callable<T> work, Consumer<T> onSuccess) {
        busyIndicator.setVisible(true);
        datasetStatus.setText(message + "...");
        FxTasks.run(work, result -> {
            busyIndicator.setVisible(false);
            onSuccess.accept(result);
        }, error -> {
            busyIndicator.setVisible(false);
            datasetStatus.setText(message + " failed.");
            UiFormat.showError(message + " failed", error);
        });
    }

    private File chooseFile(String title, boolean save) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        File dataDir = Path.of("data").toFile();
        if (dataDir.isDirectory()) {
            chooser.setInitialDirectory(dataDir.getAbsoluteFile());
        }
        Window window = previewTable.getScene().getWindow();
        if (save) {
            chooser.setInitialFileName("export.csv");
            return chooser.showSaveDialog(window);
        }
        return chooser.showOpenDialog(window);
    }
}
