package se.infoware.domino.aldhani;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AldhaniSelfTest {
    public static void main(String[] args) throws Exception {
        jsonRoundTrip();
        rejectsUnsafePaths();
        listsOnlyTheSandbox();
        refusesDatabasesOutsideTheAllowlist();
        speaksMcp();
        if (args.length > 0 && "live".equals(args[0])) {
            live(Path.of(args.length > 1 ? args[1] : ".env"));
        }
        System.out.println("aldhani self-test ok");
    }

    private static void jsonRoundTrip() {
        String raw = "{\"a\":\"q\\\"u\\n\",\"n\":2,\"ok\":true,\"z\":null,\"list\":[\"å\"]}";
        Map<String, Object> parsed = Json.object(Json.parse(raw));
        expect("q\"u\n", parsed.get("a"));
        expect(2L, parsed.get("n"));
        expect(Boolean.TRUE, parsed.get("ok"));
        expect(null, parsed.get("z"));
        String written = Json.write(parsed);
        expect(parsed, Json.parse(written));
    }

    private static void rejectsUnsafePaths() {
        AldhaniConfig config = sample();
        for (String path : List.of("../kyber.nsf", "/aldhanitest.nsf", "C:/Domino/Data/aldhanitest.nsf")) {
            try {
                config.allows(path);
                throw new AssertionError("accepted " + path);
            } catch (IllegalArgumentException expected) {
                // The path must not be normalized into the allowlist.
            }
        }
        expect(true, config.allows("AldhaniTest.nsf"));
    }

    private static void listsOnlyTheSandbox() {
        DesignTools tools = new DesignTools(sample(), path -> {
            if (!path.startsWith("/api/kyber-v1/databases")) {
                throw new AssertionError(path);
            }
            return """
                    {"databases":[
                      {"nsfPath":"kybertest.nsf","dbTitle":"Kyber"},
                      {"nsfPath":"aldhanitest.nsf","dbTitle":"Aldhani"}
                    ]}""";
        });
        DesignTools.ToolResult result = tools.call("list_databases", Map.of());
        expect(false, result.error());
        Map<String, Object> json = Json.object(Json.parse(result.text()));
        List<?> databases = (List<?>) json.get("databases");
        expect(1, databases.size());
        expect("aldhanitest.nsf", Json.string(Json.object(databases.get(0)).get("nsfPath")));
        expect(List.of(), json.get("missing"));
    }

    private static void refusesDatabasesOutsideTheAllowlist() {
        List<String> calls = new ArrayList<>();
        DesignTools tools = new DesignTools(sample(), path -> {
            calls.add(path);
            return "{}";
        });
        DesignTools.ToolResult result = tools.call("get_design", Map.of(
                "type", "form",
                "name", "Main",
                "nsfPath", "kybertest.nsf"));
        expect(true, result.error());
        expect(0, calls.size());
    }

    private static void speaksMcp() {
        McpProtocol protocol = new McpProtocol(new DesignTools(sample(), path -> {
            expect(true, path.contains("nsfPath=aldhanitest.nsf"));
            expect(true, path.startsWith("/api/kyber-v1/forms"));
            return "{\"Forms\":[\"Main\"]}";
        }));
        McpProtocol.HttpReply init = protocol.handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""");
        expect(200, init.status());
        expect("2025-06-18", Json.string(Json.object(Json.object(Json.parse(init.body())).get("result")).get("protocolVersion")));
        McpProtocol.HttpReply notice = protocol.handle("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""");
        expect(202, notice.status());
        expect(null, notice.body());
        McpProtocol.HttpReply call = protocol.handle("""
                {"jsonrpc":"2.0","id":"a","method":"tools/call","params":{"name":"list_design","arguments":{"type":"form"}}}""");
        Map<String, Object> message = Json.object(Json.parse(call.body()));
        expect("a", message.get("id"));
        Map<String, Object> result = Json.object(message.get("result"));
        expect(Boolean.FALSE, result.get("isError"));
        McpProtocol.HttpReply missing = protocol.handle("""
                {"jsonrpc":"2.0","id":3,"method":"nope"}""");
        Map<String, Object> error = Json.object(Json.object(Json.parse(missing.body())).get("error"));
        expect(-32601, ((Number) error.get("code")).intValue());
    }

    private static void live(Path envFile) throws Exception {
        Map<String, String> env = readEnv(envFile);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("kyberBaseUrl", env.get("DOMINO_REST_API_DATA_URL"));
        config.put("kyberUser", env.get("REST_ADMIN_USER"));
        config.put("kyberPassword", env.get("REST_ADMIN_PASSWORD"));
        config.put("bearerToken", "test-token");
        config.put("allowedNsf", List.of("aldhanitest.nsf"));
        config.put("insecureTls", true);
        DesignTools tools = new DesignTools(AldhaniConfig.parse(Json.write(config)), new KyberClient(AldhaniConfig.parse(Json.write(config))));
        DesignTools.ToolResult result = tools.call("list_databases", Map.of());
        if (result.error()) {
            throw new AssertionError(result.text());
        }
        System.out.println(result.text());
    }

    private static Map<String, String> readEnv(Path file) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int split = trimmed.indexOf('=');
            values.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1).trim());
        }
        return values;
    }

    private static AldhaniConfig sample() {
        return AldhaniConfig.parse("""
                {
                  "kyberBaseUrl": "https://domino.example",
                  "kyberUser": "reader",
                  "kyberPassword": "secret",
                  "bearerToken": "token",
                  "allowedNsf": ["aldhanitest.nsf"]
                }""");
    }

    private static void expect(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
