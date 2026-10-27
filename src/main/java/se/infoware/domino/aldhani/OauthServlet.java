package se.infoware.domino.aldhani;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** OAuth 2.1 and OIDC discovery for MCP clients. The design API stays on {@link McpServlet}. */
public class OauthServlet extends HttpServlet {
    private static final int MAX_BODY = 100_000;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        if (path.endsWith("/authorize")) {
            writeHtml(response, form(request, null));
            return;
        }
        if (path.endsWith("/jwks")) {
            writeJson(response, 200, Json.write(store().jwks()));
            return;
        }
        if (path.contains("oauth-protected-resource")) {
            writeJson(response, 200, Json.write(OauthStore.resource(origin(request))));
            return;
        }
        if (path.contains("openid-configuration") || path.contains("oauth-authorization-server")) {
            writeJson(response, 200, Json.write(metadata(origin(request))));
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = path(request);
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        Map<String, String> form = formBody(read(request), request.getContentType());
        try {
            if (path.endsWith("/authorize")) {
                response.sendRedirect(store().authorizationRedirect(
                        form.get("client_id"),
                        form.get("redirect_uri"),
                        form.get("code_challenge"),
                        form.get("code_challenge_method"),
                        form.get("state"),
                        subject(),
                        form.get("token"),
                        bearer()));
                return;
            }
            if (path.endsWith("/token")) {
                writeJson(response, 200, Json.write(token(form, origin(request))));
                return;
            }
            if (path.endsWith("/register")) {
                writeJson(response, 201, Json.write(register(form)));
                return;
            }
        } catch (IllegalArgumentException ex) {
            if (path.endsWith("/authorize")) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                writeHtml(response, postedForm(form, "The token was not accepted."));
                return;
            }
            writeJson(response, 400, Json.write(Map.of("error", "invalid_request", "error_description", ex.getMessage())));
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private Map<String, Object> token(Map<String, String> form, String origin) {
        String grant = form.get("grant_type");
        OauthStore.Access access;
        if ("authorization_code".equals(grant)) {
            access = store().exchangeCode(
                    form.get("client_id"),
                    form.get("redirect_uri"),
                    form.get("code"),
                    form.get("code_verifier"),
                    origin + "/oauth",
                    origin);
        } else if ("refresh_token".equals(grant)) {
            access = store().refresh(form.get("refresh_token"), origin + "/oauth", origin);
        } else {
            throw new IllegalArgumentException("unsupported_grant_type");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", access.accessToken());
        body.put("token_type", "Bearer");
        body.put("expires_in", access.expiresIn());
        body.put("refresh_token", access.refreshToken());
        body.put("scope", OauthStore.SCOPE);
        return body;
    }

    private Map<String, Object> register(Map<String, String> form) {
        String raw = form.get("redirect_uris");
        List<String> redirects = new ArrayList<>();
        if (raw != null && raw.startsWith("[")) {
            Object parsed = Json.parse(raw);
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    String uri = Json.string(item);
                    if (uri != null) {
                        redirects.add(uri);
                    }
                }
            }
        } else if (raw != null && !raw.isBlank()) {
            redirects.add(raw);
        }
        String id = store().register(redirects);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", id);
        body.put("client_id_issued_at", System.currentTimeMillis() / 1000);
        body.put("redirect_uris", redirects);
        body.put("token_endpoint_auth_method", "none");
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        return body;
    }

    private static Map<String, Object> metadata(String origin) {
        String issuer = origin + "/oauth";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/authorize");
        body.put("token_endpoint", issuer + "/token");
        body.put("registration_endpoint", issuer + "/register");
        body.put("jwks_uri", issuer + "/jwks");
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("scopes_supported", List.of(OauthStore.SCOPE));
        return body;
    }

    private static String postedForm(Map<String, String> form, String error) {
        return page(error, value(form, "client_id"), value(form, "redirect_uri"), value(form, "code_challenge"),
                value(form, "code_challenge_method"), value(form, "state"));
    }

    private static String form(HttpServletRequest request, String error) {
        return page(error, query(request, "client_id"), query(request, "redirect_uri"), query(request, "code_challenge"),
                query(request, "code_challenge_method"), query(request, "state"));
    }

    private static String page(String error, String clientId, String redirect, String challenge, String method, String state) {
        String message = error == null ? "" : "<p>" + escape(error) + "</p>";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="utf-8"><title>Aldhani</title></head>
                <body>
                <h1>Aldhani</h1>
                %s
                <form method="post">
                <input type="hidden" name="client_id" value="%s">
                <input type="hidden" name="redirect_uri" value="%s">
                <input type="hidden" name="code_challenge" value="%s">
                <input type="hidden" name="code_challenge_method" value="%s">
                <input type="hidden" name="state" value="%s">
                <label>Token <input type="password" name="token" autocomplete="off" required></label>
                <button type="submit">Continue</button>
                </form>
                </body>
                </html>
                """.formatted(
                message,
                escape(clientId),
                escape(redirect),
                escape(challenge),
                escape(method),
                escape(state));
    }

    private static String value(Map<String, String> form, String key) {
        String text = form.get(key);
        return text == null ? "" : text;
    }

    private String origin(HttpServletRequest request) {
        String configured = null;
        try {
            configured = AldhaniConfig.load(AldhaniConfig.configFile()).publicBaseUrl;
        } catch (Exception ignored) {
            configured = null;
        }
        return PublicOrigin.of(request, configured);
    }

    private static String subject() {
        try {
            AldhaniConfig config = AldhaniConfig.load(AldhaniConfig.configFile());
            if (config.oauthSubject != null && !config.oauthSubject.isBlank()) {
                return config.oauthSubject;
            }
            return config.kyberUser;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Aldhani is not configured");
        }
    }

    private static String bearer() {
        try {
            return AldhaniConfig.load(AldhaniConfig.configFile()).bearerToken;
        } catch (Exception ex) {
            return null;
        }
    }

    private static OauthStore store() {
        return OauthStore.open(AldhaniConfig.configFile());
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null ? "" : uri;
    }

    private static String query(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value == null ? "" : value;
    }

    private static Map<String, String> formBody(String body, String contentType) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (contentType != null && contentType.contains("application/json") && body.startsWith("{")) {
            Map<String, Object> json = Json.object(Json.parse(body));
            for (Map.Entry<String, Object> entry : json.entrySet()) {
                if (entry.getValue() instanceof List<?> list) {
                    fields.put(entry.getKey(), Json.write(list));
                } else {
                    String text = Json.string(entry.getValue());
                    fields.put(entry.getKey(), text == null ? String.valueOf(entry.getValue()) : text);
                }
            }
            return fields;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = decode(eq < 0 ? "" : pair.substring(eq + 1));
            fields.put(key, value);
        }
        return fields;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String read(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[2048];
        try (var reader = request.getReader()) {
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (body.length() + count > MAX_BODY) {
                    throw new IOException("OAuth body is too large");
                }
                body.append(buffer, 0, count);
            }
        }
        return body.toString();
    }

    private static void writeJson(HttpServletResponse response, int status, String json) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(json);
    }

    private static void writeHtml(HttpServletResponse response, String html) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/html; charset=utf-8");
        response.getWriter().write(html);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
