package de.komoot.photon;


import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import de.komoot.photon.elasticsearch.CustomServer;
import de.komoot.photon.elasticsearch.Server;
import de.komoot.photon.elasticsearch.SingletonConfig;
import de.komoot.photon.nominatim.NominatimConnector;
import de.komoot.photon.nominatim.NominatimUpdater;
import de.komoot.photon.utils.CorsFilter;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import spark.Request;
import spark.Response;

import java.io.FileNotFoundException;
import java.io.IOException;

import static spark.Spark.before;
import static spark.Spark.get;
import static spark.Spark.ipAddress;
import static spark.Spark.port;


@Slf4j
public class App {

    public static void main(String[] rawArgs) throws Exception {
        // parse command line arguments
        CommandLineArgs args = new CommandLineArgs();
        final JCommander jCommander = new JCommander(args);
        try {
            jCommander.parse(rawArgs);
            if (args.isCorsAnyOrigin() && args.getCorsOrigin() != null) { // these are mutually exclusive
                throw new ParameterException("Use only one cors configuration type");
            }
        } catch (ParameterException e) {
            log.warn("could not start photon: " + e.getMessage());
            jCommander.usage();
            return;
        }

        // show help
        if (args.isUsage()) {
            jCommander.usage();
            return;
        }

        if (args.getJsonDump() != null) {
            startJsonDump(args);
            return;
        }

        // First thing first - we must setup configs
        SingletonConfig.init(args);

        // FIXME - (we use external ES so this if is true - we have CustomServer now)
        if (true) {
            CustomServer customServer = new CustomServer(args);
            customServer.start();
            if (args.isRecreateIndex()) {
                customServer.recreateIndex();
            }

            if (args.isNominatimImport()) {
                startNominatimImport(args, customServer);
            } else {
                startApi(args, customServer.getClient());
            }

            if (true) return;
        }

        boolean shutdownES = false;
        final Server esServer = new Server(args).start();
        try {
            Client esClient = esServer.getClient();

            if (args.isRecreateIndex()) {
                shutdownES = true;
                startRecreatingIndex(esServer);
                return;
            }

            if (args.isNominatimImport()) {
                shutdownES = true;
                startNominatimImport(args, esServer, esClient);
                return;
            }

            if (args.isNominatimUpdate()) {
                shutdownES = true;
                log.info("Ensuring that the cluster is ready, this might take some time.");
                // inspired by https://stackoverflow.com/a/50316299
                esClient.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
                final NominatimUpdater nominatimUpdater = setupNominatimUpdater(args, esClient);
                nominatimUpdater.update();
                return;
            }

            // no special action specified -> normal mode: start search API
            startApi(args, esClient);
        } finally {
            if (shutdownES) esServer.shutdown();
        }
    }


    /**
     * dump elastic search index and create a new and empty one
     *
     * @param esServer
     */
    private static void startRecreatingIndex(Server esServer) {
        try {
            esServer.recreateIndex();
        } catch (IOException e) {
            throw new RuntimeException("cannot setup index, elastic search config files not readable", e);
        }

        log.info("deleted photon index and created an empty new one.");
    }


    /**
     * take nominatim data and dump it to json
     *
     * @param args
     */
    private static void startJsonDump(CommandLineArgs args) {
        try {
            final String filename = args.getJsonDump();
            final JsonDumper jsonDumper = new JsonDumper(filename, args.getLanguages());
            NominatimConnector nominatimConnector = new NominatimConnector(args.getHost(), args.getPort(), args.getDatabase(), args.getUser(), args.getPassword());
            nominatimConnector.setImporter(jsonDumper);
            nominatimConnector.readEntireDatabase(args.getCountryCodes().split(","));
            log.info("json dump was created: " + filename);
        } catch (FileNotFoundException e) {
            log.error("cannot create dump", e);
        }
    }

    // FIXME - This is added to work with new CustomServer
    private static void startNominatimImport(CommandLineArgs args, CustomServer customServer) {
        try {
            customServer.recreateIndex(); // dump previous data
        } catch (IOException e) {
            throw new RuntimeException("cannot setup index, elastic search config files not readable", e);
        }

        TransportClient client = customServer.getClient();
        Importer importer = new de.komoot.photon.elasticsearch.CustomImporter(client, args.getLanguages());

        NominatimConnector nominatimConnector = new NominatimConnector(args.getHost(), args.getPort(), args.getDatabase(), args.getUser(), args.getPassword());
        nominatimConnector.setImporter(importer);
        nominatimConnector.readEntireDatabase(args.getCountryCodes().split(","));

        log.info("imported data from nominatim to photon with languages: " + args.getLanguages());
    }

    /**
     * take nominatim data to fill elastic search index
     *
     * @param args
     * @param esServer
     * @param esNodeClient
     */
    private static void startNominatimImport(CommandLineArgs args, Server esServer, Client esNodeClient) {
        try {
            esServer.recreateIndex(); // dump previous data
        } catch (IOException e) {
            throw new RuntimeException("cannot setup index, elastic search config files not readable", e);
        }

        log.info("starting import from nominatim to photon with languages: " + args.getLanguages());
        de.komoot.photon.elasticsearch.Importer importer = new de.komoot.photon.elasticsearch.Importer(esNodeClient, args.getLanguages());
        NominatimConnector nominatimConnector = new NominatimConnector(args.getHost(), args.getPort(), args.getDatabase(), args.getUser(), args.getPassword());
        nominatimConnector.setImporter(importer);
        nominatimConnector.readEntireDatabase(args.getCountryCodes().split(","));

        log.info("imported data from nominatim to photon with languages: " + args.getLanguages());
    }

    /**
     * Prepare Nominatim updater
     *
     * @param args
     * @param esNodeClient
     */
    private static NominatimUpdater setupNominatimUpdater(CommandLineArgs args, Client esNodeClient) {
        NominatimUpdater nominatimUpdater = new NominatimUpdater(args.getHost(), args.getPort(), args.getDatabase(), args.getUser(), args.getPassword());
        Updater updater = new de.komoot.photon.elasticsearch.Updater(esNodeClient, args.getLanguages());
        nominatimUpdater.setUpdater(updater);
        return nominatimUpdater;
    }

    /**
     * start api to accept search requests via http
     *
     * @param args
     * @param esNodeClient
     */
    private static void startApi(CommandLineArgs args, Client esNodeClient) {
        port(args.getListenPort());
        ipAddress(args.getListenIp());

        String allowedOrigin = args.isCorsAnyOrigin() ? "*" : args.getCorsOrigin();
        if (allowedOrigin != null) {
            CorsFilter.enableCORS(allowedOrigin, "get", "*");
        } else {
            before((request, response) -> {
                response.type("application/json; charset=UTF-8"); // in the other case set by enableCors
            });
        }

        // setup search API
        get("api", new SearchRequestHandler("api", esNodeClient, args.getLanguages(), args.getDefaultLanguage()));
        get("api/", new SearchRequestHandler("api/", esNodeClient, args.getLanguages(), args.getDefaultLanguage()));
        get("reverse", new ReverseSearchRequestHandler("reverse", esNodeClient, args.getLanguages(), args.getDefaultLanguage()));
        get("reverse/", new ReverseSearchRequestHandler("reverse/", esNodeClient, args.getLanguages(), args.getDefaultLanguage()));

        // setup update API
        final NominatimUpdater nominatimUpdater = setupNominatimUpdater(args, esNodeClient);
        get("/nominatim-update", (Request request, Response response) -> {
            new Thread(() -> nominatimUpdater.update()).start();
            return "nominatim update started (more information in console output) ...";
        });
    }
}
