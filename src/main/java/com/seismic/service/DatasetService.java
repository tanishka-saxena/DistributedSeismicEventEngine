package com.seismic.service;

import com.seismic.config.ProcessingConfig;
import com.seismic.dataset.CsvDatasetLoader;
import com.seismic.dataset.CsvExporter;
import com.seismic.dataset.DataValidator;
import com.seismic.dataset.DatasetFormatException;
import com.seismic.dataset.DatasetLoadResult;
import com.seismic.dataset.DatasetStatisticsService;
import com.seismic.dataset.DatasetStatisticsService.DatasetStatistics;
import com.seismic.dataset.UsgsCatalogLoader;
import com.seismic.dataset.UsgsCatalogLoader.CatalogLoadResult;
import com.seismic.model.DatasetMetadata;
import com.seismic.model.DatasetSourceType;
import com.seismic.model.SeismicEvent;
import com.seismic.model.SeismicReading;
import com.seismic.model.SensorNetwork;
import com.seismic.processing.DatasetAnalyzer;
import com.seismic.processing.DatasetAnalyzer.AnalysisResult;
import com.seismic.repository.Repositories;
import com.seismic.repository.RepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * Use cases of the Dataset screen. Every method may do file or database I/O, so the UI calls them from
 * background tasks. The class holds no mutable state and is safe to use from several threads.
 */
public final class DatasetService {

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);
    public static final Path BUNDLED_DATASET = Path.of("data", "seismic_sensor_readings.csv");

    /**
     * @param saved          metadata with database ID, or null if saving failed or storage is in memory
     * @param storageMessage what happened to the results, shown to the user
     */
    public record AnalysisOutcome(AnalysisResult result, DatasetMetadata saved, String storageMessage) {
    }

    private final ProcessingConfig config;
    private final SensorNetwork network;
    private final Repositories repositories;
    private final SeismicEventService eventService;
    private final Clock clock;
    private final DataValidator validator;
    private final CsvDatasetLoader loader;
    private final UsgsCatalogLoader catalogLoader = new UsgsCatalogLoader();
    private final DatasetStatisticsService statisticsService = new DatasetStatisticsService();
    private final CsvExporter exporter = new CsvExporter();

    public DatasetService(ProcessingConfig config, SensorNetwork network, Repositories repositories,
                          SeismicEventService eventService, Clock clock) {
        this.config = config;
        this.network = network;
        this.repositories = repositories;
        this.eventService = eventService;
        this.clock = clock;
        this.validator = new DataValidator(clock);
        this.loader = new CsvDatasetLoader(validator, clock);
    }

    public DatasetLoadResult loadBundled() throws IOException, DatasetFormatException {
        return loader.load(BUNDLED_DATASET, DatasetSourceType.BUNDLED, network);
    }

    public DatasetLoadResult importCsv(Path file) throws IOException, DatasetFormatException {
        return loader.load(file, DatasetSourceType.IMPORTED, network);
    }

    public CatalogLoadResult importCatalog(Path file) throws IOException, DatasetFormatException {
        return catalogLoader.load(file);
    }

    public DatasetStatistics statistics(DatasetLoadResult loaded) {
        return statisticsService.compute(loaded.readings(), loaded.rejected().size());
    }

    /**
     * Replays the dataset through the detection pipeline (on a copy of the network, so live monitoring is
     * unaffected). With MySQL, the dataset and its readings are saved in one transaction, then the events
     * in a second one. The events are also added to the Event History screen.
     */
    public AnalysisOutcome analyze(DatasetLoadResult loaded) {
        IdGenerator ids = new IdGenerator("EVD", clock.instant());
        AnalysisResult result = new DatasetAnalyzer(config, validator).analyze(loaded.readings(), network, ids);
        DatasetMetadata saved = null;
        String message;
        try {
            saved = repositories.datasets().saveDataset(loaded.metadata(), result.processedReadings());
            for (SeismicEvent event : result.events()) {
                event.setDatasetId(saved.datasetId());
            }
            repositories.events().saveAll(result.events());
            message = repositories.persistent()
                    ? "Saved to MySQL as dataset " + saved.datasetId() + " with " + result.processedReadings().size()
                    + " readings and " + result.events().size() + " events"
                    : "Demo mode: results are kept in memory only and are NOT saved";
        } catch (RepositoryException e) {
            log.error("Saving dataset analysis failed", e);
            message = "Analysis finished, but saving failed: " + e.getMessage();
        }
        eventService.addExternalEvents(result.events());
        return new AnalysisOutcome(result, saved, message);
    }

    public void exportReadings(List<SeismicReading> readings, Path target) throws IOException {
        exporter.exportReadings(readings, target);
    }

    public void exportEvents(List<SeismicEvent> events, Path target) throws IOException {
        exporter.exportEvents(events, target);
    }

    public ProcessingConfig getConfig() {
        return config;
    }
}
