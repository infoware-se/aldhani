package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesignTools {
    static final int MAX_CHARS = 100_000;

    private static final Map<String, String> COLLECTIONS = new LinkedHashMap<>();
    private static final List<String> COLLECTION_NAMES;
    private static final List<String> SINGLETONS = List.of(
            "about",
            "acl",
            "applicationproperties",
            "databasescript",
            "dbicon",
            "using",
            "xspproperties");
    private static final Set<String> FILE_TYPES = Set.of(
            "database", "notes_database", "1247",
            "template", "1248",
            "replica_candidate", "1245",
            "template_candidate", "1246");

    static {
        alias("agents", "agent");
        alias("applets", "applet");
        alias("compositeapplications", "compositeapplication");
        alias("compositecomponents", "compositecomponent");
        alias("customcontrols", "customcontrol");
        alias("dataconnections", "dataconnection");
        alias("fileresources", "fileresource");
        alias("folders", "folder");
        alias("forms", "form");
        alias("framesets", "frameset");
        alias("images", "image");
        alias("jars", "jar");
        alias("javasources", "javasource");
        alias("navigators", "navigator");
        alias("outlines", "outline");
        alias("pages", "page");
        alias("scriptlibraries", "scriptlibrary");
        alias("sharedactions", "sharedaction");
        alias("sharedcolumns", "sharedcolumn");
        alias("sharedfields", "sharedfield");
        alias("stylesheets", "stylesheet");
        alias("subforms", "subform");
        alias("themes", "theme");
        alias("views", "view");
        alias("webcontent", "webcontent");
        alias("webserviceconsumers", "webserviceconsumer");
        alias("webservices", "webservice");
        alias("wiringproperties", "wiringproperty");
        alias("xpages", "xpage");
        alias("xpagesresources", "xpagesresource");
        COLLECTION_NAMES = List.copyOf(new java.util.LinkedHashSet<>(COLLECTIONS.values()));
    }

    static List<String> collections() {
        return COLLECTION_NAMES;
    }

    static List<String> singletons() {
        return SINGLETONS;
    }

    private final AldhaniConfig config;
    private final KyberGet kyber;

    DesignTools(AldhaniConfig config, KyberGet kyber) {
        this.config = config;
        this.kyber = kyber;
    }

    ToolResult call(String name, Map<String, Object> arguments) {
        try {
            String text = switch (name) {
                case "list_databases" -> listDatabases(arguments);
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

    private String listDatabases(Map<String, Object> arguments) {
        String fileType = Json.string(arguments.get("fileType"));
        if (fileType == null || fileType.isBlank()) {
            fileType = "database";
        }
        if (!FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "fileType must be database, template, replica_candidate, or template_candidate");
        }
        String body = kyber.get("/api/kyber-v1/databases?" + KyberClient.query("fileType", fileType));
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
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " is one design note; use get_design");
        }
        if (kind.defaults) {
            return kyber.get("/api/kyber-v1/defaults");
        }
        return kyber.get("/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf(arguments)));
    }

    private String getDesign(Map<String, Object> arguments) {
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            if (present(arguments, "name")) {
                throw new IllegalArgumentException(kind.path + " has no element name");
            }
            return kyber.get("/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf(arguments)));
        }
        String name = required(arguments, "name");
        if (kind.defaults) {
            String path = "/api/kyber-v1/defaults/" + KyberClient.pathSegment(name);
            if (present(arguments, "kind")) {
                path += "?" + KyberClient.query("kind", Json.string(arguments.get("kind")));
            }
            return kyber.get(path);
        }
        return kyber.get("/api/kyber-v1/" + kind.path + "/" + KyberClient.pathSegment(name)
                + "?" + KyberClient.query("nsfPath", nsf(arguments)));
    }

    private String exportDesign(Map<String, Object> arguments) {
        String nsf = nsf(arguments);
        return kyber.get("/api/kyber-v1/export?" + KyberClient.query("nsfPath", nsf));
    }

    private static void alias(String plural, String singular) {
        COLLECTIONS.put(plural, plural);
        if (!plural.equals(singular)) {
            COLLECTIONS.put(singular, plural);
        }
    }

    private static DesignKind resolve(String type) {
        String key = AldhaniConfig.lower(type);
        if ("defaults".equals(key)) {
            return new DesignKind("defaults", false, true);
        }
        if (SINGLETONS.contains(key)) {
            return new DesignKind(key, true, false);
        }
        String collection = COLLECTIONS.get(key);
        if (collection == null) {
            throw new IllegalArgumentException("Unknown Kyber design type " + type);
        }
        return new DesignKind(collection, false, false);
    }

    private static boolean present(Map<String, Object> arguments, String key) {
        String value = Json.string(arguments.get(key));
        return value != null && !value.isBlank();
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

    private record DesignKind(String path, boolean singleton, boolean defaults) {
    }
}
