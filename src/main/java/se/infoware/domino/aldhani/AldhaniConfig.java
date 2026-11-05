package se.infoware.domino.aldhani;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AldhaniConfig {
    final String kyberBaseUrl;
    final String kyberUser;
    final String kyberPassword;
    final String bearerToken;
    final List<String> allowedNsf;
    final boolean insecureTls;
    final boolean allowWrite;
    /** Null means every allowlisted NSF may be written when {@link #allowWrite} is true. */
    final List<String> writeNsf;
    final String publicBaseUrl;
    final String oauthSubject;
    final String repoRoot;
    final List<String> repoNsf;

    private AldhaniConfig(
            String kyberBaseUrl,
            String kyberUser,
            String kyberPassword,
            String bearerToken,
            List<String> allowedNsf,
            boolean insecureTls,
            boolean allowWrite,
            List<String> writeNsf,
            String publicBaseUrl,
            String oauthSubject,
            String repoRoot,
            List<String> repoNsf) {
        this.kyberBaseUrl = kyberBaseUrl;
        this.kyberUser = kyberUser;
        this.kyberPassword = kyberPassword;
        this.bearerToken = bearerToken;
        this.allowedNsf = List.copyOf(allowedNsf);
        this.insecureTls = insecureTls;
        this.allowWrite = allowWrite;
        this.writeNsf = writeNsf == null ? null : List.copyOf(writeNsf);
        this.publicBaseUrl = publicBaseUrl;
        this.oauthSubject = oauthSubject;
        this.repoRoot = repoRoot;
        this.repoNsf = repoNsf == null ? null : List.copyOf(repoNsf);
    }

    static Path configFile() {
        String override = System.getenv("ALDHANI_CONFIG");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return dataDirectory().resolve("aldhani.json");
    }

    static AldhaniConfig load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return parse(text);
    }

    static AldhaniConfig parse(String text) {
        Map<String, Object> root = Json.object(Json.parse(text));
        String base = required(root, "kyberBaseUrl");
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Object allowed = root.get("allowedNsf");
        if (!(allowed instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("allowedNsf must list at least one NSF");
        }
        List<String> paths = new ArrayList<>();
        for (Object item : list) {
            String path = Json.string(item);
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("allowedNsf contains an empty path");
            }
            paths.add(normalizeNsf(path));
        }
        Object tls = root.get("insecureTls");
        boolean insecure = Boolean.TRUE.equals(tls);
        boolean allowWrite = Boolean.TRUE.equals(root.get("allowWrite"));
        List<String> writeNsf = pathList(root, "writeNsf");
        AldhaniConfig config = new AldhaniConfig(
                base,
                required(root, "kyberUser"),
                required(root, "kyberPassword"),
                required(root, "bearerToken"),
                paths,
                insecure,
                allowWrite,
                writeNsf,
                optional(root, "publicBaseUrl"),
                optional(root, "oauthSubject"),
                resolveRepoRoot(optional(root, "repoRoot")),
                pathList(root, "repoNsf"));
        if (config.writeNsf != null) {
            for (String path : config.writeNsf) {
                if (!config.allows(path)) {
                    throw new IllegalArgumentException("writeNsf is not allowlisted: " + path);
                }
            }
        }
        if (config.repoNsf != null) {
            for (String path : config.repoNsf) {
                if (!config.allows(path)) {
                    throw new IllegalArgumentException("repoNsf is not allowlisted: " + path);
                }
            }
        }
        return config;
    }

    /** NSFs whose design is written under repoRoot. Missing repoNsf means every allowlisted NSF. */
    List<String> syncTargets() {
        if (repoRoot == null) {
            return List.of();
        }
        return repoNsf == null ? allowedNsf : repoNsf;
    }

    boolean syncs(String nsfPath) {
        if (repoRoot == null) {
            return false;
        }
        String normalized = normalizeNsf(nsfPath);
        for (String path : syncTargets()) {
            if (path.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** True when writes are on and this NSF is in the write grant (or every allowlisted NSF). */
    boolean writes(String path) {
        if (!allowWrite) {
            return false;
        }
        String normalized = normalizeNsf(path);
        if (!allows(normalized)) {
            return false;
        }
        if (writeNsf == null) {
            return true;
        }
        for (String granted : writeNsf) {
            if (granted.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> pathList(Map<String, Object> root, String key) {
        if (!root.containsKey(key) || root.get(key) == null) {
            return null;
        }
        Object raw = root.get(key);
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a list");
        }
        List<String> paths = new ArrayList<>();
        for (Object item : list) {
            String path = Json.string(item);
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(key + " contains an empty path");
            }
            paths.add(normalizeNsf(path));
        }
        return paths;
    }

    static String normalizeNsf(String path) {
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.contains("..")
                || normalized.indexOf(':') >= 0) {
            throw new IllegalArgumentException("NSF path is not allowed: " + path);
        }
        return normalized;
    }

    boolean allows(String path) {
        String normalized = normalizeNsf(path);
        for (String allowed : allowedNsf) {
            if (allowed.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    String onlyNsf() {
        if (allowedNsf.size() == 1) {
            return allowedNsf.get(0);
        }
        return null;
    }

    private static String optional(Map<String, Object> root, String key) {
        String value = Json.string(root.get(key));
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Absolute {@code repoRoot} is kept. A relative path is resolved against the Domino data
     * directory (the same base as {@code aldhani.json}), not the MCP client workspace.
     */
    static String resolveRepoRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path path = Path.of(raw.trim());
        if (!path.isAbsolute()) {
            path = dataDirectory().resolve(path);
        }
        return path.toAbsolutePath().normalize().toString();
    }

    private static String required(Map<String, Object> root, String key) {
        String value = Json.string(root.get(key));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static Path dataDirectory() {
        String area = System.getProperty("osgi.instance.area");
        if (area != null && area.startsWith("file:")) {
            try {
                Path workspace = Path.of(URI.create(area));
                Path domino = workspace.getParent();
                if (domino != null && "workspace".equalsIgnoreCase(workspace.getFileName().toString())) {
                    Path data = domino.getParent();
                    if (data != null) {
                        return data;
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Fall through to the process directory.
            }
        }
        return Path.of("").toAbsolutePath();
    }

    static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
