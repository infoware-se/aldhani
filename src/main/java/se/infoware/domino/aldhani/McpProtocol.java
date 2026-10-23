package se.infoware.domino.aldhani;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McpProtocol {
    static final String VERSION = "0.1.1";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18");

    private final DesignTools tools;

    McpProtocol(DesignTools tools) {
        this.tools = tools;
    }

    /** A null body means HTTP 202 with no content. */
    HttpReply handle(String body) {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IllegalArgumentException ex) {
            return HttpReply.json(400, error(null, -32700, "Parse error"));
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return HttpReply.json(400, error(null, -32600, "Expected a JSON-RPC object"));
        }
        Map<String, Object> message = Json.object(parsed);
        String method = Json.string(message.get("method"));
        Object id = message.get("id");
        boolean notification = !message.containsKey("id");
        if (method == null || method.isBlank()) {
            return HttpReply.json(400, error(id, -32600, "method is required"));
        }
        try {
            Object result = dispatch(method, message.get("params"));
            if (notification) {
                return HttpReply.accepted();
            }
            return HttpReply.json(200, result(id, result));
        } catch (UnknownMethod ex) {
            if (notification) {
                return HttpReply.accepted();
            }
            return HttpReply.json(200, error(id, -32601, ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            if (notification) {
                return HttpReply.accepted();
            }
            return HttpReply.json(200, error(id, -32602, ex.getMessage()));
        }
    }

    private Object dispatch(String method, Object params) {
        Map<String, Object> object = params instanceof Map<?, ?> ? Json.object(params) : Map.of();
        return switch (method) {
            case "initialize" -> initialize(object);
            case "notifications/initialized", "initialized" -> Map.of();
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", toolDefinitions());
            case "tools/call" -> callTool(object);
            default -> throw new UnknownMethod("Unknown method " + method);
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = Json.string(params.get("protocolVersion"));
        String version = requested != null && PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : "2025-03-26";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "aldhani", "version", VERSION));
        return result;
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = Json.string(params.get("name"));
        if (name == null || !DesignTools.names().contains(name)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("content", List.of(Map.of("type", "text", "text", "Unknown tool " + name)));
            missing.put("isError", true);
            return missing;
        }
        Object raw = params.get("arguments");
        Map<String, Object> arguments = raw instanceof Map<?, ?> ? Json.object(raw) : Map.of();
        DesignTools.ToolResult outcome = tools.call(name, arguments);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", outcome.text())));
        result.put("isError", outcome.error());
        return result;
    }

    private static List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool(
                        "list_databases",
                        "Lists the Kyber-enabled databases Aldhani is allowed to see. "
                                + "aldhanitest.nsf is the sandbox. Other databases are omitted.",
                        Map.of()),
                tool(
                        "list_design",
                        "Lists design elements of one type in an allowed NSF. "
                                + "Use this before reading a single element.",
                        Map.of(
                                "type", prop("string", "form, view, agent, or xpage"),
                                "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."))),
                tool(
                        "get_design",
                        "Returns one design element as Kyber JSON, including its DXL.",
                        Map.of(
                                "type", prop("string", "form, view, agent, or xpage"),
                                "name", prop("string", "Design element name"),
                                "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."))),
                tool(
                        "export_design",
                        "Exports the whole NSF design as DXL. Large. Prefer list_design and get_design.",
                        Map.of("nsfPath", prop("string", "NSF path. Defaults to the only allowed database."))));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if ("list_design".equals(name) || "get_design".equals(name)) {
            schema.put("required", "get_design".equals(name) ? List.of("type", "name") : List.of("type"));
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        return tool;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> result(Object id, Object value) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", value);
        return message;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("error", Map.of("code", code, "message", message));
        return envelope;
    }

    private static final class UnknownMethod extends IllegalArgumentException {
        UnknownMethod(String message) {
            super(message);
        }
    }

    record HttpReply(int status, String body) {
        static HttpReply json(int status, Object body) {
            return new HttpReply(status, Json.write(body));
        }

        static HttpReply accepted() {
            return new HttpReply(202, null);
        }
    }
}
