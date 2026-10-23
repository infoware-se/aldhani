package se.infoware.domino.aldhani;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
        McpProtocol current = refresh(false);
        if (current == null) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writeJson(response, "{\"error\":\"" + escape(configError) + "\"}");
            return;
        }
        if (!authorized(request)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Aldhani realm=\"aldhani\"");
            writeJson(response, "{\"error\":\"Unauthorized\"}");
            return;
        }
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = readBody(request);
        McpProtocol.HttpReply reply = current.handle(body);
        response.setStatus(reply.status());
        if (reply.body() != null) {
            writeJson(response, reply.body());
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
        String expected = bearer();
        if (presented == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Domino's HTTP task handles {@code Authorization: Basic} and {@code Authorization: Bearer}
     * itself and answers with the session login page, so those schemes never reach this servlet.
     * {@code Authorization: Aldhani} is the scheme that gets through. Bearer still works on a
     * server that does not intercept it, and {@code X-Aldhani-Token} remains as a fallback.
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
