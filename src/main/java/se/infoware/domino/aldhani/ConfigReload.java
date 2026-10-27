package se.infoware.domino.aldhani;

/**
 * Reloads Aldhani when aldhani.json changes. A file that does not parse leaves the
 * last good protocol in place and is tried again on the next call.
 */
final class ConfigReload {
    private McpProtocol protocol;
    private long loadedStamp = Long.MIN_VALUE;
    private long failedStamp = Long.MIN_VALUE;
    private String error;

    Outcome apply(long stamp, Loader loader) {
        if (protocol != null && stamp == loadedStamp) {
            return Outcome.unchanged(protocol);
        }
        try {
            protocol = loader.load();
            loadedStamp = stamp;
            failedStamp = Long.MIN_VALUE;
            error = null;
            return Outcome.loaded(protocol);
        } catch (Exception ex) {
            error = ex.getMessage() == null ? "Could not load aldhani.json" : ex.getMessage();
            boolean report = stamp != failedStamp;
            failedStamp = stamp;
            return Outcome.failed(protocol, error, report, ex);
        }
    }

    @FunctionalInterface
    interface Loader {
        McpProtocol load() throws Exception;
    }

    record Outcome(McpProtocol protocol, String error, boolean loaded, boolean reportFailure, Exception failure) {
        static Outcome unchanged(McpProtocol protocol) {
            return new Outcome(protocol, null, false, false, null);
        }

        static Outcome loaded(McpProtocol protocol) {
            return new Outcome(protocol, null, true, false, null);
        }

        static Outcome failed(McpProtocol protocol, String error, boolean report, Exception failure) {
            return new Outcome(protocol, error, false, report, failure);
        }
    }
}
