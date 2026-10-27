package se.infoware.domino.aldhani;

import java.net.URI;

/** Transport checks from the Streamable HTTP specification. */
final class HttpGuard {
    private HttpGuard() {
    }

    /** A missing header is the 2025-03-26 assumption. An unknown value is rejected. */
    static boolean protocolHeaderOk(String header) {
        if (header == null || header.isBlank()) {
            return true;
        }
        return McpProtocol.supports(header.trim());
    }

    /**
     * A missing Origin is a non-browser client. A present Origin must be this host
     * or a loopback host, which is the DNS-rebinding check.
     */
    static boolean originAllowed(String origin, String serverName) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            if (host == null) {
                return false;
            }
            if (serverName != null && host.equalsIgnoreCase(serverName)) {
                return true;
            }
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
