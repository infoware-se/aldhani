package se.infoware.domino.aldhani;

import javax.servlet.http.HttpServletRequest;

/** HTTPS origin advertised in OAuth metadata. A configured value wins over the request. */
final class PublicOrigin {
    private PublicOrigin() {
    }

    static String of(HttpServletRequest request, String configured) {
        if (configured != null && !configured.isBlank()) {
            return strip(configured);
        }
        String scheme = request.isSecure() ? "https" : request.getScheme();
        if (scheme == null || scheme.isBlank()) {
            scheme = "https";
        }
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean standard = ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80)
                || port <= 0;
        return strip(scheme + "://" + host + (standard ? "" : ":" + port));
    }

    private static String strip(String value) {
        String text = value.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
