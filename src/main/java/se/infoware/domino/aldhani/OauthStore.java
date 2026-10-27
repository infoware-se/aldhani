package se.infoware.domino.aldhani;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authorization-code server for MCP clients. Access tokens are RS256 JWTs so Domino's
 * HTTP bearer check can forward them. Refresh tokens stay opaque and are not JWTs.
 */
final class OauthStore {
    static final String SCOPE = "aldhani";
    static final String DOMINO_SCOPE = "Domino.user.all";
    private static final long CODE_SECONDS = 120;
    private static final long ACCESS_SECONDS = 600;
    private static final long REFRESH_SECONDS = 12 * 60 * 60;
    private static final String BUILTIN_CLIENT = "aldhani";

    private static volatile OauthStore instance;

    private final KeyPair keys;
    private final String kid;
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Code> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Refresh> refreshes = new ConcurrentHashMap<>();

    private OauthStore(KeyPair keys, String kid) {
        this.keys = keys;
        this.kid = kid;
    }

    static OauthStore open(Path configFile) {
        OauthStore current = instance;
        if (current != null) {
            return current;
        }
        synchronized (OauthStore.class) {
            if (instance == null) {
                Path file = configFile.resolveSibling("aldhani-oauth.pem");
                instance = new OauthStore(loadOrCreate(file), "aldhani");
            }
            return instance;
        }
    }

    static OauthStore memory(KeyPair keys) {
        return new OauthStore(keys, "test");
    }

    String register(List<String> redirects) {
        if (redirects == null || redirects.isEmpty()) {
            throw new IllegalArgumentException("redirect_uris is required");
        }
        for (String redirect : redirects) {
            if (!httpsOrLoopback(redirect)) {
                throw new IllegalArgumentException("redirect_uri is not allowed");
            }
        }
        String id = UUID.randomUUID().toString();
        clients.put(id, new Client(List.copyOf(redirects)));
        return id;
    }

    String authorizationRedirect(
            String clientId,
            String redirect,
            String challenge,
            String method,
            String state,
            String subject,
            String presentedToken,
            String expectedToken) {
        if (!"S256".equals(method) || challenge == null || challenge.length() < 43) {
            throw new IllegalArgumentException("code_challenge must be S256");
        }
        if (!redirectAllowed(clientId, redirect)) {
            throw new IllegalArgumentException("redirect_uri is not allowed");
        }
        if (presentedToken == null || expectedToken == null
                || !MessageDigest.isEqual(presentedToken.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The token was not accepted");
        }
        String code = random();
        codes.put(code, new Code(clientId, redirect, challenge, subject, Instant.now().getEpochSecond() + CODE_SECONDS));
        String location = redirect + (redirect.contains("?") ? "&" : "?") + "code=" + code;
        if (state != null && !state.isBlank()) {
            location += "&state=" + url(state);
        }
        return location;
    }

    Access exchangeCode(String clientId, String redirect, String code, String verifier, String issuer, String audience) {
        Code stored = codes.remove(code);
        if (stored == null || stored.expires < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("invalid_grant");
        }
        if (!stored.clientId.equals(clientId) || !stored.redirect.equals(redirect)) {
            throw new IllegalArgumentException("invalid_grant");
        }
        if (!s256(verifier).equals(stored.challenge)) {
            throw new IllegalArgumentException("invalid_grant");
        }
        return issue(stored.subject, issuer, audience);
    }

    Access refresh(String refreshToken, String issuer, String audience) {
        String hash = sha256(refreshToken);
        Refresh stored = refreshes.remove(hash);
        if (stored == null || stored.expires < Instant.now().getEpochSecond()) {
            throw new IllegalArgumentException("invalid_grant");
        }
        return issue(stored.subject, issuer, audience);
    }

    boolean accessValid(String token) {
        try {
            Map<String, Object> claims = Jwt.verify(keys.getPublic(), token, Instant.now().getEpochSecond());
            String scope = Json.string(claims.get("scope"));
            return scope != null && scope.contains(SCOPE);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    static Map<String, Object> resource(String origin) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", origin + "/mcp");
        body.put("authorization_servers", List.of(origin + "/oauth"));
        body.put("scopes_supported", List.of(SCOPE));
        body.put("bearer_methods_supported", List.of("header"));
        return body;
    }

    Map<String, Object> jwks() {
        RSAPublicKey rsa = (RSAPublicKey) keys.getPublic();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("kty", "RSA");
        key.put("use", "sig");
        key.put("alg", "RS256");
        key.put("kid", kid);
        key.put("n", unsigned(rsa.getModulus().toByteArray()));
        key.put("e", unsigned(rsa.getPublicExponent().toByteArray()));
        return Map.of("keys", List.of(key));
    }

    private Access issue(String subject, String issuer, String audience) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", List.of(audience, audience + "/mcp"));
        claims.put("sub", subject);
        claims.put("email", subject);
        claims.put("upn", subject);
        claims.put("scope", SCOPE + " " + DOMINO_SCOPE);
        claims.put("iat", now);
        claims.put("nbf", now - 30);
        claims.put("exp", now + ACCESS_SECONDS);
        claims.put("jti", UUID.randomUUID().toString());
        String refresh = random();
        refreshes.put(sha256(refresh), new Refresh(subject, now + REFRESH_SECONDS));
        return new Access(Jwt.sign(keys.getPrivate(), kid, claims), refresh, ACCESS_SECONDS);
    }

    private boolean redirectAllowed(String clientId, String redirect) {
        if (redirect == null || redirect.isBlank()) {
            return false;
        }
        if (BUILTIN_CLIENT.equals(clientId)) {
            return loopbackOrCursor(redirect);
        }
        Client client = clients.get(clientId);
        return client != null && client.redirects.contains(redirect);
    }

    static boolean httpsOrLoopback(String redirect) {
        return loopbackOrCursor(redirect) || https(redirect);
    }

    private static boolean https(String redirect) {
        try {
            return "https".equalsIgnoreCase(URI.create(redirect).getScheme());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean loopbackOrCursor(String redirect) {
        try {
            URI uri = URI.create(redirect);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if ("cursor".equalsIgnoreCase(scheme)) {
                return true;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String s256(String verifier) {
        if (verifier == null || verifier.length() < 43 || verifier.length() > 128) {
            return "";
        }
        return b64(sha256Bytes(verifier));
    }

    private static String sha256(String value) {
        return b64(sha256Bytes(value));
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is missing", ex);
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return b64(bytes);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** JWK integers drop the sign byte BigInteger adds. */
    private static String unsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return b64(bytes);
    }

    private static String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KeyPair loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                String text = Files.readString(file, StandardCharsets.US_ASCII);
                return pair(section(text, "PRIVATE KEY"), section(text, "PUBLIC KEY"));
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair created = generator.generateKeyPair();
            String pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(created.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(created.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n";
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
            return created;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not load the OAuth signing key", ex);
        }
    }

    private static KeyPair pair(String privatePkcs8, String publicX509) throws Exception {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicX509))),
                factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(privatePkcs8))));
    }

    private static String section(String pem, String name) {
        String start = "-----BEGIN " + name + "-----";
        String end = "-----END " + name + "-----";
        int from = pem.indexOf(start);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalStateException("OAuth key file is missing " + name);
        }
        return pem.substring(from + start.length(), to).replaceAll("\\s", "");
    }

    record Access(String accessToken, String refreshToken, long expiresIn) {
    }

    private record Client(List<String> redirects) {
    }

    private record Code(String clientId, String redirect, String challenge, String subject, long expires) {
    }

    private record Refresh(String subject, long expires) {
    }

}
