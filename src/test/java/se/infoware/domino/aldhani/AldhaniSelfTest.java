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
        speaksTheCurrentProtocol();
        cancelsARunningCall();
        issuesAnOauthBearer();
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

        String paged = DesignTools.page("x".repeat(DesignTools.PAGE + 3), Map.of("part", 1));
        Map<String, Object> page = Json.object(Json.parse(paged));
        expect(1, ((Number) page.get("part")).intValue());
        expect(2, ((Number) page.get("parts")).intValue());
        expect("xxx", page.get("text"));
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
        Map<String, Object> initialized = Json.object(Json.object(Json.parse(init.body())).get("result"));
        expect("2025-06-18", initialized.get("protocolVersion"));
        expect(true, Json.string(initialized.get("instructions")).contains("Kyber"));
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
        expect("Main", ((List<?>) Json.object(result.get("structuredContent")).get("Forms")).get(0));
        McpProtocol.HttpReply missing = protocol.handle("""
                {"jsonrpc":"2.0","id":3,"method":"nope"}""");
        Map<String, Object> error = Json.object(Json.object(Json.parse(missing.body())).get("error"));
        expect(-32601, ((Number) error.get("code")).intValue());
        McpProtocol.HttpReply resource = protocol.handle("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"aldhani://aldhanitest.nsf/form/Main"}}""");
        String resourceText = Json.string(Json.object(((List<?>) Json.object(Json.object(Json.parse(resource.body())).get("result")).get("contents")).get(0)).get("text"));
        expect(true, resourceText.contains("Main"));
        McpProtocol.HttpReply completion = protocol.handle("""
                {"jsonrpc":"2.0","id":5,"method":"completion/complete","params":{"argument":{"name":"type","value":"fo"}}}""");
        List<?> values = (List<?>) Json.object(Json.object(Json.object(Json.parse(completion.body())).get("result")).get("completion")).get("values");
        expect(true, values.contains("forms"));
        expect(true, values.contains("folders"));
    }

    private static void speaksTheCurrentProtocol() {
        expect(true, McpProtocol.supports("2025-11-25"));
        expect(true, HttpGuard.protocolHeaderOk(null));
        expect(false, HttpGuard.protocolHeaderOk("1999-01-01"));
        expect(true, HttpGuard.originAllowed(null, "dantooine.oninfoware.local"));
        expect(true, HttpGuard.originAllowed("https://dantooine.oninfoware.local", "dantooine.oninfoware.local"));
        expect(false, HttpGuard.originAllowed("https://evil.example", "dantooine.oninfoware.local"));

        McpProtocol protocol = new McpProtocol(new DesignTools(sample(), path -> "{\"databases\":[]}"));
        McpProtocol.HttpReply latest = protocol.handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""");
        Map<String, Object> initialized = Json.object(Json.object(Json.parse(latest.body())).get("result"));
        expect("2025-11-25", initialized.get("protocolVersion"));
        expect(true, latest.openSession());
        Map<String, Object> capabilities = Json.object(initialized.get("capabilities"));
        expect(true, capabilities.containsKey("prompts"));
        expect(true, capabilities.containsKey("logging"));
        McpProtocol.HttpReply fallback = protocol.handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""");
        expect("2025-11-25", Json.object(Json.object(Json.parse(fallback.body())).get("result")).get("protocolVersion"));

        McpProtocol.HttpReply tools = protocol.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");
        List<?> definitions = (List<?>) Json.object(Json.object(Json.parse(tools.body())).get("result")).get("tools");
        expect(true, Json.object(definitions.get(0)).containsKey("outputSchema"));
        expect(false, Json.object(definitions.get(3)).containsKey("outputSchema"));

        McpProtocol.HttpReply prompts = protocol.handle("""
                {"jsonrpc":"2.0","id":3,"method":"prompts/list"}""");
        List<?> promptList = (List<?>) Json.object(Json.object(Json.parse(prompts.body())).get("result")).get("prompts");
        expect("review_design", Json.object(promptList.get(0)).get("name"));
        McpProtocol.HttpReply prompt = protocol.handle("""
                {"jsonrpc":"2.0","id":4,"method":"prompts/get","params":{"name":"explain_design","arguments":{"type":"form","name":"Main"}}}""");
        String text = Json.string(Json.object(Json.object(((List<?>) Json.object(Json.object(Json.parse(prompt.body())).get("result")).get("messages")).get(0)).get("content")).get("text"));
        expect(true, text.contains("Main"));
        McpProtocol.HttpReply badLevel = protocol.handle("""
                {"jsonrpc":"2.0","id":5,"method":"logging/setLevel","params":{"level":"verbose"}}""");
        expect(-32602, ((Number) Json.object(Json.object(Json.parse(badLevel.body())).get("error")).get("code")).intValue());
        protocol.handle("""
                {"jsonrpc":"2.0","id":6,"method":"logging/setLevel","params":{"level":"error"}}""");
        McpProtocol.HttpReply quiet = protocol.handle("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"list_databases","arguments":{},"_meta":{"progressToken":"p"}}}""");
        expect(2, quiet.notifications().size());
        expect("notifications/progress", quiet.notifications().get(0).get("method"));
        protocol.handle("""
                {"jsonrpc":"2.0","id":8,"method":"logging/setLevel","params":{"level":"debug"}}""");
        McpProtocol.HttpReply loud = protocol.handle("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"list_databases","arguments":{}}}""");
        expect(true, loud.notifications().size() >= 2);
        expect("notifications/message", loud.notifications().get(0).get("method"));
        McpProtocol.HttpReply names = protocol.handle("""
                {"jsonrpc":"2.0","id":10,"method":"completion/complete","params":{"argument":{"name":"nsfPath","value":"ald"}}}""");
        List<?> nsf = (List<?>) Json.object(Json.object(Json.object(Json.parse(names.body())).get("result")).get("completion")).get("values");
        expect(true, nsf.contains("aldhanitest.nsf"));
    }

    private static void cancelsARunningCall() throws Exception {
        McpProtocol protocol = new McpProtocol(new DesignTools(sample(), path -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            return "{}";
        }));
        java.util.concurrent.atomic.AtomicReference<McpProtocol.HttpReply> reply = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> reply.set(protocol.handle("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"list_databases","arguments":{}}}""")));
        worker.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!protocol.running(9) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        expect(true, protocol.running(9));
        protocol.handle("""
                {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":9}}""");
        worker.join(5_000);
        expect(false, worker.isAlive());
        expect(true, reply.get().suppressed());
        expect(null, reply.get().body());
    }

    private static void issuesAnOauthBearer() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keys = generator.generateKeyPair();
        String verifier = "a".repeat(50);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        OauthStore store = OauthStore.memory(keys);
        expect(false, OauthStore.httpsOrLoopback("http://evil.example/cb"));
        String location = store.authorizationRedirect(
                "aldhani", "http://127.0.0.1/cb", challenge, "S256", "s", "CN=Reader/O=Org", "token", "token");
        String code = location.substring(location.indexOf("code=") + 5, location.indexOf("&state"));
        OauthStore.Access access = store.exchangeCode("aldhani", "http://127.0.0.1/cb", code, verifier,
                "https://domino.example/oauth", "https://domino.example");
        expect(true, store.accessValid(access.accessToken()));
        Map<String, Object> claims = Jwt.verify(keys.getPublic(), access.accessToken(), java.time.Instant.now().getEpochSecond());
        expect(true, Json.string(claims.get("scope")).contains("aldhani"));
        expect(true, Json.string(claims.get("scope")).contains("Domino.user.all"));
        try {
            store.exchangeCode("aldhani", "http://127.0.0.1/cb", code, verifier, "https://domino.example/oauth", "https://domino.example");
            throw new AssertionError("authorization code was reused");
        } catch (IllegalArgumentException expected) {
            expect("invalid_grant", expected.getMessage());
        }
        Map<String, Object> resource = OauthStore.resource("https://domino.example");
        expect("https://domino.example/mcp", resource.get("resource"));
        expect(List.of("https://domino.example/oauth"), resource.get("authorization_servers"));

        OauthStore.Access renewed = store.refresh(access.refreshToken(), "https://domino.example/oauth", "https://domino.example");
        expect(true, store.accessValid(renewed.accessToken()));
        try {
            store.refresh(access.refreshToken(), "https://domino.example/oauth", "https://domino.example");
            throw new AssertionError("refresh token was reused");
        } catch (IllegalArgumentException expected) {
            expect("invalid_grant", expected.getMessage());
        }
        String damaged = access.accessToken().substring(0, access.accessToken().length() - 2) + "aa";
        expect(false, store.accessValid(damaged));

        String registered = store.register(List.of("https://client.example/callback"));
        expect(true, registered.length() > 10);
        try {
            store.register(List.of("http://evil.example/cb"));
            throw new AssertionError("plain http redirect was registered");
        } catch (IllegalArgumentException expected) {
            expect("redirect_uri is not allowed", expected.getMessage());
        }
        try {
            store.authorizationRedirect("aldhani", "https://evil.example/cb", challenge, "S256", "s", "CN=Reader/O=Org", "token", "token");
            throw new AssertionError("built-in client accepted an https redirect");
        } catch (IllegalArgumentException expected) {
            expect("redirect_uri is not allowed", expected.getMessage());
        }
        String cursor = store.authorizationRedirect(
                "aldhani", "cursor://anysphere.cursor-mcp/oauth/callback", challenge, "S256", "s",
                "CN=Reader/O=Org", "token", "token");
        expect(true, cursor.startsWith("cursor://"));
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
