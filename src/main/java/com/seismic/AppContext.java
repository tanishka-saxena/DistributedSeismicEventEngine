package com.seismic;

import com.seismic.alert.AlertManager;
import com.seismic.config.ConfigLoader;
import com.seismic.config.ProcessingConfig;
import com.seismic.config.SimulationConfig;
import com.seismic.database.DatabaseConfig;
import com.seismic.database.DatabaseConnection;
import com.seismic.database.SchemaInitializer;
import com.seismic.model.SensorNetwork;
import com.seismic.repository.Repositories;
import com.seismic.repository.RepositoryException;
import com.seismic.service.DatasetService;
import com.seismic.service.IdGenerator;
import com.seismic.service.SeismicEventService;
import com.seismic.simulation.SensorNetworkFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Properties;

/**
 * Composition root: creates every object once and wires them together through constructors.
 * No framework is used, so the whole object graph can be read in this one class.
 */
public final class AppContext implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AppContext.class);

    private final SensorNetwork network;
    private final Repositories repositories;
    private final SeismicEventService eventService;
    private final DatasetService datasetService;

    private AppContext(SensorNetwork network, Repositories repositories, SeismicEventService eventService,
                       DatasetService datasetService) {
        this.network = network;
        this.repositories = repositories;
        this.eventService = eventService;
        this.datasetService = datasetService;
    }

    /** Builds the application. Connects to MySQL if configured, otherwise (or on failure) uses demo mode. */
    public static AppContext create() throws IOException {
        Clock clock = Clock.systemUTC();
        Properties engineProperties = ConfigLoader.loadOptional(ConfigLoader.DEFAULT_ENGINE_CONFIG);
        ProcessingConfig processingConfig = ProcessingConfig.fromProperties(engineProperties);
        SimulationConfig simulationConfig = SimulationConfig.fromProperties(engineProperties);
        SensorNetwork network = SensorNetworkFactory.loadDefault();

        Repositories repositories = connectRepositories(DatabaseConfig.load(), network);
        AlertManager alertManager = new AlertManager(clock, new IdGenerator("AL", clock.instant()), 2_000);
        SeismicEventService eventService = new SeismicEventService(processingConfig, simulationConfig, network,
                repositories, alertManager, clock);
        eventService.loadHistory();
        DatasetService datasetService = new DatasetService(processingConfig, network, repositories, eventService, clock);
        return new AppContext(network, repositories, eventService, datasetService);
    }

    static Repositories connectRepositories(DatabaseConfig config, SensorNetwork network) {
        if (!config.isConfigured()) {
            log.warn("No database configured; running in demo mode");
            return Repositories.inMemory("no database configured (see config/database.properties.example)");
        }
        DatabaseConnection connection = new DatabaseConnection(config);
        try {
            connection.verify();
            try (Connection c = connection.open()) {
                int tables = SchemaInitializer.createTables(c);
                log.info("Schema check complete ({} table statements)", tables);
            }
            Repositories repositories = Repositories.jdbc(connection);
            repositories.sensors().saveAll(network.sensors());
            log.info("Connected to {}", config.safeTarget());
            return repositories;
        } catch (SQLException e) {
            String message = DatabaseConnection.friendlyMessage(e, config);
            log.error("MySQL unavailable: {}", message);
            return Repositories.inMemory(message);
        } catch (RepositoryException e) {
            log.error("MySQL unavailable: {}", e.getMessage());
            return Repositories.inMemory(e.getMessage());
        }
    }

    public SensorNetwork getNetwork() { return network; }
    public Repositories getRepositories() { return repositories; }
    public SeismicEventService getEventService() { return eventService; }
    public DatasetService getDatasetService() { return datasetService; }

    @Override
    public void close() {
        eventService.close();
    }
}
