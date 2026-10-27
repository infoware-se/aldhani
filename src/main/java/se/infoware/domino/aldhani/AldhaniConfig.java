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
    final String publicBaseUrl;
    final String oauthSubject;

    private AldhaniConfig(
            String kyberBaseUrl,
            String kyberUser,
            String kyberPassword,
            String bearerToken,
            List<String> allowedNsf,
            boolean insecureTls,
            boolean allowWrite,
            String publicBaseUrl,
            String oauthSubject) {
        this.kyberBaseUrl = kyberBaseUrl;
        this.kyberUser = kyberUser;
        this.kyberPassword = kyberPassword;
        this.bearerToken = bearerToken;
        this.allowedNsf = List.copyOf(allowedNsf);
        this.insecureTls = insecureTls;
        this.allowWrite = allowWrite;
        this.publicBaseUrl = publicBaseUrl;
        this.oauthSubject = oauthSubject;
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
        return new AldhaniConfig(
                base,
                required(root, "kyberUser"),
                required(root, "kyberPassword"),
                required(root, "bearerToken"),
                paths,
                insecure,
                allowWrite,
                optional(root, "publicBaseUrl"),
                optional(root, "oauthSubject"));
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
