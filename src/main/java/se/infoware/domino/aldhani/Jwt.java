package se.infoware.domino.aldhani;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/** Compact JWT, RS256 only. No extra claims are logged by callers. */
final class Jwt {
    private Jwt() {
    }

    static String sign(PrivateKey key, String kid, Map<String, Object> claims) {
        String header = b64(Json.write(Map.of("alg", "RS256", "typ", "JWT", "kid", kid)));
        String payload = b64(Json.write(claims));
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(signature.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Could not sign a token", ex);
        }
    }

    static Map<String, Object> verify(PublicKey key, String token, long nowSeconds) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Token is not a JWT");
        }
        byte[] signed = decode(parts[2]);
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(signed)) {
                throw new IllegalArgumentException("Token signature was rejected");
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Token signature was rejected");
        }
        Object parsed = Json.parse(new String(decode(parts[1]), StandardCharsets.UTF_8));
        Map<String, Object> claims = Json.object(parsed);
        long exp = number(claims.get("exp"));
        if (exp <= nowSeconds) {
            throw new IllegalArgumentException("Token is expired");
        }
        return claims;
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Token exp is missing");
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String text) {
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Token is not a JWT");
        }
    }
}
