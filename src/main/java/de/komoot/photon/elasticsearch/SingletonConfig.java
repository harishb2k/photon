package de.komoot.photon.elasticsearch;

import de.komoot.photon.CommandLineArgs;

public class SingletonConfig {
    public static String indexName = "photon";

    public static void init(CommandLineArgs args) {
        indexName = args.getIndex();
    }
}
