package se.infoware.domino.aldhani;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Calls a running Aldhani servlet. The sandbox aldhanitest.nsf is expected to
 * contain SmokeForm, SmokeView, the SmokeAgent* agents, and SmokeXPage.xsp.
 */
public final class AldhaniSmoke {
    private static final String NSF = "aldhanitest.nsf";
    private static final Map<String, List<String>> DESIGN = Map.of(
            "form", List.of("SmokeForm"),
            "view", List.of("SmokeView"),
            "agent", List.of(
                    "SmokeAgentFormula",
                    "SmokeAgentImported",
                    "SmokeAgentJava",
                    "SmokeAgentLs",
                    "SmokeAgentSimple"),
            "xpage", List.of("SmokeXPage.xsp"));

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : "https://dantooine.oninfoware.local/mcp";
        Path configFile = Path.of(args.length > 1 ? args[1] : "C:\\Domino\\Data\\aldhani.json");
        AldhaniConfig config = AldhaniConfig.load(configFile);
        HttpClient http = client();
        expectStatus("GET", 405, send(http, url, "GET", null, null));
        Reply anonymous = send(http, url, "POST", initializeBody(), null);
        expectStatus("anonymous initialize", 401, anonymous);
        String challenge = anonymous.authenticate == null ? "" : anonymous.authenticate;
        if (!challenge.regionMatches(true, 0, "Aldhani", 0, "Aldhani".length())) {
            throw new AssertionError("WWW-Authenticate should use the Aldhani scheme");
        }
        Reply init = send(http, url, "POST", initializeBody(), config.bearerToken);
        expectStatus("initialize", 200, init);
        Map<String, Object> server = Json.object(Json.object(result(init)).get("serverInfo"));
        expect("aldhani", server.get("name"));
        expect(McpProtocol.VERSION, server.get("version"));

        Reply listed = call(http, url, config.bearerToken, "tools/list", Map.of());
        List<String> toolNames = new ArrayList<>();
        for (Object item : (List<?>) Json.object(result(listed)).get("tools")) {
            toolNames.add(Json.string(Json.object(item).get("name")));
        }
        expect(List.of("list_databases", "list_design", "get_design", "export_design"), toolNames);

        String databases = tool(http, url, config.bearerToken, "list_databases", Map.of());
        Map<String, Object> listedDatabases = Json.object(Json.parse(databases));
        expect(List.of(NSF), listedDatabases.get("allowedNsf"));
        expect(List.of(), listedDatabases.get("missing"));
        List<?> found = (List<?>) listedDatabases.get("databases");
        expect(1, found.size());
        Map<String, Object> database = Json.object(found.get(0));
        expect(NSF, database.get("nsfPath"));
        expect("Aldhani Smoke Test", database.get("dbTitle"));

        for (Map.Entry<String, List<String>> entry : DESIGN.entrySet()) {
            String text = tool(http, url, config.bearerToken, "list_design", Map.of("type", entry.getKey(), "nsfPath", NSF));
            List<String> names = designNames(text, entry.getKey());
            for (String name : entry.getValue()) {
                if (!names.contains(name)) {
                    throw new AssertionError(entry.getKey() + " is missing " + name);
                }
            }
            String sample = entry.getValue().get(0);
            if ("agent".equals(entry.getKey())) {
                sample = "SmokeAgentSimple";
            }
            Map<String, Object> element = Json.object(Json.parse(
                    tool(http, url, config.bearerToken, "get_design", Map.of(
                            "type", entry.getKey(),
                            "name", sample,
                            "nsfPath", NSF))));
            expect(sample, element.get("name"));
            String dxl = Json.string(element.get("dxl"));
            if (dxl == null || !dxl.contains(sample.contains(".") ? sample.substring(0, sample.indexOf('.')) : sample)) {
                throw new AssertionError("DXL for " + sample + " does not name that element");
            }
        }

