package se.infoware.domino.aldhani;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class McpProtocol {
    static final String VERSION = "0.1.21";
    private static final String LATEST_PROTOCOL = "2025-11-25";
    private static final Set<String> PROTOCOL_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            LATEST_PROTOCOL);
    private static final List<String> LOG_LEVELS = List.of(
            "debug", "info", "notice", "warning", "error", "critical", "alert", "emergency");

    /** Writes one JSON-RPC message as soon as it exists, so progress is not held until the result. */
    static final ThreadLocal<Consumer<Map<String, Object>>> LIVE = new ThreadLocal<>();
    /** Opens the SSE response before a request that can emit notifications. Not used for initialize. */
    static final ThreadLocal<Runnable> ON_REQUEST = new ThreadLocal<>();

    private final DesignTools tools;
    private final CallTracker tracker = new CallTracker();
    private final AtomicReference<String> logLevel = new AtomicReference<>("info");
    private final ConcurrentHashMap<String, String> sessionLevels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionNsf = new ConcurrentHashMap<>();
    private final ThreadLocal<List<Map<String, Object>>> notes = new ThreadLocal<>();
    private final ThreadLocal<String> session = new ThreadLocal<>();

    McpProtocol(DesignTools tools) {
        this.tools = tools;
    }

    AldhaniConfig config() {
        return tools.config();
    }

    static boolean supports(String protocolVersion) {
        return PROTOCOL_VERSIONS.contains(protocolVersion);
    }

    boolean running(Object id) {
        return tracker.running(CallTracker.key(id));
    }

    /** A null body means HTTP 202 with no content. */
    HttpReply handle(String body) {
        return handle(body, null);
    }

    HttpReply handle(String body, String sessionId) {
        notes.set(new ArrayList<>());
        session.set(sessionId);
        try {
            return handleMessage(body);
        } finally {
            notes.remove();
            session.remove();
            Thread.interrupted();
        }
    }

    private HttpReply handleMessage(String body) {
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
        String key = CallTracker.key(id);
        CallTracker.Flight flight = null;
        if (!notification && key != null && !"initialize".equals(method)) {
            flight = tracker.start(key);
            Runnable open = ON_REQUEST.get();
            if (open != null) {
                open.run();
            }
        }
        try {
            if (flight != null && flight.cancelled.get()) {
                return HttpReply.stopped();
            }
            Object result = dispatch(method, message.get("params"));
            if (flight != null && flight.cancelled.get()) {
                return HttpReply.stopped();
            }
            if (notification) {
                return HttpReply.accepted();
            }
            return reply(200, result(id, result), "initialize".equals(method));
        } catch (UnknownMethod ex) {
            if (notification) {
                return HttpReply.accepted();
            }
            return reply(200, error(id, -32601, ex.getMessage()), false);
        } catch (IllegalArgumentException ex) {
            if (notification) {
                return HttpReply.accepted();
            }
            return reply(200, error(id, -32602, ex.getMessage()), false);
        } finally {
            if (key != null && flight != null) {
                tracker.finish(key);
            }
        }
    }

    private HttpReply reply(int status, Object body, boolean openSession) {
        return new HttpReply(status, Json.write(body), List.copyOf(notes.get()), false, openSession);
    }

    private Object dispatch(String method, Object params) {
        Map<String, Object> object = params instanceof Map<?, ?> ? Json.object(params) : Map.of();
        return switch (method) {
            case "initialize" -> initialize(object);
            case "notifications/initialized", "initialized" -> Map.of();
            case "ping" -> Map.of();
            case "tools/list" -> Map.of("tools", toolDefinitions());
            case "tools/call" -> callTool(object);
            case "resources/list" -> Map.of("resources", resources());
            case "resources/templates/list" -> Map.of("resourceTemplates", resourceTemplates());
            case "resources/read" -> readResource(object);
            case "completion/complete" -> complete(object);
            case "prompts/list" -> listPrompts(object);
            case "prompts/get" -> getPrompt(object);
            case "logging/setLevel" -> setLevel(object);
            case "notifications/cancelled" -> {
                tracker.cancel(CallTracker.key(object.get("requestId")));
                yield Map.of();
            }
            default -> throw new UnknownMethod("Unknown method " + method);
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String requested = Json.string(params.get("protocolVersion"));
        String version = requested != null && PROTOCOL_VERSIONS.contains(requested)
                ? requested
                : LATEST_PROTOCOL;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", Map.of(
                "tools", Map.of("listChanged", false),
                "resources", Map.of("subscribe", false, "listChanged", false),
                "prompts", Map.of("listChanged", false),
                "logging", Map.of(),
                "completions", Map.of()));
        result.put("serverInfo", Map.of("name", "aldhani", "version", VERSION));
        result.put("instructions", instructions());
        return result;
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = Json.string(params.get("name"));
        if (name == null || !tools.names().contains(name)) {
            Map<String, Object> missing = new LinkedHashMap<>();
            missing.put("content", List.of(Map.of("type", "text", "text", "Unknown tool " + name)));
            missing.put("isError", true);
            return missing;
        }
        Object raw = params.get("arguments");
        Map<String, Object> arguments = new LinkedHashMap<>(
                raw instanceof Map<?, ?> ? Json.object(raw) : Map.of());
        applySessionNsf(arguments);
        Object progressToken = progressToken(params);
        log("info", "aldhani", callData(name, arguments, "started", Map.of()));
        if (progressToken != null) {
            progress(progressToken, 0, 1, "Started " + name);
            DesignTools.PROGRESS.set(new DesignTools.Progress(update ->
                    progress(progressToken, update.completed(), Math.max(1, update.total()), update.message())));
        }
        DesignTools.ToolResult outcome;
        try {
            outcome = tools.call(name, arguments);
        } finally {
            DesignTools.PROGRESS.remove();
        }
        if (Thread.currentThread().isInterrupted()) {
            return Map.of();
        }
        if (progressToken != null) {
            progress(progressToken, 1, 1, (outcome.error() ? "Failed " : "Finished ") + name);
        }
        Map<String, Object> finished = callData(
                name, arguments, outcome.error() ? "failed" : "finished", tools.lastKyber());
        log(outcome.error() ? "error" : "info", "aldhani", finished);
        if (!tools.lastKyber().isEmpty()) {
            log("debug", "aldhani.kyber", Map.copyOf(tools.lastKyber()));
        }
        if (!outcome.error()) {
            rememberSessionNsf(arguments, outcome.text());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(Map.of("type", "text", "text", outcome.text())));
        result.put("isError", outcome.error());
        if (!outcome.error()) {
            try {
                Object parsed = Json.parse(outcome.text());
                if (parsed instanceof Map<?, ?>) {
                    result.put("structuredContent", parsed);
                }
            } catch (IllegalArgumentException ignored) {
                // Text that is not a JSON object stays in content only.
            }
        }
        return result;
    }

    private void applySessionNsf(Map<String, Object> arguments) {
        String given = Json.string(arguments.get("nsfPath"));
        if (given != null && !given.isBlank()) {
            return;
        }
        String sid = session.get();
        if (sid == null || sid.isBlank()) {
            return;
        }
        String remembered = sessionNsf.get(sid);
        if (remembered != null && !remembered.isBlank()) {
            arguments.put("nsfPath", remembered);
        }
    }

    private void rememberSessionNsf(Map<String, Object> arguments, String resultText) {
        String sid = session.get();
        if (sid == null || sid.isBlank()) {
            return;
        }
        String nsf = Json.string(arguments.get("nsfPath"));
        if (nsf == null || nsf.isBlank()) {
            try {
                Object parsed = Json.parse(resultText);
                if (parsed instanceof Map<?, ?> map) {
                    nsf = Json.string(Json.object(map).get("nsfPath"));
                }
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        if (nsf == null || nsf.isBlank()) {
            return;
        }
        try {
            sessionNsf.put(sid, AldhaniConfig.normalizeNsf(nsf));
        } catch (IllegalArgumentException ignored) {
            // Do not remember a path the allowlist would reject.
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        Map<String, Object> read = hints(true, false, true);
        List<Map<String, Object>> definitions = new ArrayList<>();
        definitions.add(tool(
                "list_databases",
                "Lists the Kyber-enabled databases Aldhani is allowed to see. "
                        + "aldhanitest.nsf is the sandbox. Other allowlisted databases may appear. "
                        + "more is true when Kyber had more files than the allowlist filter kept.",
                Map.of(
                        "fileType", prop("string",
                                "database (default), template, replica_candidate, or template_candidate")),
                List.of(),
                read));
        definitions.add(tool(
                "list_design",
                "Lists one Kyber design collection in an allowed NSF. "
                        + "A database note such as acl is read with get_design. "
                        + "limit keeps the first rows and sets truncated when more remain.",
                Map.of(
                        "type", prop("string", typeDescription()),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                        "limit", prop("integer", "Maximum rows to return. Omit to return the whole collection.")),
                List.of("type"),
                read));
        definitions.add(tool(
                "get_design",
                "Returns one design element or one database note, including its DXL. "
                        + "Pass type and name (or type alone for a database note), "
                        + "or look up by unid or noteId without type. "
                        + "For acl, Kyber also returns structured entries and roles beside dxl. "
                        + "Names with parentheses use Kyber's /named route automatically.",
                Map.of(
                        "type", prop("string", typeDescription()),
                        "name", prop("string", "Element name. Omit for a database note."),
                        "unid", prop("string", "Note UNID. Use instead of type and name."),
                        "noteId", prop("string", "Note id. Use instead of type and name."),
                        "includeDxl", prop("boolean", "When looking up by unid or noteId, include DXL. Default true."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                        "part", prop("integer", "Zero-based page of a large body. Each page is raw text, not a summary.")),
                List.of(),
                read));
        definitions.add(tool(
                "export_design",
                "Exports the whole NSF design as DXL. Large. Prefer inventory_design, then get_design.",
                Map.of(
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                        "part", prop("integer", "Zero-based page of a large export. Each page is raw text, not a summary.")),
                List.of(),
                read));
        definitions.add(tool(
                "inventory_design",
                "Lists every design element in an NSF without DXL. "
                        + "Each row has type, name when present, noteId, and unid. "
                        + "Prefer this before get_design. Use export_design only when the DXL bodies are needed.",
                Map.of(
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of(),
                read));
        definitions.add(tool(
                "search_design",
                "Searches design names and optionally DXL in an allowed NSF. "
                        + "Prefer this over inventory_design loops when looking for a name or substring.",
                Map.of(
                        "q", prop("string", "Search text."),
                        "types", prop("string",
                                "Comma-separated design types, or omit to search every collection."),
                        "match", prop("string", "name (default), dxl, or both."),
                        "limit", prop("integer", "Maximum matches. At most 200."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of("q"),
                read));
        definitions.add(tool(
                "list_templates",
                "Lists starter template names for one design type from kyber.nsf and JAR defaults. "
                        + "Use the name with create_design template, or preview_starter.",
                Map.of(
                        "type", prop("string", typeDescription()),
                        "kind", prop("string", "Agent, script library, or Java source kind."),
                        "nsfPath", prop("string", "Optional NSF for Kyber routing. Usually omit.")),
                List.of("type"),
                read));
        definitions.add(tool(
                "preview_starter",
                "Resolves the empty-create starter DXL without writing. "
                        + "Optional template picks a named starter from list_templates.",
                Map.of(
                        "type", prop("string", typeDescription()),
                        "name", prop("string", "Proposed element name for the preview."),
                        "kind", prop("string", "Agent, script library, or Java source kind."),
                        "template", prop("string", "Starter name from list_templates."),
                        "nsfPath", prop("string", "Optional NSF for Kyber routing. Usually omit.")),
                List.of("type"),
                read));
        definitions.add(tool(
                "get_content",
                "Returns binary design content as contentBase64. "
                        + "type is image, fileresource, jar, or dbicon. name is required except for dbicon.",
                Map.of(
                        "type", prop("string", "image, fileresource, jar, or dbicon."),
                        "name", prop("string", "Element name. Omit for dbicon."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                        "part", prop("integer", "Zero-based page of a large body.")),
                List.of("type"),
                read));
        definitions.add(tool(
                "list_fields",
                "Lists field name, type, and kind on one form or subform without full DXL. "
                        + "Prefer this before get_design when inspecting form structure.",
                Map.of(
                        "type", prop("string", "form or subform."),
                        "name", prop("string", "Element name."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of("type", "name"),
                read));
        definitions.add(tool(
                "list_columns",
                "Lists column title, formula, and flags on one view or folder without full DXL. "
                        + "Prefer this before get_design when inspecting view structure.",
                Map.of(
                        "type", prop("string", "view or folder."),
                        "name", prop("string", "Element name."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of("type", "name"),
                read));
        definitions.add(tool(
                "get_design_summary",
                "Compact inventory for one design note: aliases, kind, comment, agent trigger/schedule, "
                        + "field or column counts, view forms. DXL omitted unless includeDxl is true. "
                        + "Pass type and name (or type alone for a database note), or unid or noteId alone.",
                Map.of(
                        "type", prop("string", typeDescription()),
                        "name", prop("string", "Element name. Omit for a database note."),
                        "unid", prop("string", "Note UNID. Use instead of type and name."),
                        "noteId", prop("string", "Note id. Use instead of type and name."),
                        "includeDxl", prop("boolean", "Include DXL. Default false."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                        "part", prop("integer", "Zero-based page when includeDxl returned a large body.")),
                List.of(),
                read));
        definitions.add(tool(
                "diff_design",
                "Compares two live notes (type, name, toName, optional toType) or a live note to proposed DXL. "
                        + "With dxl or nested left/right objects, posts to Kyber; otherwise GETs two named notes. "
                        + "Returns structured field/column/comment changes and DXL line hunks.",
                Map.of(
                        "type", prop("string", "Left note type for flat compare."),
                        "name", prop("string", "Left note name for flat compare."),
                        "toName", prop("string", "Right note name when comparing two live notes."),
                        "toType", prop("string", "Right note type when different from type."),
                        "dxl", prop("string", "Proposed right-hand DXL. Triggers POST /diff."),
                        "left", prop("object", "Nested left side: type+name or dxl."),
                        "right", prop("object", "Nested right side: type+name or dxl."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of(),
                read));
        definitions.add(tool(
                "list_agents_schedule",
                "Lists agents with trigger and schedule metadata for an NSF, without full DXL. "
                        + "Optional trigger filter and scheduledOnly=true for scheduled agents only.",
                Map.of(
                        "trigger", prop("string", "Filter by trigger, for example schedule, manual, or agentlist."),
                        "scheduledOnly", prop("boolean", "When true, only agents with a schedule."),
                        "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                List.of(),
                read));
        definitions.add(tool(
                "compare_inventories",
                "Compares inventory_design for two allowlisted NSFs. "
                        + "Returns onlyInLeft, onlyInRight, changed (same type/name, different unid), and sameCount. "
                        + "Does not fetch DXL.",
                Map.of(
                        "nsfPath", prop("string", "Left NSF path. Defaults to the only allowed database."),
                        "toNsfPath", prop("string", "Right NSF path. Must be allowlisted and different from nsfPath.")),
                List.of("toNsfPath"),
                read));
        definitions.add(tool(
                "repo_path",
                "Package Explorer path for one design file. "
                        + "The folder is the Domino Designer folder, not the API name. "
                        + "Omit type to list every folder. "
                        + "Pass nsfPath and replicaId together for the NSF folder domino/<nsfPath>-<replicaId>. "
                        + "noteId makes the file <name>-<noteId>.dxl. This tool does not call Kyber.",
                Map.of(
                        "type", prop("string", "Design type. Singular or plural. Omit to list the folders."),
                        "name", prop("string", "Element name. A slash is a path for Java and XPages resources."),
                        "noteId", prop("string", "Note id from list_design or get_design. Appended to the file name."),
                        "nsfPath", prop("string", "NSF path. Set together with replicaId."),
                        "replicaId", prop("string", "Replica id, 16 hex digits. Set together with nsfPath.")),
                List.of(),
                read));
        definitions.add(tool(
                "repo_status",
                "Reports NSF trees under repoRoot from their manifest.json files: "
                        + "nsfPath, replicaId, elementCount, root, and syncedAt. "
                        + "Does not call Kyber. Fails when repoRoot is unset.",
                Map.of(),
                List.of(),
                read));
        definitions.add(tool(
                "sync_repo",
                "Writes every design element Kyber exports into repoRoot, using Package Explorer paths. "
                        + "Includes the database icon as Resources/Images/$DBIcon-<noteId>.dxl. "
                        + "Omit nsfPath to refresh every NSF in repoNsf. "
                        + "A design write or delete does this for the NSF it changed when that NSF is in repoNsf, "
                        + "so stale DXL files are removed. "
                        + "Fails with an actionable message when repoRoot is unset. "
                        + "repoRoot may be absolute or relative to the Domino data directory.",
                Map.of(
                        "nsfPath", prop("string", "NSF to refresh. Omit to refresh every NSF in repoNsf.")),
                List.of(),
                hints(false, false, true)));
        definitions.add(tool(
                "debug_kyber",
                "Returns the last Kyber HTTP method, path (without query), status, and ok flag for support. "
                        + "Never includes tokens, passwords, or request bodies.",
                Map.of(),
                List.of(),
                read));
        if (tools.writesEnabled()) {
            definitions.add(tool(
                    "create_database",
                    "Creates one missing NSF that is already on allowedNsf. "
                            + "Kyber sets the category KyberEnabled and gives the caller Manager. "
                            + "dbTitle sets the database title. An existing file is left unchanged. "
                            + "Database delete is not a tool.",
                    Map.of(
                            "nsfPath", prop("string", "NSF path. Must be allowlisted. Defaults to the only allowed database."),
                            "dbTitle", prop("string", "Database title. Omit to use Kyber's default for that path.")),
                    List.of(),
                    hints(false, false, false)));
            definitions.add(tool(
                    "create_database_from_template",
                    "Creates one missing allowlisted NSF from a Domino NTF/NSF template. "
                            + "Kyber sets KyberEnabled and gives the caller Manager. "
                            + "templateNsfPath is path-safe but need not be allowlisted. "
                            + "An existing target responds as a conflict. Database delete is not a tool.",
                    Map.of(
                            "nsfPath", prop("string", "Target NSF path. Must be allowlisted."),
                            "templateNsfPath", prop("string", "Source template NSF/NTF path."),
                            "dbTitle", prop("string", "Database title after create."),
                            "encryption", prop("string", "none, simple, medium, strong, aes128, or aes256.")),
                    List.of("templateNsfPath"),
                    hints(false, false, false)));
            definitions.add(tool(
                    "set_db_title",
                    "Sets the database title on an allowlisted Kyber-enabled NSF without rewriting applicationproperties.",
                    Map.of(
                            "dbTitle", prop("string", "New database title."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("dbTitle"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "set_kyber_enabled",
                    "Sets or clears the KyberEnabled catalog category on an allowlisted NSF. "
                            + "Clearing it stops further Kyber design calls on that file until it is set again.",
                    Map.of(
                            "enabled", prop("boolean", "true to set KyberEnabled, false to clear it."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("enabled"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "create_design",
                    "Creates one design element. Omit dxl to copy Kyber's starter. "
                            + "template selects a named starter from list_templates. "
                            + "name is required for a collection. Agents, script libraries, and Java sources take kind. "
                            + "about, using, databasescript, and xspproperties can be created. "
                            + "acl, applicationproperties, and dbicon cannot.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Element name. Omit for a creatable database note."),
                            "kind", prop("string", "Agent, script library, or Java source kind. Only on create."),
                            "template", prop("string", "Starter name from list_templates. Not with dxl or dxlParts."),
                            "dxl", prop("string", "DXL. Omit to copy the starter. Not with dxlParts."),
                            "dxlParts", prop("array",
                                    "Ordered DXL fragments joined before the write when one string would be too large."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type"),
                    hints(false, false, false)));
            definitions.add(tool(
                    "update_design",
                    "Replaces one existing design element or database note with DXL. "
                            + "acl cannot be replaced. applicationproperties and dbicon can. "
                            + "Use dxlParts instead of dxl when the host truncates a single large string.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Element name. Omit for a database note."),
                            "dxl", prop("string", "Replacement DXL. Not with dxlParts."),
                            "dxlParts", prop("array",
                                    "Ordered DXL fragments joined before the write when one string would be too large."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "patch_design",
                    "Patches comment and/or alias on one named design element without replacing the DXL.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Element name."),
                            "comment", prop("string", "Design comment. Omit to leave it unchanged."),
                            "alias", prop("string", "Alias after |. Omit to leave it unchanged."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type", "name"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "copy_design",
                    "Copies one named design element. toNsfPath may be another allowlisted NSF.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Source element name."),
                            "toName", prop("string", "Name of the copy."),
                            "nsfPath", prop("string", "Source NSF path. Defaults to the only allowed database."),
                            "toNsfPath", prop("string", "Destination NSF. Defaults to nsfPath. Must be allowlisted.")),
                    List.of("type", "name", "toName"),
                    hints(false, false, false)));
            definitions.add(tool(
                    "rename_design",
                    "Renames one named design element without delete-plus-create.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Current element name."),
                            "newName", prop("string", "New element name."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type", "name", "newName"),
                    hints(false, false, false)));
            definitions.add(tool(
                    "update_content",
                    "Replaces binary design content from contentBase64. "
                            + "type is image, fileresource, jar, or dbicon. name is required except for dbicon.",
                    Map.of(
                            "type", prop("string", "image, fileresource, jar, or dbicon."),
                            "name", prop("string", "Element name. Omit for dbicon."),
                            "contentBase64", prop("string", "Base64-encoded bytes."),
                            "contentType", prop("string", "Optional MIME type, for example image/png."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type", "contentBase64"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "recompile_lotusscript",
                    "Runs Kyber's Recompile All LotusScript for an allowlisted NSF.",
                    Map.of(
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of(),
                    hints(false, false, false)));
            definitions.add(tool(
                    "inherit_design",
                    "Refreshes design from the NSF's design template via Domino Database.refreshDesign. "
                            + "Optional templateServer names the server that holds the template.",
                    Map.of(
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database."),
                            "templateServer", prop("string", "Template server. Omit for the local/current server.")),
                    List.of(),
                    hints(false, false, false)));
            definitions.add(tool(
                    "import_design_element",
                    "Creates or replaces one design note from DXL via Kyber PUT /upsert. "
                            + "Preserves note UNID on replace. Not a full NSF import and does not compileAll. "
                            + "acl cannot be replaced. Prefer create_design or update_design when the note "
                            + "existence is already known. Use dxlParts for oversized DXL strings.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Element name. Optional; defaults to the name in DXL. Omit for a database note."),
                            "dxl", prop("string", "DXL to create or replace. Not with dxlParts."),
                            "dxlParts", prop("array",
                                    "Ordered DXL fragments joined before the upsert when one string would be too large."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type"),
                    hints(false, false, true)));
            definitions.add(tool(
                    "delete_design",
                    "Deletes one named design element, or about, using, databasescript, or xspproperties. "
                            + "acl, applicationproperties, and dbicon cannot be deleted.",
                    Map.of(
                            "type", prop("string", typeDescription()),
                            "name", prop("string", "Element name. Omit for a deletable database note."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("type"),
                    hints(false, true, true)));
            definitions.add(tool(
                    "validate_design",
                    "Checks a design payload with Kyber and writes nothing. "
                            + "Each element may use dxlParts instead of dxl for a large body.",
                    Map.of(
                            "elements", prop("array",
                                    "Objects with type, and name, dxl or dxlParts, or delete. "
                                            + "dxl/dxlParts required unless delete is true."),
                            "nsfPath", prop("string", "NSF path. Defaults to the only allowed database.")),
                    List.of("elements"),
                    read));
        }
        return definitions;
    }

    private static Map<String, Object> tool(
            String name,
            String description,
            Map<String, Object> properties,
            List<String> required,
            Map<String, Object> annotations) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        Map<String, Object> outputSchema = outputSchema(name);
        if (outputSchema != null) {
            tool.put("outputSchema", outputSchema);
        }
        tool.put("annotations", annotations);
        return tool;
    }

    private static Map<String, Object> outputSchema(String name) {
        return switch (name) {
            case "list_databases" -> listDatabasesSchema();
            case "inventory_design" -> inventorySchema();
            case "search_design" -> searchSchema();
            case "repo_path" -> repoPathSchema();
            case "repo_status" -> repoStatusSchema();
            case "sync_repo" -> syncRepoSchema();
            case "debug_kyber" -> debugKyberSchema();
            case "validate_design" -> validateSchema();
            case "list_design", "get_design", "export_design", "get_content", "preview_starter",
                    "list_fields", "list_columns", "get_design_summary", "diff_design",
                    "list_agents_schedule", "compare_inventories" ->
                    designObjectSchema();
            case "list_templates", "create_database", "create_database_from_template",
                    "set_db_title", "set_kyber_enabled", "recompile_lotusscript", "inherit_design",
                    "create_design", "update_design", "patch_design", "copy_design", "rename_design",
                    "update_content", "import_design_element", "delete_design" ->
                    objectSchema();
            default -> null;
        };
    }

    private static Map<String, Object> objectSchema() {
        return Map.of("type", "object", "additionalProperties", true);
    }

    private static Map<String, Object> listDatabasesSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("allowedNsf", "databases", "missing"));
        schema.put("properties", Map.of(
                "allowedNsf", Map.of("type", "array", "items", Map.of("type", "string")),
                "databases", Map.of("type", "array", "items", Map.of("type", "object")),
                "missing", Map.of("type", "array", "items", Map.of("type", "string")),
                "more", Map.of("type", "boolean")));
        return schema;
    }

    private static Map<String, Object> inventorySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("required", List.of("nsfPath", "elementCount", "elements"));
        schema.put("properties", Map.of(
                "nsfPath", Map.of("type", "string"),
                "replicaId", Map.of("type", "string"),
                "elementCount", Map.of("type", "integer"),
                "elements", Map.of("type", "array", "items", Map.of("type", "object")),
                "skipped", Map.of("type", "integer"),
                "skippedElements", Map.of("type", "array", "items", Map.of("type", "object"))));
        return schema;
    }

    private static Map<String, Object> searchSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("properties", Map.of(
                "nsfPath", Map.of("type", "string"),
                "q", Map.of("type", "string"),
                "match", Map.of("type", "string"),
                "elements", Map.of("type", "array", "items", Map.of("type", "object")),
                "truncated", Map.of("type", "boolean"),
                "returned", Map.of("type", "integer"),
                "total", Map.of("type", "integer")));
        return schema;
    }

    private static Map<String, Object> repoPathSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("properties", Map.of(
                "layout", Map.of("type", "string"),
                "path", Map.of("type", "string"),
                "folder", Map.of("type", "string"),
                "folders", Map.of("type", "object")));
        return schema;
    }

    private static Map<String, Object> repoStatusSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("repoRoot", "databases"));
        schema.put("properties", Map.of(
                "repoRoot", Map.of("type", "string"),
                "databases", Map.of("type", "array", "items", Map.of("type", "object"))));
        return schema;
    }

    private static Map<String, Object> syncRepoSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("databases"));
        schema.put("properties", Map.of(
                "databases", Map.of("type", "array", "items", Map.of("type", "object"))));
        return schema;
    }

    private static Map<String, Object> debugKyberSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("kyberBaseUrl", "last"));
        schema.put("properties", Map.of(
                "kyberBaseUrl", Map.of("type", "string"),
                "last", Map.of("type", "object", "additionalProperties", true)));
        return schema;
    }

    private static Map<String, Object> validateSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("properties", Map.of(
                "valid", Map.of("type", "boolean"),
                "errors", Map.of("type", "array", "items", Map.of("type", "object")),
                "repoSync", Map.of("type", "object")));
        return schema;
    }

    private static Map<String, Object> designObjectSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("truncated", Map.of("type", "boolean"));
        properties.put("returned", Map.of("type", "integer"));
        properties.put("total", Map.of("type", "integer"));
        properties.put("chars", Map.of("type", "integer"));
        properties.put("part", Map.of("type", "integer"));
        properties.put("parts", Map.of("type", "integer"));
        properties.put("totalChars", Map.of("type", "integer"));
        properties.put("text", Map.of("type", "string"));
        properties.put("elements", Map.of("type", "array"));
        properties.put("dxl", Map.of("type", "string"));
        properties.put("name", Map.of("type", "string"));
        properties.put("unid", Map.of("type", "string"));
        properties.put("noteId", Map.of("type", "string"));
        properties.put("repoSync", Map.of("type", "object"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> hints(boolean readOnly, boolean destructive, boolean idempotent) {
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", true);
        return annotations;
    }

    private static String typeDescription() {
        return "Collection: " + String.join(", ", DesignTools.collections())
                + ". Database note: " + String.join(", ", DesignTools.singletons()) + ".";
    }

    private String instructions() {
        String text = "Aldhani is the MCP server for Domino design. Every design call goes to Kyber. "
                + "Read with inventory_design, list_design, or search_design, then get_design for one element. "
                + "get_design_summary, list_fields, and list_columns avoid full DXL when only structure is needed. "
                + "diff_design compares two notes or a note to proposed DXL. list_agents_schedule lists agent triggers. "
                + "compare_inventories diffs two allowlisted NSF inventories without DXL. "
                + "Large write bodies may use dxlParts (ordered fragments) instead of one dxl string. "
                + "get_design accepts type and name, or unid or noteId alone. "
                + "When several databases are allowlisted, pass nsfPath or reuse the session default from the last call that named one. "
                + "Resources: aldhani://types lists collections; aldhani://<nsf>/inventory is inventory without a tool call. "
                + "list_templates and preview_starter show empty-create starters; get_content reads image, file, jar, or dbicon bytes. "
                + "A result with truncated left out DXL because the body was large. "
                + "Call get_design or export_design with part to read that body in pages. "
                + "repo_path is where that DXL file belongs in a repository. "
                + "repo_status reports synced trees under repoRoot. "
                + "When repoRoot is set, a design write or delete refreshes that NSF in the repository, and sync_repo refreshes every NSF in repoNsf. "
                + "debug_kyber shows the last Kyber path and HTTP status without tokens. "
                + "Database delete, a full design import, and an ACL replacement are not tools. "
                + "Prompts review_design and explain_design say how to read one element. "
                + "Send the caller token as Authorization: Aldhani or X-Aldhani-Token "
                + "(OAuth access tokens use the same headers; Domino may swallow Authorization: Bearer). ";
        if (tools.writesEnabled()) {
            return text + "create_database and create_database_from_template create a missing allowlisted NSF. "
                    + "set_db_title and set_kyber_enabled change database metadata. "
                    + "create_design (optional template), update_design, import_design_element (upsert), patch_design, "
                    + "copy_design, rename_design, update_content, recompile_lotusscript, inherit_design, "
                    + "and delete_design change design. "
                    + "Residual notes use type designnote. "
                    + "validate_design writes nothing. acl cannot be replaced or deleted.";
        }
        return text + "Writes are off.";
    }

    private List<Map<String, Object>> resources() {
        List<Map<String, Object>> listed = new ArrayList<>();
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("uri", "aldhani://types");
        types.put("name", "design-types");
        types.put("mimeType", "application/json");
        types.put("description", "Kyber design collections and database notes Aldhani accepts as type");
        listed.add(types);
        for (String nsf : tools.allowedNsf()) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("uri", "aldhani://" + nsf);
            resource.put("name", nsf);
            resource.put("mimeType", "application/json");
            resource.put("description", "Allowlisted Domino database");
            listed.add(resource);
            Map<String, Object> inventory = new LinkedHashMap<>();
            inventory.put("uri", "aldhani://" + nsf + "/inventory");
            inventory.put("name", nsf + "-inventory");
            inventory.put("mimeType", "application/json");
            inventory.put("description", "Design inventory for " + nsf + " without DXL");
            listed.add(inventory);
        }
        return listed;
    }

    private static List<Map<String, Object>> resourceTemplates() {
        return List.of(
                template("aldhani://{nsfPath}/inventory", "Design inventory",
                        "Every design element in an allowlisted NSF without DXL."),
                template("aldhani://{nsfPath}/{type}", "Design collection",
                        "One Kyber collection or the element names in an allowlisted NSF."),
                template("aldhani://{nsfPath}/{type}/{name}", "Design element",
                        "One design element, including its DXL. name may contain slashes."));
    }

    private static Map<String, Object> template(String uri, String name, String description) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("uriTemplate", uri);
        template.put("name", name);
        template.put("mimeType", "application/json");
        template.put("description", description);
        return template;
    }

    private Map<String, Object> readResource(Map<String, Object> params) {
        String uri = Json.string(params.get("uri"));
        if (uri == null || !uri.startsWith("aldhani://")) {
            throw new IllegalArgumentException("uri must start with aldhani://");
        }
        String rest = uri.substring("aldhani://".length());
        String text;
        if ("types".equalsIgnoreCase(rest)) {
            text = Json.write(Map.of(
                    "collections", DesignTools.collections(),
                    "singletons", DesignTools.singletons()));
        } else {
            String nsf = longestAllowlisted(rest);
            if (nsf == null) {
                throw new IllegalArgumentException("uri needs an allowlisted NSF path or aldhani://types");
            }
            String tail = rest.substring(nsf.length());
            if (tail.startsWith("/")) {
                tail = tail.substring(1);
            }
            if (tail.isEmpty()) {
                DesignTools.ToolResult listed = tools.call("list_databases", Map.of());
                if (listed.error()) {
                    throw new IllegalArgumentException(listed.text());
                }
                text = oneDatabase(listed.text(), nsf);
            } else if ("inventory".equalsIgnoreCase(tail)) {
                DesignTools.ToolResult inventory = tools.call("inventory_design", Map.of("nsfPath", nsf));
                if (inventory.error()) {
                    throw new IllegalArgumentException(inventory.text());
                }
                text = inventory.text();
            } else {
                String[] pieces = tail.split("/", 2);
                Map<String, Object> arguments = new LinkedHashMap<>();
                arguments.put("nsfPath", nsf);
                arguments.put("type", pieces[0]);
                String tool = "list_design";
                if (pieces.length == 2) {
                    tool = "get_design";
                    arguments.put("name", pieces[1]);
                } else if (DesignTools.singletons().contains(AldhaniConfig.lower(pieces[0]))) {
                    tool = "get_design";
                }
                DesignTools.ToolResult outcome = tools.call(tool, arguments);
                if (outcome.error()) {
                    throw new IllegalArgumentException(outcome.text());
                }
                text = outcome.text();
            }
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", uri);
        content.put("mimeType", "application/json");
        content.put("text", text);
        return Map.of("contents", List.of(content));
    }

    /** The allowlist entry that matches the URI. A nested NSF beats a shorter prefix. */
    private String longestAllowlisted(String rest) {
        String found = null;
        for (String allowed : tools.allowedNsf()) {
            boolean exact = rest.equalsIgnoreCase(allowed);
            boolean nested = rest.length() > allowed.length()
                    && rest.regionMatches(true, 0, allowed, 0, allowed.length())
                    && rest.charAt(allowed.length()) == '/';
            if ((exact || nested) && (found == null || allowed.length() > found.length())) {
                found = allowed;
            }
        }
        return found;
    }

    private static String oneDatabase(String text, String nsf) {
        Map<String, Object> json = Json.object(Json.parse(text));
        List<Object> present = new ArrayList<>();
        Object raw = json.get("databases");
        if (raw instanceof List<?> databases) {
            for (Object item : databases) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String path = Json.string(Json.object(map).get("nsfPath"));
                if (path == null) {
                    continue;
                }
                try {
                    if (nsf.equalsIgnoreCase(AldhaniConfig.normalizeNsf(path))) {
                        present.add(item);
                    }
                } catch (IllegalArgumentException ignored) {
                    // A Kyber path outside the allowlist rules is not this resource.
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allowedNsf", List.of(nsf));
        result.put("databases", present);
        result.put("missing", present.isEmpty() ? List.of(nsf) : List.of());
        return Json.write(result);
    }

    private Map<String, Object> complete(Map<String, Object> params) {
        Map<String, Object> argument = params.get("argument") instanceof Map<?, ?> map ? Json.object(map) : Map.of();
        String name = Json.string(argument.get("name"));
        String prefix = Json.string(argument.get("value"));
        if (prefix == null) {
            prefix = "";
        }
        Map<String, Object> context = params.get("context") instanceof Map<?, ?> map ? Json.object(map) : Map.of();
        Map<String, Object> given = context.get("arguments") instanceof Map<?, ?> map ? Json.object(map) : Map.of();
        List<String> values = new ArrayList<>();
        if ("type".equals(name)) {
            values.addAll(DesignTools.collections());
            values.addAll(DesignTools.singletons());
        } else if ("nsfPath".equals(name) || "toNsfPath".equals(name) || "templateNsfPath".equals(name)) {
            values.addAll(tools.allowedNsf());
        } else if ("fileType".equals(name)) {
            values.addAll(List.of("database", "template", "replica_candidate", "template_candidate"));
        } else if ("match".equals(name)) {
            values.addAll(List.of("name", "dxl", "both"));
        } else if ("name".equals(name) || "toName".equals(name) || "newName".equals(name) || "template".equals(name)) {
            String type = Json.string(given.get("type"));
            if (type != null && !type.isBlank()) {
                if ("template".equals(name)) {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("type", type);
                    if (given.get("kind") != null) {
                        call.put("kind", given.get("kind"));
                    }
                    DesignTools.ToolResult listed = tools.call("list_templates", call);
                    if (!listed.error()) {
                        values.addAll(templateNames(listed.text()));
                    }
                } else {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("type", type);
                    if (given.get("nsfPath") != null) {
                        call.put("nsfPath", given.get("nsfPath"));
                    }
                    DesignTools.ToolResult listed = tools.call("list_design", call);
                    if (!listed.error()) {
                        values.addAll(elementNames(listed.text()));
                    }
                }
            }
        }
        String lower = prefix.toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase().startsWith(lower)) {
                matched.add(value);
            }
        }
        int total = matched.size();
        boolean more = total > 100;
        if (more) {
            matched = new ArrayList<>(matched.subList(0, 100));
        }
        Map<String, Object> completion = new LinkedHashMap<>();
        completion.put("values", matched);
        completion.put("total", total);
        completion.put("hasMore", more);
        return Map.of("completion", completion);
    }

    private static List<String> elementNames(String text) {
        return stringFieldList(text, "elements", "name");
    }

    private static List<String> templateNames(String text) {
        List<String> names = stringFieldList(text, "templates", "name");
        if (!names.isEmpty()) {
            return names;
        }
        return stringFieldList(text, "elements", "name");
    }

    private static List<String> stringFieldList(String text, String arrayKey, String field) {
        List<String> names = new ArrayList<>();
        try {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map<?, ?> map)) {
                return names;
            }
            Object raw = Json.object(map).get(arrayKey);
            if (!(raw instanceof List<?> elements)) {
                return names;
            }
            for (Object item : elements) {
                if (item instanceof Map<?, ?> element) {
                    String name = Json.string(Json.object(element).get(field));
                    if (name != null) {
                        names.add(name);
                    }
                } else {
                    String name = Json.string(item);
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            return names;
        }
        return names;
    }

    private Map<String, Object> listPrompts(Map<String, Object> params) {
        if (params.get("cursor") != null) {
            throw new IllegalArgumentException("Unknown cursor");
        }
        return Map.of("prompts", prompts());
    }

    private List<Map<String, Object>> prompts() {
        List<Map<String, Object>> listed = new ArrayList<>();
        listed.add(prompt(
                "review_design",
                "Review a design element",
                "Reads one element through Kyber and reviews its DXL.",
                true));
        listed.add(prompt(
                "explain_design",
                "Explain a design element",
                "Reads one element through Kyber and explains it in plain language.",
                true));
        if (tools.writesEnabled()) {
            listed.add(prompt(
                    "create_element",
                    "Create one design element",
                    "Validates, then creates one element. Writes nothing else.",
                    true));
        }
        return listed;
    }

    private static Map<String, Object> prompt(String name, String title, String description, boolean typeRequired) {
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put("name", name);
        prompt.put("title", title);
        prompt.put("description", description);
        prompt.put("arguments", List.of(
                argument("type", "Collection or database note.", typeRequired),
                argument("name", "Element name. Omit for a database note.", false),
                argument("nsfPath", "NSF path. Defaults to the only allowed database.", false),
                argument("purpose", "What the new element is for. Used by create_element.", false)));
        return prompt;
    }

    private static Map<String, Object> argument(String name, String description, boolean required) {
        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("name", name);
        argument.put("description", description);
        argument.put("required", required);
        return argument;
    }

    private Map<String, Object> getPrompt(Map<String, Object> params) {
        String name = Json.string(params.get("name"));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map ? Json.object(map) : Map.of();
        String type = Json.string(arguments.get("type"));
        String element = Json.string(arguments.get("name"));
        String nsf = Json.string(arguments.get("nsfPath"));
        String purpose = Json.string(arguments.get("purpose"));
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        String where = (nsf == null || nsf.isBlank() ? "the default allowed NSF" : nsf)
                + ", type " + type
                + (element == null || element.isBlank() ? "" : ", name " + element);
        String text = switch (name) {
            case "review_design" -> "Call get_design for " + where
                    + ". Review the DXL that Kyber returns: structure, names, formulas, and anything that would break the database. "
                    + "Quote only design that the tool returned.";
            case "explain_design" -> "Call get_design for " + where
                    + ". Explain that element in plain language, including what a user of the database would notice. "
                    + "Quote only design that the tool returned.";
            case "create_element" -> {
                if (!tools.writesEnabled()) {
                    throw new IllegalArgumentException("Writes are off.");
                }
                yield "Create one design element in " + where
                        + (purpose == null || purpose.isBlank() ? "." : ". Purpose: " + purpose + ".")
                        + " Call validate_design before create_design. "
                        + "Do not delete a database, import a full design, or replace the ACL.";
            }
            default -> throw new IllegalArgumentException("Unknown prompt " + name);
        };
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", Map.of("type", "text", "text", text));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("description", name);
        result.put("messages", List.of(message));
        return result;
    }

    private Map<String, Object> setLevel(Map<String, Object> params) {
        String level = Json.string(params.get("level"));
        if (level == null || !LOG_LEVELS.contains(level)) {
            throw new IllegalArgumentException("level must be a syslog severity");
        }
        String sid = session.get();
        if (sid == null || sid.isBlank()) {
            logLevel.set(level);
        } else {
            sessionLevels.put(sid, level);
        }
        return Map.of();
    }

    private void log(String level, String logger, Map<String, Object> data) {
        if (!emits(level)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("level", level);
        params.put("logger", logger);
        params.put("data", data);
        notify("notifications/message", params);
    }

    private boolean emits(String level) {
        String sid = session.get();
        String floor = sid == null ? null : sessionLevels.get(sid);
        if (floor == null) {
            floor = logLevel.get();
        }
        return LOG_LEVELS.indexOf(level) >= LOG_LEVELS.indexOf(floor);
    }

    private static Map<String, Object> callData(
            String tool, Map<String, Object> arguments, String event, Map<String, Object> kyber) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", event);
        data.put("tool", tool);
        String type = Json.string(arguments.get("type"));
        if (type != null) {
            data.put("type", type);
        }
        String nsf = Json.string(arguments.get("nsfPath"));
        if (nsf != null) {
            data.put("nsfPath", nsf);
        }
        if (kyber != null && !kyber.isEmpty()) {
            data.put("kyber", kyber);
        }
        return data;
    }

    private void progress(Object token, int value, int total, String message) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("progressToken", token);
        params.put("progress", value);
        params.put("total", total);
        params.put("message", message);
        notify("notifications/progress", params);
    }

    private static Object progressToken(Map<String, Object> params) {
        if (!(params.get("_meta") instanceof Map<?, ?> meta)) {
            return null;
        }
        Object token = Json.object(meta).get("progressToken");
        if (token instanceof String || token instanceof Number) {
            return token;
        }
        return null;
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.put("params", params);
        List<Map<String, Object>> pending = notes.get();
        if (pending != null) {
            pending.add(message);
        }
        Consumer<Map<String, Object>> live = LIVE.get();
        if (live != null) {
            live.accept(message);
        }
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

    record HttpReply(
            int status,
            String body,
            List<Map<String, Object>> notifications,
            boolean suppressed,
            boolean openSession) {
        static HttpReply json(int status, Object body) {
            return new HttpReply(status, Json.write(body), List.of(), false, false);
        }

        static HttpReply accepted() {
            return new HttpReply(202, null, List.of(), false, false);
        }

        static HttpReply stopped() {
            return new HttpReply(202, null, List.of(), true, false);
        }
    }
}
