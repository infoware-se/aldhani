package se.infoware.domino.aldhani;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Zero-config MCP auth cookie. Domino may consume {@code Authorization: Bearer}; this cookie
 * and {@code Authorization: Aldhani} reach the servlet without Internet Site OIDC trust.
 */
final class AccessCookie {
    static final String NAME = "aldhani_at";

    private AccessCookie() {
    }

    static void attach(HttpServletResponse response, String accessToken, long expiresIn, boolean secure) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        StringBuilder cookie = new StringBuilder();
        cookie.append(NAME).append('=').append(accessToken);
        cookie.append("; Path=/; HttpOnly; Max-Age=").append(Math.max(1L, expiresIn));
        cookie.append("; SameSite=Lax");
        if (secure) {
            cookie.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookie.toString());
    }

    static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    static boolean secureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && forwarded.regionMatches(true, 0, "https", 0, 5);
    }
}
