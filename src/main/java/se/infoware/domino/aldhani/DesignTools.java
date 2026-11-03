package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesignTools {
    static final int MAX_CHARS = 100_000;
    static final int PAGE = 40_000;

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
    private static final Set<String> CREATABLE_NOTES = Set.of(
            "about", "using", "databasescript", "xspproperties");
    private static final Set<String> UPDATABLE_NOTES = Set.of(
            "about", "using", "databasescript", "xspproperties", "applicationproperties", "dbicon");
    private static final Set<String> DELETABLE_NOTES = CREATABLE_NOTES;
    private static final Set<String> KINDED = Set.of("agents", "scriptlibraries", "javasources");
    private static final Set<String> READ_NAMES = Set.of(
            "list_databases", "list_design", "get_design", "export_design", "inventory_design",
            "search_design", "list_templates", "preview_starter", "get_content",
            "repo_path", "repo_status", "sync_repo", "debug_kyber");
    private static final Set<String> WRITE_NAMES = Set.of(
            "list_databases", "list_design", "get_design", "export_design", "inventory_design",
            "search_design", "list_templates", "preview_starter", "get_content",
            "repo_path", "repo_status", "sync_repo", "debug_kyber",
            "create_database", "create_database_from_template", "set_db_title", "set_kyber_enabled",
            "create_design", "update_design", "patch_design",
            "copy_design", "rename_design", "update_content", "recompile_lotusscript",
            "inherit_design", "delete_design", "validate_design");
    private static final int SKIPPED_DETAIL_LIMIT = 50;
    private static final Set<String> SEARCH_MATCH = Set.of("name", "dxl", "both");
    private static final Set<String> CONTENT_TYPES = Set.of("image", "fileresource", "jar", "dbicon");
    private static final String XML = "text/xml; charset=utf-8";
    private static final String JSON = "application/json; charset=utf-8";

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
        alias("designnotes", "designnote");
        COLLECTION_NAMES = List.copyOf(new java.util.LinkedHashSet<>(COLLECTIONS.values()));
    }

    static List<String> collections() {
        return COLLECTION_NAMES;
    }

    static List<String> singletons() {
        return SINGLETONS;
    }

    List<String> allowedNsf() {
        return config.allowedNsf;
    }

    AldhaniConfig config() {
        return config;
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
                case "inventory_design" -> inventoryDesign(arguments);
                case "search_design" -> searchDesign(arguments);
                case "list_templates" -> listTemplates(arguments);
                case "preview_starter" -> previewStarter(arguments);
                case "get_content" -> getContent(arguments);
                case "repo_path" -> RepoLayout.describe(arguments);
                case "repo_status" -> Json.write(RepoSync.status(config));
                case "sync_repo" -> syncRepo(arguments);
                case "debug_kyber" -> debugKyber();
                case "create_database" -> createDatabase(arguments);
                case "create_database_from_template" -> createDatabaseFromTemplate(arguments);
                case "set_db_title" -> setDbTitle(arguments);
                case "set_kyber_enabled" -> setKyberEnabled(arguments);
                case "create_design" -> createDesign(arguments);
                case "update_design" -> updateDesign(arguments);
                case "patch_design" -> patchDesign(arguments);
                case "copy_design" -> copyDesign(arguments);
                case "rename_design" -> renameDesign(arguments);
                case "update_content" -> updateContent(arguments);
                case "recompile_lotusscript" -> recompileLotusScript(arguments);
                case "inherit_design" -> inheritDesign(arguments);
                case "delete_design" -> deleteDesign(arguments);
                case "validate_design" -> validateDesign(arguments);
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
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                return new ToolResult("Cancelled", true);
            }
            throw ex;
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
        Object more = json.get("more");
        if (Boolean.TRUE.equals(more)) {
            result.put("more", true);
        }
        return Json.write(result);
    }

    private String listDesign(Map<String, Object> arguments) {
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " is one design note; use get_design");
        }
        Integer limit = limit(arguments);
        String body = kyber.get("/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf(arguments)));
        if (limit == null) {
            return body;
        }
        return applyLimit(body, limit);
    }

    private String createDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        String nsf = writableNsf(arguments);
        String dxl = dxl(arguments, false);
        boolean hasTemplate = present(arguments, "template");
        if (hasTemplate && dxl != null && !dxl.isBlank()) {
            throw new IllegalArgumentException("template cannot be combined with dxl");
        }
        if (kind.singleton) {
            if (!CREATABLE_NOTES.contains(kind.path)) {
                throw new IllegalArgumentException(kind.path + " cannot be created");
            }
            if (present(arguments, "name")) {
                throw new IllegalArgumentException(kind.path + " has no element name");
            }
            if (hasTemplate) {
                throw new IllegalArgumentException(kind.path + " has no template");
            }
            rejectKind(arguments);
            return finish(nsf, writer.exchange(
                    "POST",
                    "/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf),
                    dxl == null ? "" : dxl,
                    XML));
        }
        String name = required(arguments, "name");
        String query = KyberClient.query("nsfPath", nsf) + "&" + KyberClient.query("name", name);
        if (KINDED.contains(kind.path)) {
            if (present(arguments, "kind")) {
                query += "&" + KyberClient.query("kind", Json.string(arguments.get("kind")));
            }
        } else if (present(arguments, "kind")) {
            throw new IllegalArgumentException(kind.path + " has no kind");
        }
        if (hasTemplate) {
            query += "&" + KyberClient.query("template", Json.string(arguments.get("template")));
        }
        return finish(nsf, writer.exchange(
                "POST",
                "/api/kyber-v1/" + kind.path + "?" + query,
                dxl == null ? "" : dxl,
                XML));
    }

    private String updateDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        String nsf = writableNsf(arguments);
        String dxl = dxl(arguments, true);
        rejectKind(arguments);
        if (kind.singleton) {
            if (!UPDATABLE_NOTES.contains(kind.path)) {
                throw new IllegalArgumentException(kind.path + " cannot be replaced through Aldhani");
            }
            if (present(arguments, "name")) {
                throw new IllegalArgumentException(kind.path + " has no element name");
            }
            return finish(nsf, writer.exchange(
                    "PUT",
                    "/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf),
                    dxl,
                    XML));
        }
        String name = required(arguments, "name");
        return finish(nsf, writer.exchange("PUT", elementUrl(kind, name, nsf), dxl, XML));
    }

    private String deleteDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        String nsf = writableNsf(arguments);
        rejectKind(arguments);
        if (present(arguments, "dxl")) {
            throw new IllegalArgumentException("delete_design does not take dxl");
        }
        if (kind.singleton) {
            if (!DELETABLE_NOTES.contains(kind.path)) {
                throw new IllegalArgumentException(kind.path + " cannot be deleted");
            }
            if (present(arguments, "name")) {
                throw new IllegalArgumentException(kind.path + " has no element name");
            }
            return finish(nsf, writer.exchange(
                    "DELETE",
                    "/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf),
                    null,
                    null));
        }
        String name = required(arguments, "name");
        return finish(nsf, writer.exchange("DELETE", elementUrl(kind, name, nsf), null, null));
    }

    private String finish(String nsf, String body) {
        if (!config.syncs(nsf)) {
            return body;
        }
        Map<String, Object> sync;
        try {
            sync = RepoSync.write(config, kyber, nsf);
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw ex;
            }
            sync = new LinkedHashMap<>();
            sync.put("nsfPath", nsf);
            sync.put("error", ex.getMessage() == null ? "repo sync failed" : ex.getMessage());
        }
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>(Json.object(map));
                copy.put("repoSync", sync);
                return Json.write(copy);
            }
        } catch (IllegalArgumentException ignored) {
            // Kyber's body stays as it was when it is not a JSON object.
        }
        return body;
    }

    private String debugKyber() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kyberBaseUrl", config.kyberBaseUrl);
        Map<String, Object> last = kyber.lastExchange();
        if (last.isEmpty()) {
            result.put("last", Map.of());
        } else {
            result.put("last", last);
        }
        return Json.write(result);
    }

    Map<String, Object> lastKyber() {
        return kyber.lastExchange();
    }

    private String syncRepo(Map<String, Object> arguments) {
        if (config.repoRoot == null) {
            throw new IllegalArgumentException(
                    "repoRoot is not set in aldhani.json; set it to an absolute directory then call sync_repo again");
        }
        String given = Json.string(arguments.get("nsfPath"));
        List<String> targets = new ArrayList<>();
        if (given == null || given.isBlank()) {
            targets.addAll(config.syncTargets());
        } else {
            String nsf = nsf(arguments);
            if (!config.syncs(nsf)) {
                throw new IllegalArgumentException("NSF is not in repoNsf: " + nsf);
            }
            targets.add(nsf);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("repoNsf is empty");
        }
        List<Object> databases = new ArrayList<>();
        int total = targets.size();
        for (int i = 0; i < targets.size(); i++) {
            String target = targets.get(i);
            reportProgress("Syncing " + target, i, total);
            databases.add(RepoSync.write(config, kyber, target));
        }
        reportProgress("Finished sync_repo", total, total);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("databases", databases);
        return Json.write(result);
    }

    private String validateDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        Object raw = arguments.get("elements");
        if (!(raw instanceof List<?> elements) || elements.isEmpty()) {
            throw new IllegalArgumentException("elements is required");
        }
        List<Object> normalized = new ArrayList<>();
        for (Object item : elements) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("each element must be an object");
            }
            Map<String, Object> source = Json.object(item);
            String type = Json.string(source.get("type"));
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("each element needs a type");
            }
            boolean deleting = Boolean.TRUE.equals(source.get("delete"));
            String dxl = Json.string(source.get("dxl"));
            if (!deleting && (dxl == null || dxl.isBlank())) {
                throw new IllegalArgumentException("dxl is required unless delete is true");
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("type", kyberType(type));
            if (source.containsKey("name")) {
                copy.put("name", source.get("name"));
            }
            if (dxl != null) {
                copy.put("dxl", dxl);
            }
            if (source.containsKey("delete")) {
                copy.put("delete", source.get("delete"));
            }
            normalized.add(copy);
        }
        return writer.exchange(
                "POST",
                "/api/kyber-v1/validate?" + KyberClient.query("nsfPath", nsf(arguments)),
                Json.write(Map.of("elements", normalized)),
                JSON);
    }

    private KyberExchange writer() {
        if (!config.allowWrite) {
            throw new IllegalArgumentException("Aldhani writes are off");
        }
        return client();
    }

    private KyberExchange client() {
        if (kyber instanceof KyberExchange exchange) {
            return exchange;
        }
        throw new IllegalArgumentException("Kyber client cannot write");
    }

    private static String contentType(Map<String, Object> arguments) {
        String type = AldhaniConfig.lower(required(arguments, "type"));
        type = switch (type) {
            case "images" -> "image";
            case "fileresources" -> "fileresource";
            case "jars" -> "jar";
            default -> type;
        };
        if (!CONTENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be image, fileresource, jar, or dbicon");
        }
        return type;
    }

    private String getDesign(Map<String, Object> arguments) {
        boolean byUnid = present(arguments, "unid");
        boolean byNoteId = present(arguments, "noteId");
        if (byUnid || byNoteId) {
            if (byUnid && byNoteId) {
                throw new IllegalArgumentException("pass unid or noteId, not both");
            }
            if (present(arguments, "type") || present(arguments, "name")) {
                throw new IllegalArgumentException("unid or noteId lookup does not take type or name");
            }
            String query = KyberClient.query("nsfPath", nsf(arguments));
            if (byUnid) {
                query += "&" + KyberClient.query("unid", required(arguments, "unid"));
            } else {
                query += "&" + KyberClient.query("noteId", required(arguments, "noteId"));
            }
            if (Boolean.FALSE.equals(arguments.get("includeDxl"))) {
                query += "&includeDxl=false";
            }
            return page(kyber.get("/api/kyber-v1/lookup?" + query), arguments);
        }
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            if (present(arguments, "name")) {
                throw new IllegalArgumentException(kind.path + " has no element name");
            }
            return page(kyber.get("/api/kyber-v1/" + kind.path + "?" + KyberClient.query("nsfPath", nsf(arguments))), arguments);
        }
        String name = required(arguments, "name");
        return page(kyber.get(elementUrl(kind, name, nsf(arguments))), arguments);
    }

    private String searchDesign(Map<String, Object> arguments) {
        String query = KyberClient.query("nsfPath", nsf(arguments))
                + "&" + KyberClient.query("q", required(arguments, "q"));
        String types = typesCsv(arguments.get("types"));
        if (types != null) {
            query += "&" + KyberClient.query("types", types);
        }
        if (present(arguments, "match")) {
            String match = Json.string(arguments.get("match"));
            if (!SEARCH_MATCH.contains(match)) {
                throw new IllegalArgumentException("match must be name, dxl, or both");
            }
            query += "&" + KyberClient.query("match", match);
        }
        Integer limit = limit(arguments);
        if (limit != null) {
            if (limit > 200) {
                throw new IllegalArgumentException("limit must be at most 200");
            }
            query += "&limit=" + limit;
        }
        return kyber.get("/api/kyber-v1/search?" + query);
    }

    private String listTemplates(Map<String, Object> arguments) {
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " has no templates");
        }
        String query = KyberClient.query("type", kyberType(kind.path));
        if (KINDED.contains(kind.path)) {
            if (present(arguments, "kind")) {
                query += "&" + KyberClient.query("kind", Json.string(arguments.get("kind")));
            }
        } else if (present(arguments, "kind")) {
            throw new IllegalArgumentException(kind.path + " has no kind");
        }
        if (present(arguments, "nsfPath")) {
            query += "&" + KyberClient.query("nsfPath", nsf(arguments));
        }
        return kyber.get("/api/kyber-v1/templates?" + query);
    }

    private String previewStarter(Map<String, Object> arguments) {
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " has no starter preview");
        }
        String query = KyberClient.query("type", kyberType(kind.path));
        if (present(arguments, "name")) {
            query += "&" + KyberClient.query("name", Json.string(arguments.get("name")));
        }
        if (KINDED.contains(kind.path)) {
            if (present(arguments, "kind")) {
                query += "&" + KyberClient.query("kind", Json.string(arguments.get("kind")));
            }
        } else if (present(arguments, "kind")) {
            throw new IllegalArgumentException(kind.path + " has no kind");
        }
        if (present(arguments, "template")) {
            query += "&" + KyberClient.query("template", Json.string(arguments.get("template")));
        }
        if (present(arguments, "nsfPath")) {
            query += "&" + KyberClient.query("nsfPath", nsf(arguments));
        }
        return client().exchange("POST", "/api/kyber-v1/defaults/preview?" + query, "", JSON);
    }

    private String getContent(Map<String, Object> arguments) {
        String type = contentType(arguments);
        String query = KyberClient.query("nsfPath", nsf(arguments)) + "&" + KyberClient.query("type", type);
        if ("dbicon".equals(type)) {
            if (present(arguments, "name")) {
                throw new IllegalArgumentException("dbicon has no element name");
            }
        } else {
            query += "&" + KyberClient.query("name", required(arguments, "name"));
        }
        return page(kyber.get("/api/kyber-v1/content?" + query), arguments);
    }

    private String updateContent(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String type = contentType(arguments);
        String nsf = writableNsf(arguments);
        String contentBase64 = required(arguments, "contentBase64");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentBase64", contentBase64);
        if (present(arguments, "contentType")) {
            body.put("contentType", Json.string(arguments.get("contentType")));
        }
        String query = KyberClient.query("nsfPath", nsf) + "&" + KyberClient.query("type", type);
        if ("dbicon".equals(type)) {
            if (present(arguments, "name")) {
                throw new IllegalArgumentException("dbicon has no element name");
            }
        } else {
            query += "&" + KyberClient.query("name", required(arguments, "name"));
        }
        return finish(nsf, writer.exchange(
                "PUT",
                "/api/kyber-v1/content?" + query,
                Json.write(body),
                JSON));
    }

    private String recompileLotusScript(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        return finish(nsf, writer.exchange(
                "POST",
                "/api/kyber-v1/compile?" + KyberClient.query("nsfPath", nsf),
                null,
                null));
    }

    private String copyDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " cannot be copied");
        }
        String nsf = nsf(arguments);
        String query = KyberClient.query("nsfPath", nsf)
                + "&" + KyberClient.query("type", kyberType(kind.path))
                + "&" + KyberClient.query("name", required(arguments, "name"))
                + "&" + KyberClient.query("toName", required(arguments, "toName"));
        String dest = nsf;
        if (present(arguments, "toNsfPath")) {
            dest = allowlisted(Json.string(arguments.get("toNsfPath")));
            query += "&" + KyberClient.query("toNsfPath", dest);
        }
        requireWritable(dest);
        return finish(dest, writer.exchange("POST", "/api/kyber-v1/copy?" + query, null, null));
    }

    private String renameDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " cannot be renamed");
        }
        String nsf = writableNsf(arguments);
        String query = KyberClient.query("nsfPath", nsf)
                + "&" + KyberClient.query("type", kyberType(kind.path))
                + "&" + KyberClient.query("name", required(arguments, "name"))
                + "&" + KyberClient.query("newName", required(arguments, "newName"));
        return finish(nsf, writer.exchange("POST", "/api/kyber-v1/rename?" + query, null, null));
    }

    private String patchDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        DesignKind kind = resolve(required(arguments, "type"));
        if (kind.singleton) {
            throw new IllegalArgumentException(kind.path + " cannot be patched");
        }
        boolean hasComment = arguments.containsKey("comment");
        boolean hasAlias = arguments.containsKey("alias");
        if (!hasComment && !hasAlias) {
            throw new IllegalArgumentException("comment or alias is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (hasComment) {
            String comment = Json.string(arguments.get("comment"));
            if (comment == null) {
                throw new IllegalArgumentException("comment must be text");
            }
            body.put("comment", comment);
        }
        if (hasAlias) {
            String alias = Json.string(arguments.get("alias"));
            if (alias == null) {
                throw new IllegalArgumentException("alias must be text");
            }
            body.put("alias", alias);
        }
        String nsf = writableNsf(arguments);
        String query = KyberClient.query("nsfPath", nsf)
                + "&" + KyberClient.query("type", kyberType(kind.path))
                + "&" + KyberClient.query("name", required(arguments, "name"));
        return finish(nsf, writer.exchange(
                "PATCH",
                "/api/kyber-v1/patch?" + query,
                Json.write(body),
                JSON));
    }

    private String exportDesign(Map<String, Object> arguments) {
        String nsf = nsf(arguments);
        reportProgress("Exporting " + nsf, 0, 1);
        String body = page(kyber.get("/api/kyber-v1/export?" + KyberClient.query("nsfPath", nsf)), arguments);
        reportProgress("Finished export_design", 1, 1);
        return body;
    }

    private String inventoryDesign(Map<String, Object> arguments) {
        String nsf = nsf(arguments);
        reportProgress("Inventory " + nsf, 0, 1);
        String body = kyber.get("/api/kyber-v1/export?" + KyberClient.query("nsfPath", nsf));
        Map<String, Object> document = Json.object(Json.parse(body));
        Object raw = document.get("elements");
        if (!(raw instanceof List<?> elements)) {
            throw new IllegalArgumentException("Kyber export has no elements");
        }
        List<Object> inventory = new ArrayList<>();
        for (Object item : elements) {
            Map<String, Object> element = Json.object(item);
            Map<String, Object> entry = new LinkedHashMap<>();
            String type = Json.string(element.get("type"));
            if (type != null && !type.isBlank()) {
                entry.put("type", type);
            }
            String name = Json.string(element.get("name"));
            if (name != null && !name.isBlank()) {
                entry.put("name", name);
            }
            String noteId = Json.string(element.get("noteId"));
            if (noteId == null) {
                noteId = Json.string(element.get("noteid"));
            }
            if (noteId != null && !noteId.isBlank()) {
                entry.put("noteId", noteId);
            }
            String unid = Json.string(element.get("unid"));
            if (unid != null && !unid.isBlank()) {
                entry.put("unid", unid);
            }
            inventory.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nsfPath", nsf);
        Object replicaId = document.get("replicaId");
        if (replicaId == null) {
            replicaId = document.get("replicaid");
        }
        if (replicaId != null) {
            result.put("replicaId", replicaId);
        }
        result.put("elementCount", inventory.size());
        result.put("elements", inventory);
        Object skipped = document.get("skipped");
        if (skipped instanceof List<?> list && !list.isEmpty()) {
            result.put("skipped", list.size());
            List<Object> details = new ArrayList<>();
            int limit = Math.min(list.size(), SKIPPED_DETAIL_LIMIT);
            for (int i = 0; i < limit; i++) {
                Object item = list.get(i);
                if (!(item instanceof Map<?, ?>)) {
                    continue;
                }
                Map<String, Object> source = Json.object(item);
                Map<String, Object> row = new LinkedHashMap<>();
                String type = Json.string(source.get("type"));
                if (type != null && !type.isBlank()) {
                    row.put("type", type);
                }
                String name = Json.string(source.get("name"));
                if (name != null && !name.isBlank()) {
                    row.put("name", name);
                }
                String message = Json.string(source.get("message"));
                if (message == null) {
                    message = Json.string(source.get("error"));
                }
                if (message != null && !message.isBlank()) {
                    row.put("message", message);
                }
                if (!row.isEmpty()) {
                    details.add(row);
                }
            }
            if (!details.isEmpty()) {
                result.put("skippedElements", details);
            }
        }
        reportProgress("Finished inventory_design", 1, 1);
        return Json.write(result);
    }

    private String createDatabase(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        String query = KyberClient.query("nsfPath", nsf);
        String title = Json.string(arguments.get("dbTitle"));
        if (title != null && !title.isBlank()) {
            query += "&" + KyberClient.query("dbTitle", title.trim());
        }
        return finish(nsf, writer.exchange(
                "POST",
                "/api/kyber-v1/database?" + query,
                null,
                null));
    }

    private String createDatabaseFromTemplate(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        String template = AldhaniConfig.normalizeNsf(required(arguments, "templateNsfPath"));
        String query = KyberClient.query("nsfPath", nsf)
                + "&" + KyberClient.query("templateNsfPath", template);
        String title = Json.string(arguments.get("dbTitle"));
        if (title != null && !title.isBlank()) {
            query += "&" + KyberClient.query("dbTitle", title.trim());
        }
        if (present(arguments, "encryption")) {
            query += "&" + KyberClient.query("encryption", Json.string(arguments.get("encryption")));
        }
        return finish(nsf, writer.exchange(
                "POST",
                "/api/kyber-v1/database/fromTemplate?" + query,
                null,
                null));
    }

    private String setDbTitle(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        String title = required(arguments, "dbTitle").trim();
        return finish(nsf, writer.exchange(
                "PUT",
                "/api/kyber-v1/database?" + KyberClient.query("nsfPath", nsf)
                        + "&" + KyberClient.query("dbTitle", title),
                null,
                null));
    }

    private String setKyberEnabled(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        boolean enabled = enabledFlag(arguments);
        return writer.exchange(
                "PUT",
                "/api/kyber-v1/database/category?" + KyberClient.query("nsfPath", nsf)
                        + "&enabled=" + enabled,
                null,
                null);
    }

    private String inheritDesign(Map<String, Object> arguments) {
        KyberExchange writer = writer();
        String nsf = writableNsf(arguments);
        String query = KyberClient.query("nsfPath", nsf);
        if (present(arguments, "templateServer")) {
            query += "&" + KyberClient.query("templateServer", Json.string(arguments.get("templateServer")));
        }
        return finish(nsf, writer.exchange(
                "POST",
                "/api/kyber-v1/database/inherit?" + query,
                null,
                null));
    }

    private static boolean enabledFlag(Map<String, Object> arguments) {
        Object raw = arguments.get("enabled");
        if (raw instanceof Boolean value) {
            return value;
        }
        String text = Json.string(raw);
        if (text != null) {
            if ("true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text) || "no".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw new IllegalArgumentException("enabled must be true or false");
    }

    /** A requested part is one slice of the raw Kyber body. Omit part and a large body is summarized instead. */
    static String page(String text, Map<String, Object> arguments) {
        Integer part = part(arguments);
        if (part == null) {
            return text == null ? "" : text;
        }
        String body = text == null ? "" : text;
        int pages = Math.max(1, (body.length() + PAGE - 1) / PAGE);
        if (part >= pages) {
            throw new IllegalArgumentException("part is out of range");
        }
        int start = part * PAGE;
        int end = Math.min(body.length(), start + PAGE);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("part", part);
        result.put("parts", pages);
        result.put("totalChars", body.length());
        result.put("text", body.substring(start, end));
        return Json.write(result);
    }

    private static Integer part(Map<String, Object> arguments) {
        Object value = arguments.get("part");
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("part must be a non-negative integer");
        }
        if (number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("part is out of range");
        }
        return (int) number.longValue();
    }

    private static void alias(String plural, String singular) {
        COLLECTIONS.put(plural, plural);
        if (!plural.equals(singular)) {
            COLLECTIONS.put(singular, plural);
        }
    }

    private static DesignKind resolve(String type) {
        String key = AldhaniConfig.lower(type);
        if (SINGLETONS.contains(key)) {
            return new DesignKind(key, true);
        }
        String collection = COLLECTIONS.get(key);
        if (collection == null) {
            throw new IllegalArgumentException("Unknown Kyber design type " + type);
        }
        return new DesignKind(collection, false);
    }

    private static boolean present(Map<String, Object> arguments, String key) {
        String value = Json.string(arguments.get(key));
        return value != null && !value.isBlank();
    }

    private String nsf(Map<String, Object> arguments) {
        String given = Json.string(arguments.get("nsfPath"));
        String path = (given == null || given.isBlank()) ? config.onlyNsf() : given;
        if (path == null) {
            throw new IllegalArgumentException(
                    "nsfPath is required when more than one database is allowlisted ("
                            + String.join(", ", config.allowedNsf) + ")");
        }
        return allowlisted(path);
    }

    /** Optional progress sink: message, completed steps, total steps. */
    static final ThreadLocal<Progress> PROGRESS = new ThreadLocal<>();

    record Progress(java.util.function.Consumer<ProgressUpdate> sink) {
        void report(String message, int completed, int total) {
            if (sink != null) {
                sink.accept(new ProgressUpdate(message, completed, total));
            }
        }
    }

    record ProgressUpdate(String message, int completed, int total) {
    }

    private void reportProgress(String message, int completed, int total) {
        Progress progress = PROGRESS.get();
        if (progress != null) {
            progress.report(message, completed, total);
        }
    }

    private String writableNsf(Map<String, Object> arguments) {
        return requireWritable(nsf(arguments));
    }

    private String requireWritable(String nsf) {
        if (!config.writes(nsf)) {
            throw new IllegalArgumentException("NSF is not writable through Aldhani: " + nsf);
        }
        return nsf;
    }

    private String allowlisted(String path) {
        String normalized = AldhaniConfig.normalizeNsf(path);
        if (!config.allows(normalized)) {
            throw new IllegalArgumentException("NSF is not in the Aldhani allowlist: " + normalized);
        }
        return normalized;
    }

    /** Path-unsafe titles (parentheses) use Kyber {@code /named/{type}?name=}. */
    private static String elementUrl(DesignKind kind, String name, String nsf) {
        String query = KyberClient.query("nsfPath", nsf);
        if (needsNamedRoute(name)) {
            return "/api/kyber-v1/named/" + KyberClient.pathSegment(kyberType(kind.path))
                    + "?" + query + "&" + KyberClient.query("name", name);
        }
        return "/api/kyber-v1/" + kind.path + "/" + KyberClient.pathSegment(name) + "?" + query;
    }

    private static boolean needsNamedRoute(String name) {
        return name.indexOf('(') >= 0 || name.indexOf(')') >= 0;
    }

    private static String typesCsv(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                String type = Json.string(item);
                if (type == null || type.isBlank()) {
                    throw new IllegalArgumentException("types entries must be text");
                }
                parts.add(kyberType(type));
            }
            return String.join(",", parts);
        }
        String text = Json.string(raw);
        if (text == null || text.isBlank()) {
            return null;
        }
        return text.trim();
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
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (IllegalArgumentException ex) {
            return summary(text.length(), null);
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return summary(text.length(), null);
        }
        Map<String, Object> source = Json.object(parsed);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), stripDxl(entry.getValue()));
        }
        copy.put("truncated", true);
        copy.put("chars", text.length());
        String written = Json.write(copy);
        if (written.length() <= MAX_CHARS) {
            return written;
        }
        return summary(text.length(), source);
    }

    boolean writesEnabled() {
        return config.allowWrite;
    }

    Set<String> names() {
        return config.allowWrite ? WRITE_NAMES : READ_NAMES;
    }

    private static String applyLimit(String body, int limit) {
        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IllegalArgumentException ex) {
            return body;
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return body;
        }
        Map<String, Object> source = Json.object(parsed);
        Object raw = source.get("elements");
        if (!(raw instanceof List<?> elements) || elements.size() <= limit) {
            return body;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof List<?> list && list.size() == elements.size()) {
                copy.put(entry.getKey(), new ArrayList<>(list.subList(0, limit)));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        copy.put("truncated", true);
        copy.put("returned", limit);
        copy.put("total", elements.size());
        return Json.write(copy);
    }

    private static Integer limit(Map<String, Object> arguments) {
        Object raw = arguments.get("limit");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            double exact = number.doubleValue();
            int value = number.intValue();
            if (exact == value && value >= 1) {
                return value;
            }
        }
        throw new IllegalArgumentException("limit must be a whole number of at least 1");
    }

    private static String dxl(Map<String, Object> arguments, boolean requiredDxl) {
        Object raw = arguments.get("dxl");
        if (raw == null) {
            if (requiredDxl) {
                throw new IllegalArgumentException("dxl is required");
            }
            return null;
        }
        String text = Json.string(raw);
        if (text == null) {
            throw new IllegalArgumentException("dxl must be text");
        }
        if (requiredDxl && text.isBlank()) {
            throw new IllegalArgumentException("dxl is required");
        }
        return text;
    }

    private static void rejectKind(Map<String, Object> arguments) {
        if (present(arguments, "kind")) {
            throw new IllegalArgumentException(
                    "kind is only used when creating an agent, script library, or Java source");
        }
    }

    private static String kyberType(String type) {
        DesignKind kind = resolve(type);
        if (kind.singleton
                || "sharedactions".equals(kind.path)
                || "wiringproperties".equals(kind.path)) {
            return kind.path;
        }
        for (Map.Entry<String, String> entry : COLLECTIONS.entrySet()) {
            if (entry.getValue().equals(kind.path) && !entry.getKey().equals(kind.path)) {
                return entry.getKey();
            }
        }
        return kind.path;
    }

    private static Object stripDxl(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("dxl".equals(entry.getKey())) {
                    continue;
                }
                copy.put(String.valueOf(entry.getKey()), stripDxl(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(stripDxl(item));
            }
            return copy;
        }
        return value;
    }

    private static String summary(int chars, Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("truncated", true);
        out.put("chars", chars);
        if (source != null) {
            for (String key : List.of("type", "name", "unid", "noteId", "nsfPath")) {
                Object value = source.get(key);
                if (value != null) {
                    out.put(key, value);
                }
            }
        }
        return Json.write(out);
    }

    record ToolResult(String text, boolean error) {
    }

    private record DesignKind(String path, boolean singleton) {
    }
}
