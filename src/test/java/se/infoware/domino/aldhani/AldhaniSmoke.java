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
 * When the write tools are listed, the run creates AldhaniSmokeProbe and deletes it.
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
        assertDiscovery(http, url);
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
        String instructions = Json.string(Json.object(result(init)).get("instructions"));
        if (instructions == null || !instructions.contains("Kyber")) {
            throw new AssertionError("initialize instructions are missing");
        }
        Reply resources = call(http, url, config.bearerToken, "resources/list", Map.of());
        boolean sawDatabase = false;
        for (Object item : (List<?>) Json.object(result(resources)).get("resources")) {
            if (("aldhani://" + NSF).equals(Json.object(item).get("uri"))) {
                sawDatabase = true;
            }
        }
        if (!sawDatabase) {
            throw new AssertionError("allowlisted database is not a resource");
        }
        Reply completion = call(http, url, config.bearerToken, "completion/complete", Map.of(
                "argument", Map.of("name", "type", "value", "fo")));
        List<?> values = (List<?>) Json.object(Json.object(result(completion)).get("completion")).get("values");
        if (!values.contains("forms") || !values.contains("folders")) {
            throw new AssertionError("type completion missed forms or folders");
        }
        Map<String, Object> capabilities = Json.object(Json.object(result(init)).get("capabilities"));
        if (!capabilities.containsKey("prompts") || !capabilities.containsKey("logging")) {
            throw new AssertionError("prompts or logging capability is missing");
        }
        Reply prompts = call(http, url, config.bearerToken, "prompts/list", Map.of());
        List<?> promptList = (List<?>) Json.object(result(prompts)).get("prompts");
        if (promptList.isEmpty() || !"review_design".equals(Json.object(promptList.get(0)).get("name"))) {
            throw new AssertionError("review_design prompt is missing");
        }
        call(http, url, config.bearerToken, "logging/setLevel", Map.of("level", "info"));

        Reply wrongToken = send(http, url, "POST", initializeBody(), "wrong-token");
        expectStatus("wrong token", 401, wrongToken);
        expectStatus("DELETE", 405, send(http, url, "DELETE", null, config.bearerToken));
        call(http, url, config.bearerToken, "ping", Map.of());
        Reply notice = send(http, url, "POST", """
                {"jsonrpc":"2.0","method":"notifications/initialized"}""", config.bearerToken);
        expectStatus("notification", 202, notice);
        Reply unknown = call(http, url, config.bearerToken, "nope", Map.of());
        expect(-32601, ((Number) Json.object(Json.object(Json.parse(unknown.body)).get("error")).get("code")).intValue());

        Reply listed = call(http, url, config.bearerToken, "tools/list", Map.of());
        List<?> toolDefs = (List<?>) Json.object(result(listed)).get("tools");
        List<String> toolNames = new ArrayList<>();
        for (Object item : toolDefs) {
            toolNames.add(Json.string(Json.object(item).get("name")));
        }
        List<String> expected = new ArrayList<>(List.of(
                "list_databases", "list_design", "get_design", "export_design", "inventory_design",
                "repo_path", "sync_repo"));
        boolean writes = toolNames.contains("create_design");
        if (writes) {
            expected.addAll(List.of(
                    "create_database", "create_design", "update_design", "delete_design", "validate_design"));
        }
        expect(expected, toolNames);
        if (!Json.object(toolDefs.get(0)).containsKey("outputSchema")) {
            throw new AssertionError("list_databases has no outputSchema");
        }
        if (Json.object(toolDefs.get(3)).containsKey("outputSchema")) {
            throw new AssertionError("export_design should stay free of an output schema");
        }
        assertToolHints(toolDefs, writes);
        Map<String, Object> layout = Json.object(Json.parse(tool(http, url, config.bearerToken, "repo_path", Map.of(
                "type", "form",
                "name", "SmokeForm",
                "noteId", "1"))));
        expect("package-explorer", layout.get("layout"));
        expect("Forms/SmokeForm-1.dxl", layout.get("path"));

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
            assertUnid(sample, element.get("unid"));
            assertUnid(sample + " list", listedElement(text, sample).get("unid"));
            String dxl = Json.string(element.get("dxl"));
            if (dxl == null || !dxl.contains(sample.contains(".") ? sample.substring(0, sample.indexOf('.')) : sample)) {
                throw new AssertionError("DXL for " + sample + " does not name that element");
            }
        }

        for (String type : DesignTools.collections()) {
            Json.object(Json.parse(tool(http, url, config.bearerToken, "list_design", Map.of("type", type, "nsfPath", NSF))));
        }
        expectError(http, url, config.bearerToken, "list_design", Map.of("type", "defaults", "nsfPath", NSF));
        expectError(http, url, config.bearerToken, "list_design", Map.of("type", "acl", "nsfPath", NSF));
        expectError(http, url, config.bearerToken, "get_design", Map.of("type", "form", "nsfPath", NSF));
        expectError(http, url, config.bearerToken, "list_design", Map.of("type", "widget", "nsfPath", NSF));
        assertListLimit(http, url, config.bearerToken);
        for (String type : DesignTools.singletons()) {
            String dxl = Json.string(Json.object(Json.parse(
                    tool(http, url, config.bearerToken, "get_design", Map.of("type", type, "nsfPath", NSF)))).get("dxl"));
            if (dxl == null || dxl.isBlank()) {
                throw new AssertionError(type + " returned no DXL");
            }
        }
        String refused = tool(http, url, config.bearerToken, "get_design", Map.of(
                "type", "form",
                "name", "SmokeForm",
                "nsfPath", "kybertest.nsf"));
        if (!refused.contains("allowlist")) {
            throw new AssertionError("A database outside the allowlist was readable");
        }

        assertExport(tool(http, url, config.bearerToken, "export_design", Map.of("nsfPath", NSF)));
        assertWrites(http, url, config.bearerToken, writes);
        System.out.println("aldhani smoke ok: " + NSF
                + " writes=" + writes
                + " collections=" + DesignTools.collections().size()
                + " notes=" + DesignTools.singletons().size()
                + " form=SmokeForm view=SmokeView agents=5 xpage=SmokeXPage.xsp");
    }

    private static void assertDiscovery(HttpClient http, String mcpUrl) {
        URI mcp = URI.create(mcpUrl);
        int port = mcp.getPort();
        boolean standard = port < 0
                || ("https".equalsIgnoreCase(mcp.getScheme()) && port == 443)
                || ("http".equalsIgnoreCase(mcp.getScheme()) && port == 80);
        String origin = mcp.getScheme() + "://" + mcp.getHost() + (standard ? "" : ":" + port);
        Reply metadata = send(http, origin + "/oauth/.well-known/openid-configuration", "GET", null, null);
        expectStatus("oauth discovery", 200, metadata);
        Map<String, Object> document = Json.object(Json.parse(metadata.body));
        expect(origin + "/oauth", document.get("issuer"));
        expect(List.of("S256"), document.get("code_challenge_methods_supported"));
        Reply resource = send(http, origin + "/.well-known/oauth-protected-resource/mcp", "GET", null, null);
        expectStatus("oauth resource", 200, resource);
        expect(origin + "/mcp", Json.object(Json.parse(resource.body)).get("resource"));
    }

    private static void assertToolHints(List<?> toolDefs, boolean writes) {
        for (Object item : toolDefs) {
            Map<String, Object> tool = Json.object(item);
            String name = Json.string(tool.get("name"));
            Map<String, Object> annotations = Json.object(tool.get("annotations"));
            boolean read = !"create_design".equals(name) && !"update_design".equals(name)
                    && !"delete_design".equals(name) && !"sync_repo".equals(name)
                    && !"create_database".equals(name);
            expect(read, annotations.get("readOnlyHint"));
            expect("delete_design".equals(name), annotations.get("destructiveHint"));
        }
        if (!writes) {
            return;
        }
        expect(true, toolDefs.size() == 12);
    }

    private static void assertListLimit(HttpClient http, String url, String token) {
        String listed = tool(http, url, token, "list_design", Map.of("type", "agent", "nsfPath", NSF));
        List<String> names = designNames(listed, "agent");
        if (names.size() < 2) {
            throw new AssertionError("the sandbox needs at least two agents to prove limit");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("type", "agent");
        arguments.put("nsfPath", NSF);
        arguments.put("limit", 1);
        Map<String, Object> limited = Json.object(Json.parse(tool(http, url, token, "list_design", arguments)));
        expect(List.of(names.get(0)), limited.get("Agents"));
        expect(1, ((List<?>) limited.get("elements")).size());
        expect(Boolean.TRUE, limited.get("truncated"));
        expect(1, ((Number) limited.get("returned")).intValue());
        expect(names.size(), ((Number) limited.get("total")).intValue());
        assertUnid(names.get(0), Json.object(((List<?>) limited.get("elements")).get(0)).get("unid"));
    }

    private static void assertExport(String exported) {
        Map<String, Object> json = Json.object(Json.parse(exported));
        expect(NSF, json.get("nsfPath"));
        if (Boolean.TRUE.equals(json.get("truncated"))) {
            if (json.get("chars") == null || exported.contains("\"dxl\"")) {
                throw new AssertionError("a truncated export must keep its size and omit DXL");
            }
            return;
        }
        Object elements = json.get("elements");
        if (!(elements instanceof List<?> list) || list.isEmpty() || !exported.contains("\"dxl\"")) {
            throw new AssertionError("export_design did not return DXL for " + NSF);
        }
    }

    private static void assertWrites(HttpClient http, String url, String token, boolean writes) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("type", "form");
        probe.put("name", "AldhaniSmokeProbe");
        probe.put("nsfPath", NSF);
        if (!writes) {
            expectError(http, url, token, "create_design", probe);
            expectError(http, url, token, "update_design", Map.of(
                    "type", "form", "name", "AldhaniSmokeProbe", "dxl", "<form/>", "nsfPath", NSF));
            expectError(http, url, token, "delete_design", probe);
            expectError(http, url, token, "validate_design", Map.of(
                    "nsfPath", NSF,
                    "elements", List.of(Map.of("type", "form", "name", "Broken", "dxl", "<form>"))));
            return;
        }
        ToolReply existing = toolCall(http, url, token, "get_design", probe);
        if (!existing.error) {
            ToolReply removed = toolCall(http, url, token, "delete_design", probe);
            if (removed.error) {
                throw new AssertionError("could not remove a leftover AldhaniSmokeProbe: " + clip(removed.text));
            }
        }
        ToolReply created = toolCall(http, url, token, "create_design", probe);
        if (created.error) {
            throw new AssertionError("create_design failed: " + clip(created.text));
        }
        AssertionError failure = null;
        try {
            Map<String, Object> element = Json.object(Json.parse(
                    tool(http, url, token, "get_design", probe)));
            expect("AldhaniSmokeProbe", element.get("name"));
            assertUnid("AldhaniSmokeProbe", element.get("unid"));
            String dxl = Json.string(element.get("dxl"));
            if (dxl == null || !dxl.contains("AldhaniSmokeProbe")) {
                throw new AssertionError("created probe DXL does not name AldhaniSmokeProbe");
            }
            Map<String, Object> update = new LinkedHashMap<>(probe);
            update.put("dxl", dxl);
            ToolReply updated = toolCall(http, url, token, "update_design", update);
            if (updated.error) {
                throw new AssertionError("update_design failed: " + clip(updated.text));
            }
            String aclBefore = singletonDxl(http, url, token, "acl");
            expectError(http, url, token, "update_design", Map.of("type", "acl", "dxl", "<acl/>", "nsfPath", NSF));
            expectError(http, url, token, "delete_design", Map.of("type", "dbicon", "nsfPath", NSF));
            expectError(http, url, token, "create_design", Map.of("type", "applicationproperties", "nsfPath", NSF));
            expect(aclBefore, singletonDxl(http, url, token, "acl"));
            ToolReply validated = toolCall(http, url, token, "validate_design", Map.of(
                    "nsfPath", NSF,
                    "elements", List.of(Map.of("type", "forms", "name", "Broken", "dxl", "<form>"))));
            if (validated.error) {
                throw new AssertionError("validate_design failed: " + clip(validated.text));
            }
            Map<String, Object> verdict = Json.object(Json.parse(validated.text));
            expect(Boolean.FALSE, verdict.get("valid"));
            if (((List<?>) verdict.get("errors")).isEmpty()) {
                throw new AssertionError("validate_design accepted broken DXL");
            }
        } catch (AssertionError ex) {
            failure = ex;
        } finally {
            ToolReply deleted = toolCall(http, url, token, "delete_design", probe);
            if (deleted.error && failure == null) {
                failure = new AssertionError("could not delete AldhaniSmokeProbe: " + clip(deleted.text));
            }
            if (!deleted.error) {
                ToolReply gone = toolCall(http, url, token, "get_design", probe);
                if (!gone.error && failure == null) {
                    failure = new AssertionError("AldhaniSmokeProbe is still in the sandbox");
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String singletonDxl(HttpClient http, String url, String token, String type) {
        return Json.string(Json.object(Json.parse(
                tool(http, url, token, "get_design", Map.of("type", type, "nsfPath", NSF)))).get("dxl"));
    }

    private static void expectError(HttpClient http, String url, String token, String name, Map<String, Object> arguments) {
        ToolReply reply = toolCall(http, url, token, name, arguments);
        if (!reply.error) {
            throw new AssertionError(name + " should have failed");
        }
    }

    private static void assertUnid(String label, Object value) {
        String unid = Json.string(value);
        if (unid == null || !unid.matches("[0-9A-Fa-f]{32}")) {
            throw new AssertionError(label + " unid is missing");
        }
    }

    private static Map<String, Object> listedElement(String text, String name) {
        Object raw = Json.object(Json.parse(text)).get("elements");
        if (!(raw instanceof List<?> list)) {
            throw new AssertionError("list has no elements");
        }
        for (Object item : list) {
            Map<String, Object> element = Json.object(item);
            if (name.equals(element.get("name"))) {
                return element;
            }
        }
        throw new AssertionError("list elements missing " + name);
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
        ToolReply reply = toolCall(http, url, token, name, arguments);
        boolean outsideAllowlist = "get_design".equals(name) && "kybertest.nsf".equals(arguments.get("nsfPath"));
        if (outsideAllowlist) {
            if (!reply.error) {
                throw new AssertionError("A database outside the allowlist was readable");
            }
            return reply.text;
        }
        if (reply.error) {
            throw new AssertionError(name + " failed: " + clip(reply.text));
        }
        return reply.text;
    }

    private static ToolReply toolCall(HttpClient http, String url, String token, String name, Map<String, Object> arguments) {
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
        return new ToolReply(text == null ? "" : text, Boolean.TRUE.equals(result.get("isError")));
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
        } else if ("DELETE".equals(method)) {
            request.DELETE();
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

    private record ToolReply(String text, boolean error) {
    }
}
