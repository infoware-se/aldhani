package se.infoware.domino.aldhani;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

interface KyberGet {
    String get(String pathAndQuery);
}

/** Kyber calls that send a body. Reads stay on {@link KyberGet}. */
interface KyberExchange extends KyberGet {
    String exchange(String method, String pathAndQuery, String body, String contentType);
}

final class KyberClient implements KyberExchange {
    private final AldhaniConfig config;
    private final HttpClient http;
    private volatile String bearer;
    private final Object loginLock = new Object();

    KyberClient(AldhaniConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(sslContext(config.insecureTls));
        if (config.insecureTls) {
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("");
            builder.sslParameters(parameters);
        }
        this.http = builder.build();
    }

    public String get(String pathAndQuery) {
        return send("GET", pathAndQuery, null, null, true);
    }

    public String exchange(String method, String pathAndQuery, String body, String contentType) {
        return send(method, pathAndQuery, body, contentType, true);
    }

    private String send(
            String method, String pathAndQuery, String body, String contentType, boolean retryOnUnauthorized) {
        if (Thread.currentThread().isInterrupted()) {
            throw new KyberException(0, "Kyber call was interrupted");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.kyberBaseUrl + pathAndQuery))
                .timeout(Duration.ofSeconds(120))
                .header("Accept", "application/json");
        if ("GET".equals(method)) {
            request.GET();
        } else if ("DELETE".equals(method)) {
            request.DELETE();
        } else {
            String type = contentType == null ? "application/json; charset=utf-8" : contentType;
            request.header("Content-Type", type);
            request.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        if (!pathAndQuery.startsWith("/api/v1/auth")) {
            request.header("Authorization", "Bearer " + token());
        }
        HttpResponse<String> response = send(request.build());
        if (response.statusCode() == 401 && retryOnUnauthorized && !pathAndQuery.startsWith("/api/v1/auth")) {
            synchronized (loginLock) {
                bearer = null;
            }
            return send(method, pathAndQuery, body, contentType, false);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new KyberException(response.statusCode(), clip(response.body()));
        }
        return response.body();
    }

    private String token() {
        String current = bearer;
        if (current != null) {
            return current;
        }
        synchronized (loginLock) {
            if (bearer == null) {
                bearer = login();
            }
            return bearer;
        }
    }

    private String login() {
        Map<String, Object> body = Map.of(
                "username", config.kyberUser,
                "password", config.kyberPassword);
        String response = send("POST", "/api/v1/auth", Json.write(body), "application/json; charset=utf-8", false);
        Map<String, Object> json = Json.object(Json.parse(response));
        for (String key : new String[] {"bearer", "token", "access_token"}) {
            String value = Json.string(json.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new KyberException(200, "Kyber auth response had no bearer");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KyberException(0, "Kyber call was interrupted");
        } catch (Exception ex) {
            throw new KyberException(0, "Kyber call failed: " + ex.getMessage());
        }
    }

    static String query(String key, String value) {
        return key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= 2000) {
            return body;
        }
        return body.substring(0, 2000);
    }

    private static SSLContext sslContext(boolean insecureTls) {
        try {
            if (!insecureTls) {
                return SSLContext.getDefault();
            }
            TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll}, new SecureRandom());
            return context;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build TLS context", ex);
        }
    }
}

final class KyberException extends RuntimeException {
    final int status;

    KyberException(int status, String message) {
        super(message);
        this.status = status;
    }
}