        for (String type : DesignTools.collections()) {
            Json.object(Json.parse(tool(http, url, config.bearerToken, "list_design", Map.of("type", type, "nsfPath", NSF))));
        }
        for (String type : DesignTools.singletons()) {
            String dxl = Json.string(Json.object(Json.parse(
                    tool(http, url, config.bearerToken, "get_design", Map.of("type", type, "nsfPath", NSF)))).get("dxl"));
            if (dxl == null || dxl.isBlank()) {
                throw new AssertionError(type + " returned no DXL");
            }
        }
        String templates = tool(http, url, config.bearerToken, "list_design", Map.of("type", "defaults"));
        if (!templates.contains("\"elements\"") && !templates.contains("\"defaultNsf\"")) {
            throw new AssertionError("defaults returned no elements");
        }
        if (!tool(http, url, config.bearerToken, "get_design", Map.of("type", "defaults", "name", "form")).contains("\"dxl\"")) {
            throw new AssertionError("defaults form template returned no DXL");
        }

        String refused = tool(http, url, config.bearerToken, "get_design", Map.of(
                "type", "form",
                "name", "SmokeForm",
                "nsfPath", "kybertest.nsf"));
        if (!refused.contains("allowlist")) {
            throw new AssertionError("A database outside the allowlist was readable");
        }

        String exported = tool(http, url, config.bearerToken, "export_design", Map.of("nsfPath", NSF));
        if (!exported.contains("\"nsfPath\":\"" + NSF + "\"") || !exported.contains("\"dxl\"")) {
            throw new AssertionError("export_design did not return DXL for " + NSF);
        }
        System.out.println("aldhani smoke ok: " + NSF
                + " collections=" + DesignTools.collections().size()
                + " notes=" + DesignTools.singletons().size()
                + " form=SmokeForm view=SmokeView agents=5 xpage=SmokeXPage.xsp");
    }

    private static String initializeBody() {
        return Json.write(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of())));
    }

    private static Reply call(HttpClient http, String url, String token, String method, Map<String, Object> params) {
        Reply reply = send(http, url, "POST", Json.write(Map.of(
                "jsonrpc", "2.0",
                "id", method,
                "method", method,
                "params", params)), token);
        expectStatus(method, 200, reply);
        return reply;
    }

    private static String tool(HttpClient http, String url, String token, String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);
        Reply reply = call(http, url, token, "tools/call", params);
        Map<String, Object> result = Json.object(result(reply));
        Object content = result.get("content");
        String text = "";
        if (content instanceof List<?> blocks && !blocks.isEmpty()) {
            text = Json.string(Json.object(blocks.get(0)).get("text"));
        }
        boolean error = Boolean.TRUE.equals(result.get("isError"));
        boolean outsideAllowlist = "get_design".equals(name) && "kybertest.nsf".equals(arguments.get("nsfPath"));
        if (outsideAllowlist) {
            if (!error) {
                throw new AssertionError("A database outside the allowlist was readable");
            }
            return text == null ? "" : text;
        }
        if (error) {
            throw new AssertionError(name + " failed: " + clip(text));
        }
        return text == null ? "" : text;
    }

    private static List<String> designNames(String text, String type) {
        String key = switch (type) {
            case "form" -> "Forms";
            case "view" -> "Views";
            case "agent" -> "Agents";
            case "xpage" -> "XPages";
            default -> throw new AssertionError(type);
        };
        Object raw = Json.object(Json.parse(text)).get(key);
        if (!(raw instanceof List<?> list)) {
            throw new AssertionError(type + " response has no " + key);
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            String name = Json.string(item);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static Object result(Reply reply) {
        Map<String, Object> message = Json.object(Json.parse(reply.body));
        if (message.get("error") instanceof Map<?, ?> error) {
            throw new AssertionError(Json.string(Json.object(error).get("message")));
        }
        return message.get("result");
    }

    private static Reply send(HttpClient http, String url, String method, String body, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json");
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json; charset=utf-8");
            request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        if (token != null) {
            request.header("Authorization", "Aldhani " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Reply(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("WWW-Authenticate").orElse(null));
        } catch (Exception ex) {
            throw new AssertionError("Could not call " + url + ": " + ex.getMessage());
        }
    }

    private static HttpClient client() {
        try {
            TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(context)
                    .sslParameters(parameters)
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build TLS context", ex);
        }
    }

    private static void expectStatus(String step, int expected, Reply reply) {
        if (reply.status != expected) {
            throw new AssertionError(step + " returned HTTP " + reply.status + ": " + clip(reply.body));
        }
    }

    private static void expect(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 240 ? flat : flat.substring(0, 240);
    }

    private record Reply(int status, String body, String authenticate) {
    }
}
