package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesignTools {
    static final int MAX_CHARS = 100_000;

    private static final Map<String, String> TYPES = Map.of(
            "form", "forms",
            "forms", "forms",
            "view", "views",
            "views", "views",
            "agent", "agents",
            "agents", "agents",
            "xpage", "xpages",
            "xpages", "xpages");

    private final AldhaniConfig config;
    private final KyberGet kyber;

    DesignTools(AldhaniConfig config, KyberGet kyber) {
        this.config = config;
        this.kyber = kyber;
    }

    ToolResult call(String name, Map<String, Object> arguments) {
        try {
            String text = switch (name) {
                case "list_databases" -> listDatabases();
                case "list_design" -> listDesign(arguments);
                case "get_design" -> getDesign(arguments);
                case "export_design" -> exportDesign(arguments);
                default -> throw new IllegalArgumentException("Unknown tool " + name);
            };
            return new ToolResult(clip(text), false);
        } catch (IllegalArgumentException ex) {
            return new ToolResult(ex.getMessage(), true);
        } catch (KyberException ex) {
            String detail = ex.status == 0
                    ? ex.getMessage()
                    : "Kyber returned HTTP " + ex.status + ": " + ex.getMessage();
            return new ToolResult(detail, true);
        }
    }

    private String listDatabases() {
        String body = kyber.get("/api/kyber-v1/databases?" + KyberClient.query("fileType", "database"));
        Map<String, Object> json = Json.object(Json.parse(body));
        Object raw = json.get("databases");
        List<Object> present = new ArrayList<>();
        if (raw instanceof List<?> databases) {
            for (Object item : databases) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String path = Json.string(map.get("nsfPath"));
                if (path != null && config.allows(path)) {
                    present.add(item);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String allowed : config.allowedNsf) {
            boolean found = false;
            for (Object item : present) {
                String path = Json.string(Json.object(item).get("nsfPath"));
                if (path != null && config.allows(path) && allowed.equalsIgnoreCase(AldhaniConfig.normalizeNsf(path))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(allowed);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowedNsf", config.allowedNsf);
        result.put("databases", present);
        result.put("missing", missing);
        return Json.write(result);
    }

    private String listDesign(Map<String, Object> arguments) {
        String collection = collection(arguments);
        String nsf = nsf(arguments);
        return kyber.get("/api/kyber-v1/" + collection + "?" + KyberClient.query("nsfPath", nsf));
    }

    private String getDesign(Map<String, Object> arguments) {
        String collection = collection(arguments);
        String name = required(arguments, "name");
        String nsf = nsf(arguments);
        return kyber.get("/api/kyber-v1/" + collection + "/" + KyberClient.pathSegment(name)
                + "?" + KyberClient.query("nsfPath", nsf));
    }

    private String exportDesign(Map<String, Object> arguments) {
        String nsf = nsf(arguments);
        return kyber.get("/api/kyber-v1/export?" + KyberClient.query("nsfPath", nsf));
    }

    private String collection(Map<String, Object> arguments) {
        String type = required(arguments, "type");
        String collection = TYPES.get(AldhaniConfig.lower(type));
        if (collection == null) {
            throw new IllegalArgumentException("type must be form, view, agent, or xpage");
        }
        return collection;
    }

    private String nsf(Map<String, Object> arguments) {
        String given = Json.string(arguments.get("nsfPath"));
        String path = (given == null || given.isBlank()) ? config.onlyNsf() : given;
        if (path == null) {
            throw new IllegalArgumentException("nsfPath is required");
        }
        String normalized = AldhaniConfig.normalizeNsf(path);
        if (!config.allows(normalized)) {
            throw new IllegalArgumentException("NSF is not in the Aldhani allowlist: " + normalized);
        }
        return normalized;
    }

    private static String required(Map<String, Object> arguments, String key) {
        String value = Json.string(arguments.get(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS) + "\n… truncated";
    }

    static Set<String> names() {
        return Set.of("list_databases", "list_design", "get_design", "export_design");
    }

    record ToolResult(String text, boolean error) {
    }
}
