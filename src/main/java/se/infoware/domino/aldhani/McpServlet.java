package se.infoware.domino.aldhani;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** MCP endpoint mounted at /mcp by the Equinox HTTP registry. */
public class McpServlet extends HttpServlet {
    private static final Logger LOG = Logger.getLogger(McpServlet.class.getName());
    private static final int MAX_BODY = 1_000_000;

    private final Object gate = new Object();
    private Path configFile;
    private long configStamp = Long.MIN_VALUE;
    private String configError;
    private McpProtocol protocol;

    @Override
    public void init() {
        configFile = AldhaniConfig.configFile();
        refresh(true);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!acceptsJson(request)) {
            response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE);
            return;
        }
        if (!HttpGuard.originAllowed(request.getHeader("Origin"), request.getServerName())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            writeJson(response, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Origin is not allowed\"}}");
            return;
        }
        if (!HttpGuard.protocolHeaderOk(request.getHeader("MCP-Protocol-Version"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeJson(response, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"Unsupported protocol version\"}}");
            return;
        }
        McpProtocol current = refresh(false);
        if (current == null) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writeJson(response, "{\"error\":\"" + escape(configError) + "\"}");
            return;
        }
        if (!authorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            String origin = PublicOrigin.of(request, configuredOrigin());
            response.setHeader("WWW-Authenticate", "Aldhani realm=\"aldhani\"");
            response.addHeader("WWW-Authenticate",
                    "Bearer realm=\"aldhani\", resource_metadata=\"" + origin + "/.well-known/oauth-protected-resource/mcp\"");
            writeJson(response, "{\"error\":\"Unauthorized\"}");
            return;
        }
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = readBody(request);
        boolean stream = acceptsEventStream(request);
        AtomicBoolean opened = new AtomicBoolean();
        Writer[] writer = new Writer[1];
        if (stream) {
            McpProtocol.ON_REQUEST.set(() -> openStream(response, opened, writer));
            McpProtocol.LIVE.set(message -> {
                openStream(response, opened, writer);
                try {
                    writer[0].write("event: message\ndata: " + Json.write(message) + "\n\n");
                    writer[0].flush();
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
        }
        McpProtocol.HttpReply reply;
        try {
            reply = current.handle(body, request.getHeader("MCP-Session-Id"));
        } finally {
            McpProtocol.ON_REQUEST.remove();
            McpProtocol.LIVE.remove();
        }
        if (reply.openSession()) {
            String session = request.getHeader("MCP-Session-Id");
            if (session == null || session.isBlank()) {
                session = UUID.randomUUID().toString();
            }
            response.setHeader("MCP-Session-Id", session);
        }
        if (opened.get()) {
            if (!reply.suppressed() && reply.body() != null) {
                writer[0].write("event: message\ndata: " + reply.body() + "\n\n");
                writer[0].flush();
            }
            return;
        }
        response.setStatus(reply.status());
        if (reply.body() != null) {
            writeJson(response, reply.body());
        }
    }

    private static void openStream(HttpServletResponse response, AtomicBoolean opened, Writer[] writer) {
        if (!opened.compareAndSet(false, true)) {
            return;
        }
        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType("text/event-stream");
            response.setHeader("Cache-Control", "no-cache");
            writer[0] = response.getWriter();
            writer[0].write("id: 1\ndata:\n\n");
            writer[0].flush();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private McpProtocol refresh(boolean logSuccess) {
        synchronized (gate) {
            try {
                long stamp = Files.getLastModifiedTime(configFile).toMillis();
                if (protocol != null && stamp == configStamp) {
                    return protocol;
                }
                AldhaniConfig config = AldhaniConfig.load(configFile);
                protocol = new McpProtocol(new DesignTools(config, new KyberClient(config)));
                configStamp = stamp;
                configError = null;
                if (logSuccess) {
                    LOG.info("Aldhani loaded " + configFile);
                }
                return protocol;
            } catch (Exception ex) {
                protocol = null;
                configStamp = Long.MIN_VALUE;
                configError = ex.getMessage() == null ? "Could not load aldhani.json" : ex.getMessage();
                LOG.log(Level.SEVERE, "Aldhani could not load " + configFile, ex);
                return null;
            }
        }
    }

    private boolean authorized(HttpServletRequest request) {
        String presented = tokenFrom(request);
        if (presented == null) {
            return false;
        }
        String expected = bearer();
        if (expected != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return presented.indexOf('.') > 0 && OauthStore.open(configFile).accessValid(presented);
    }

    private String configuredOrigin() {
        try {
            return AldhaniConfig.load(configFile).publicBaseUrl;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Session authentication on Domino consumes {@code Authorization: Basic}. An opaque
     * {@code Authorization: Bearer} is intercepted too, unless the bearer is a JWT this
     * server signed and the internet site trusts Aldhani as its OIDC provider.
     * {@code Authorization: Aldhani} and {@code X-Aldhani-Token} remain direct checks
     * against {@code bearerToken}.
     */
    private static String tokenFrom(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null) {
            String token = schemeToken(header, "Aldhani ");
            if (token == null) {
                token = schemeToken(header, "Bearer ");
            }
            if (token != null) {
                return token;
            }
        }
        String custom = request.getHeader("X-Aldhani-Token");
        if (custom == null) {
            return null;
        }
        String token = custom.trim();
        return token.isEmpty() ? null : token;
    }

    private static String schemeToken(String header, String scheme) {
        if (!header.regionMatches(true, 0, scheme, 0, scheme.length())) {
            return null;
        }
        String token = header.substring(scheme.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private String bearer() {
        synchronized (gate) {
            if (protocol == null) {
                return null;
            }
        }
        try {
            return AldhaniConfig.load(configFile).bearerToken;
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept == null || accept.isBlank()) {
            return true;
        }
        String lower = accept.toLowerCase();
        return lower.contains("application/json")
                || lower.contains("text/event-stream")
                || lower.contains("*/*");
    }

    private static boolean acceptsEventStream(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.toLowerCase().contains("text/event-stream");
    }

    private static String readBody(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[4096];
        try (var reader = request.getReader()) {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (body.length() + count > MAX_BODY) {
                    throw new IOException("MCP body is larger than " + MAX_BODY + " characters");
                }
                body.append(buffer, 0, count);
            }
        }
        return body.toString();
    }

    private static void writeJson(HttpServletResponse response, String json) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(json);
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
