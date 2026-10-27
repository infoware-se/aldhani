package se.infoware.domino.aldhani;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Last aldhani.json that parsed. A later save that does not parse keeps this one,
 * so the caller token stays valid while the file is broken.
 */
final class ActiveConfig {
    private static final ActiveConfig SHARED = new ActiveConfig();
    private volatile AldhaniConfig current;

    static ActiveConfig shared() {
        return SHARED;
    }

    void remember(AldhaniConfig config) {
        current = config;
    }

    AldhaniConfig open(Path file) throws IOException {
        return open(() -> AldhaniConfig.load(file));
    }

    AldhaniConfig open(Loader loader) throws IOException {
        try {
            AldhaniConfig loaded = loader.load();
            current = loaded;
            return loaded;
        } catch (RuntimeException | IOException ex) {
            AldhaniConfig kept = current;
            if (kept != null) {
                return kept;
            }
            if (ex instanceof IOException io) {
                throw io;
            }
            throw ex;
        }
    }

    @FunctionalInterface
    interface Loader {
        AldhaniConfig load() throws IOException;
    }
}
