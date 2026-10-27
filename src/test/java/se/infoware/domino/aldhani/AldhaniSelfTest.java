package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AldhaniSelfTest {
    public static void main(String[] args) throws Exception {
        jsonRoundTrip();
        rejectsUnsafePaths();
        listsOnlyTheSandbox();
        refusesDatabasesOutsideTheAllowlist();
        readsTheKyberCatalog();
        keepsAShortListAndAWholeElement();
        writesStayOffUntilAsked();
        writesThroughKyberWhenAllowed();
        speaksMcp();
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

    private static void readsTheKyberCatalog() {
        List<String> calls = new ArrayList<>();
        DesignTools tools = new DesignTools(sample(), path -> {
            calls.add(path);
            return "{}";
        });
        expect(false, tools.call("list_design", Map.of("type", "page")).error());
        expect("/api/kyber-v1/pages?nsfPath=aldhanitest.nsf", calls.get(calls.size() - 1));
        expect(false, tools.call("get_design", Map.of("type", "acl")).error());
        expect("/api/kyber-v1/acl?nsfPath=aldhanitest.nsf", calls.get(calls.size() - 1));
        expect(true, tools.call("get_design", Map.of("type", "acl", "name", "Main")).error());
        int before = calls.size();
        expect(true, tools.call("list_design", Map.of("type", "defaults")).error());
        expect(true, tools.call("get_design", Map.of("type", "defaults", "name", "form")).error());
        expect(true, tools.call("get_design", Map.of("type", "form")).error());
        expect(true, tools.call("list_design", Map.of("type", "widget")).error());
        expect(before, calls.size());
        expect(30, DesignTools.collections().size());
        expect(7, DesignTools.singletons().size());
        expect(false, sample().allowWrite);
    }

    private static void keepsAShortListAndAWholeElement() {
        RecordingKyber kyber = new RecordingKyber();
        kyber.reply = """
                {"Forms":["A","B","C"],"elements":[{"name":"A"},{"name":"B"},{"name":"C"}]}""";
        DesignTools tools = new DesignTools(sample(), kyber);
        DesignTools.ToolResult limited = tools.call("list_design", Map.of("type", "form", "limit", 1));
        expect(false, limited.error());
        Map<String, Object> json = Json.object(Json.parse(limited.text()));
        expect(List.of("A"), json.get("Forms"));
        expect(1, ((List<?>) json.get("elements")).size());
        expect(Boolean.TRUE, json.get("truncated"));
        expect(1, ((Number) json.get("returned")).intValue());
        expect(3, ((Number) json.get("total")).intValue());
        expect(true, tools.call("list_design", Map.of("type", "form", "limit", 0)).error());

        String dxl = "y".repeat(DesignTools.MAX_CHARS);
        String body = "{\"name\":\"Main\",\"unid\":\"AABBCCDDEEFF00112233445566778899\",\"dxl\":\"" + dxl + "\"}";
        Map<String, Object> clipped = Json.object(Json.parse(DesignTools.clip(body)));
        expect(Boolean.TRUE, clipped.get("truncated"));
        expect(null, clipped.get("dxl"));
        expect("Main", clipped.get("name"));
        expect("AABBCCDDEEFF00112233445566778899", clipped.get("unid"));
        expect(Long.valueOf(body.length()), ((Number) clipped.get("chars")).longValue());

        String raw = "z".repeat(DesignTools.MAX_CHARS + 8);
        String summary = DesignTools.clip(raw);
        expect(false, summary.contains("zzzz"));
        expect(Boolean.TRUE, Json.object(Json.parse(summary)).get("truncated"));
        expect("{}", DesignTools.clip("{}"));
    }

    private static void writesStayOffUntilAsked() {
        RecordingKyber kyber = new RecordingKyber();
        DesignTools tools = new DesignTools(sample(), kyber);
        expect(false, tools.writesEnabled());
        expect(4, tools.names().size());
        DesignTools.ToolResult refused = tools.call("create_design", Map.of("type", "form", "name", "Main"));
        expect(true, refused.error());
        expect(0, kyber.calls.size());
        McpProtocol protocol = new McpProtocol(tools);
        McpProtocol.HttpReply listed = protocol.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""");
        List<?> definitions = (List<?>) Json.object(Json.object(Json.parse(listed.body())).get("result")).get("tools");
        expect(4, definitions.size());
        expect(Boolean.TRUE, Json.object(Json.object(definitions.get(1)).get("annotations")).get("readOnlyHint"));
        McpProtocol.HttpReply call = protocol.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"create_design","arguments":{"type":"form","name":"Main"}}}""");
        expect(Boolean.TRUE, Json.object(Json.object(Json.parse(call.body())).get("result")).get("isError"));
        expect(0, kyber.calls.size());
    }

    private static void writesThroughKyberWhenAllowed() {
        RecordingKyber kyber = new RecordingKyber();
        kyber.reply = "{\"ok\":true}";
        DesignTools tools = new DesignTools(writable(), kyber);
        expect(true, tools.writesEnabled());
        expect(8, tools.names().size());

        expect(false, tools.call("create_design", Map.of("type", "form", "name", "Main")).error());
        expect("POST /api/kyber-v1/forms?nsfPath=aldhanitest.nsf&name=Main", kyber.calls.get(kyber.calls.size() - 1));
        expect("", kyber.body);
        expect("text/xml; charset=utf-8", kyber.contentType);

        expect(false, tools.call("create_design", Map.of(
                "type", "agent", "name", "Nightly", "kind", "java", "dxl", "<agent/>")).error());
        expect(true, kyber.calls.get(kyber.calls.size() - 1).contains("kind=java"));
        expect("<agent/>", kyber.body);

        expect(false, tools.call("update_design", Map.of("type", "form", "name", "Main", "dxl", "<form/>")).error());
        expect("PUT /api/kyber-v1/forms/Main?nsfPath=aldhanitest.nsf", kyber.calls.get(kyber.calls.size() - 1));
        expect("<form/>", kyber.body);

        int before = kyber.calls.size();
        expect(true, tools.call("update_design", Map.of("type", "acl", "dxl", "<acl/>")).error());
        expect(true, tools.call("create_design", Map.of("type", "applicationproperties")).error());
        expect(true, tools.call("delete_design", Map.of("type", "dbicon")).error());
        expect(before, kyber.calls.size());

        expect(false, tools.call("update_design", Map.of("type", "dbicon", "dxl", "<imageresource/>")).error());
        expect("PUT /api/kyber-v1/dbicon?nsfPath=aldhanitest.nsf", kyber.calls.get(kyber.calls.size() - 1));

        expect(false, tools.call("delete_design", Map.of("type", "form", "name", "Main")).error());
        expect("DELETE /api/kyber-v1/forms/Main?nsfPath=aldhanitest.nsf", kyber.calls.get(kyber.calls.size() - 1));

        expect(false, tools.call("validate_design", Map.of("elements", List.of(
                Map.of("type", "forms", "name", "Main", "dxl", "<form/>"),
                Map.of("type", "sharedaction", "name", "Save", "dxl", "<sharedactions/>")))).error());
        expect("POST /api/kyber-v1/validate?nsfPath=aldhanitest.nsf", kyber.calls.get(kyber.calls.size() - 1));
        expect("application/json; charset=utf-8", kyber.contentType);
        expect(true, kyber.body.contains("\"type\":\"form\""));
        expect(true, kyber.body.contains("\"type\":\"sharedactions\""));

        McpProtocol protocol = new McpProtocol(tools);
        McpProtocol.HttpReply listed = protocol.handle("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""");
        List<?> definitions = (List<?>) Json.object(Json.object(Json.parse(listed.body())).get("result")).get("tools");
        expect(8, definitions.size());
        Map<String, Object> delete = Json.object(definitions.get(6));
        expect("delete_design", delete.get("name"));
        expect(Boolean.TRUE, Json.object(delete.get("annotations")).get("destructiveHint"));
        expect(Boolean.TRUE, Json.object(Json.object(definitions.get(7)).get("annotations")).get("readOnlyHint"));
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

    private static AldhaniConfig writable() {
        return AldhaniConfig.parse("""
                {
                  "kyberBaseUrl": "https://domino.example",
                  "kyberUser": "reader",
                  "kyberPassword": "secret",
                  "bearerToken": "token",
                  "allowedNsf": ["aldhanitest.nsf"],
                  "allowWrite": true
                }""");
    }

    private static final class RecordingKyber implements KyberExchange {
        final List<String> calls = new ArrayList<>();
        String body;
        String contentType;
        String reply = "{}";

        public String get(String pathAndQuery) {
            calls.add("GET " + pathAndQuery);
            return reply;
        }

        public String exchange(String method, String pathAndQuery, String body, String contentType) {
            calls.add(method + " " + pathAndQuery);
            this.body = body;
            this.contentType = contentType;
            return reply;
        }
    }

    private static void expect(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
