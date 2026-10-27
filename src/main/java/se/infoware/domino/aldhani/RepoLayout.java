package se.infoware.domino.aldhani;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Package Explorer paths used by Kyber {@code tools/export-to-repo.ps1}.
 * A file is {@code <name>-<noteId>.dxl} under {@code domino/<nsfPath>-<replicaId>/}.
 */
final class RepoLayout {
    static final String LAYOUT = "package-explorer";

    private static final Map<String, String> FOLDERS = new LinkedHashMap<>();

    static {
        FOLDERS.put("acl", "AppProperties");
        FOLDERS.put("about", "Resources");
        FOLDERS.put("using", "Resources");
        FOLDERS.put("sharedactions", "Code/actions");
        FOLDERS.put("databasescript", "Code");
        FOLDERS.put("image", "Resources/Images");
        FOLDERS.put("dbicon", "Resources/Images");
        FOLDERS.put("fileresource", "Resources/Files");
        FOLDERS.put("webcontent", "WebContent");
        FOLDERS.put("stylesheet", "Resources/StyleSheets");
        FOLDERS.put("theme", "Resources/Themes");
        FOLDERS.put("scriptlibrary", "Code/ScriptLibraries");
        FOLDERS.put("sharedfield", "SharedElements/Fields");
        FOLDERS.put("sharedcolumn", "SharedElements/Columns");
        FOLDERS.put("subform", "SharedElements/Subforms");
        FOLDERS.put("form", "Forms");
        FOLDERS.put("page", "Pages");
        FOLDERS.put("outline", "SharedElements/Outlines");
        FOLDERS.put("navigator", "SharedElements/Navigators");
        FOLDERS.put("frameset", "Framesets");
        FOLDERS.put("view", "Views");
        FOLDERS.put("folder", "Folders");
        FOLDERS.put("agent", "Code/Agents");
        FOLDERS.put("webservice", "Code/WebServices");
        FOLDERS.put("webserviceconsumer", "Code/WebServiceConsumer");
        FOLDERS.put("dataconnection", "Resources/DataConnections");
        FOLDERS.put("jar", "Code/Jars");
        FOLDERS.put("applet", "Resources/Applets");
        FOLDERS.put("javasource", "Code/Java");
        FOLDERS.put("xpagesresource", ".");
        FOLDERS.put("designnote", "_designnotes");
        FOLDERS.put("customcontrol", "CustomControls");
        FOLDERS.put("xpage", "XPages");
        FOLDERS.put("wiringproperties", "CompositeApplications");
        FOLDERS.put("compositeapplication", "CompositeApplications");
        FOLDERS.put("compositecomponent", "CompositeApplications");
        FOLDERS.put("applicationproperties", "AppProperties");
        FOLDERS.put("xspproperties", "AppProperties");
    }

    private RepoLayout() {
    }

    static String databaseFolder(String nsfPath, String replicaId) {
        return nsfFolder(nsfPath, replicaId);
    }

    static String describe(Map<String, Object> arguments) {
        String type = Json.string(arguments.get("type"));
        String nsfPath = Json.string(arguments.get("nsfPath"));
        String replicaId = Json.string(arguments.get("replicaId"));
        boolean hasNsf = nsfPath != null && !nsfPath.isBlank();
        boolean hasReplica = replicaId != null && !replicaId.isBlank();
        if (hasNsf != hasReplica) {
            throw new IllegalArgumentException("nsfPath and replicaId are set together");
        }
        String root = hasNsf ? nsfFolder(nsfPath, replicaId) : null;
        if (type == null || type.isBlank()) {
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("layout", LAYOUT);
            catalog.put("rootPattern", "domino/<nsfPath>-<replicaId>");
            catalog.put("filePattern", "<name>-<noteId>.dxl");
            if (root != null) {
                catalog.put("root", root);
            }
            catalog.put("folders", FOLDERS);
            return Json.write(catalog);
        }
        String canonical = canonical(type);
        String name = Json.string(arguments.get("name"));
        String noteId = Json.string(arguments.get("noteId"));
        String dir = directory(canonical, name);
        String file = file(canonical, name, noteId);
        String relative = ".".equals(dir) ? file : dir + "/" + file;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("layout", LAYOUT);
        result.put("type", canonical);
        if (root != null) {
            result.put("root", root);
        }
        result.put("dir", dir);
        result.put("file", file);
        result.put("path", root == null ? relative : root + "/" + relative);
        return Json.write(result);
    }

    private static String canonical(String type) {
        String key = type.trim().toLowerCase(Locale.ROOT);
        if ("sharedaction".equals(key)) {
            key = "sharedactions";
        } else if ("wiringproperty".equals(key)) {
            key = "wiringproperties";
        }
        if (!FOLDERS.containsKey(key) && key.endsWith("s")) {
            String singular = key.substring(0, key.length() - 1);
            if (FOLDERS.containsKey(singular)) {
                key = singular;
            }
        }
        if (!FOLDERS.containsKey(key)) {
            throw new IllegalArgumentException("Unknown Kyber design type " + type);
        }
        return key;
    }

    private static String directory(String type, String name) {
        if ("javasource".equals(type)) {
            String parent = parent(name);
            return parent.isEmpty() ? "Code/Java" : "Code/Java/" + parent;
        }
        if ("xpagesresource".equals(type) || "xspproperties".equals(type)) {
            String parent = parent(name);
            if (parent.isEmpty()) {
                return "xspproperties".equals(type) ? "AppProperties" : ".";
            }
            if ("WEB-INF".equals(parent) || parent.startsWith("WEB-INF/")) {
                return "WebContent/" + parent;
            }
            return parent;
        }
        return FOLDERS.get(type);
    }

    private static String file(String type, String name, String noteId) {
        String leaf;
        if ("databasescript".equals(type)) {
            leaf = "dbscript.lsdb";
        } else if ("javasource".equals(type) || "xpagesresource".equals(type) || "xspproperties".equals(type)) {
            leaf = leaf(name);
            if (leaf.isEmpty()) {
                leaf = type;
            }
        } else if (name != null && !name.isBlank()) {
            leaf = name;
        } else if ("dbicon".equals(type)) {
            leaf = "$DBIcon";
        } else {
            leaf = type;
        }
        String base = sanitize(leaf);
        if (noteId != null && !noteId.isBlank()) {
            base = base + "-" + sanitize(noteId);
        }
        return base + ".dxl";
    }

    private static String nsfFolder(String nsfPath, String replicaId) {
        String path = nsfPath.replace('\\', '/').trim();
        int bang = path.lastIndexOf("!!");
        if (bang >= 0) {
            path = path.substring(bang + 2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty() || path.contains("..") || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("nsfPath is not allowed");
        }
        String[] parts = path.split("/");
        StringBuilder folder = new StringBuilder("domino");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                throw new IllegalArgumentException("nsfPath is not allowed");
            }
            String segment = sanitize(parts[i]);
            if (i == parts.length - 1) {
                segment = segment + "-" + sanitize(replicaId);
            }
            folder.append('/').append(segment);
        }
        return folder.toString();
    }

    private static String parent(String name) {
        String leaf = leaf(name);
        if (name == null || leaf.equals(name.replace('\\', '/'))) {
            return "";
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String parent = normalized.substring(0, slash);
        String[] parts = parent.split("/");
        StringBuilder built = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.contains("..")) {
                throw new IllegalArgumentException("name is not allowed");
            }
            if (built.length() > 0) {
                built.append('/');
            }
            built.append(sanitize(part));
        }
        return built.toString();
    }

    private static String leaf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "_unnamed";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.isEmpty() ? "_unnamed" : cleaned;
    }
}
