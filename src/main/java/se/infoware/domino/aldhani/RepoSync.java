package se.infoware.domino.aldhani;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes a Kyber design export into repoRoot using Package Explorer paths. */
final class RepoSync {
    private static final Pattern REPLICA = Pattern.compile("(?i)\\breplicaid\\s*=\\s*'([0-9A-Fa-f]{16})'");

    private RepoSync() {
    }

    static Map<String, Object> write(AldhaniConfig config, KyberGet kyber, String nsfPath) {
        if (config.repoRoot == null) {
            throw new IllegalArgumentException("repoRoot is not set");
        }
        String nsf = AldhaniConfig.normalizeNsf(nsfPath);
        if (!config.allows(nsf)) {
            throw new IllegalArgumentException("NSF is not in the Aldhani allowlist: " + nsf);
        }
        if (!config.syncs(nsf)) {
            throw new IllegalArgumentException("NSF is not in repoNsf: " + nsf);
        }
        String export = kyber.get("/api/kyber-v1/export?" + KyberClient.query("nsfPath", nsf));
        Map<String, Object> document = Json.object(Json.parse(export));
        Object raw = document.get("elements");
        if (!(raw instanceof List<?> elements)) {
            throw new IllegalArgumentException("Kyber export has no elements");
        }
        String replicaId = replicaId(elements);
        Path repoRoot = Path.of(config.repoRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(repoRoot)) {
            throw new IllegalArgumentException("repoRoot is not a directory");
        }
        String folder = RepoLayout.databaseFolder(nsf, replicaId);
        Path destination = safe(repoRoot, repoRoot.resolve(folder));
        Path staging = safe(repoRoot, repoRoot.resolve(folder + ".tmp"));
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            List<String> files = new ArrayList<>();
            List<Object> manifest = new ArrayList<>();
            for (Object item : elements) {
                Map<String, Object> element = Json.object(item);
                String type = Json.string(element.get("type"));
                String name = Json.string(element.get("name"));
                String noteId = Json.string(element.get("noteId"));
                if (noteId == null) {
                    noteId = Json.string(element.get("noteid"));
                }
                String dxl = Json.string(element.get("dxl"));
                if (type == null || type.isBlank() || dxl == null) {
                    throw new IllegalArgumentException("Kyber export element is missing type or dxl");
                }
                String relative = relativeFile(type, name, noteId);
                Path file = safe(staging, staging.resolve(relative));
                Files.createDirectories(file.getParent());
                Files.writeString(file, dxl, StandardCharsets.UTF_8);
                files.add(relative);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", type);
                if (name != null && !name.isBlank()) {
                    entry.put("name", name);
                }
                entry.put("path", relative);
                if (noteId != null && !noteId.isBlank()) {
                    entry.put("noteid", noteId);
                }
                String unid = Json.string(element.get("unid"));
                if (unid != null && !unid.isBlank()) {
                    entry.put("unid", unid);
                }
                manifest.add(entry);
            }
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("nsfPath", nsf);
            index.put("replicaId", replicaId);
            index.put("layout", RepoLayout.LAYOUT);
            index.put("elementCount", manifest.size());
            index.put("elements", manifest);
            Files.writeString(staging.resolve("manifest.json"), Json.write(index), StandardCharsets.UTF_8);
            deleteTree(destination);
            Files.move(staging, destination);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nsfPath", nsf);
            result.put("replicaId", replicaId);
            result.put("root", folder);
            result.put("written", files.size());
            result.put("files", files);
            Object skipped = document.get("skipped");
            if (skipped instanceof List<?> list && !list.isEmpty()) {
                result.put("skipped", list.size());
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not write " + destination + ": " + ex.getMessage());
        }
    }

    private static String relativeFile(String type, String name, String noteId) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("type", type);
        if (name != null && !name.isBlank()) {
            arguments.put("name", name);
        }
        if (noteId != null && !noteId.isBlank()) {
            arguments.put("noteId", noteId);
        }
        return Json.string(Json.object(Json.parse(RepoLayout.describe(arguments))).get("path"));
    }

    private static String replicaId(List<?> elements) {
        for (Object item : elements) {
            String dxl = Json.string(Json.object(item).get("dxl"));
            if (dxl == null) {
                continue;
            }
            Matcher match = REPLICA.matcher(dxl);
            if (match.find()) {
                return match.group(1).toUpperCase();
            }
        }
        throw new IllegalArgumentException("Kyber export has no replica id");
    }

    private static Path safe(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("repo path escapes repoRoot");
        }
        return normalized;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
