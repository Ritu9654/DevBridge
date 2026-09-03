package com.devbridge.auth;

import com.devbridge.fawb.PermissiveHttpClients;
import com.devbridge.profile.ProjectProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches a fresh FAWB bearer token via the OAuth2 client_credentials grant.
 * <p>
 * Uses the token URL + client ID + client secret configured on the profile
 * and stores the resulting token in {@link AuthTokenHolder}. Saves the user
 * from pasting {@code WM_AUTH_TOKEN} manually every 30 min.
 * <p>
 * Request shape (matches FICO's identity endpoint at
 * {@code /registration/rest/client/security/token}):
 * <pre>
 *   POST {tokenUrl}
 *   Content-Type: application/json
 *
 *   {"clientId": "...", "secret": "..."}
 * </pre>
 * Note the field is {@code secret}, not {@code clientSecret} or the OAuth2
 * standard {@code client_secret} — FICO's endpoint is not standard OAuth2.
 * No {@code grantType}, no Basic auth header — confirmed via Postman.
 * <p>
 * Response handling covers three body shapes seen across FICO endpoints:
 * <ul>
 *   <li>Plain string body (WFS): the whole body IS the token.</li>
 *   <li>JSON string node: token wrapped in quotes.</li>
 *   <li>JSON object: extract {@code access_token} / {@code accessToken} /
 *       {@code token} / {@code wm_auth_token} / {@code wmAuthToken}, including
 *       one level of nesting under {@code data} / {@code result} /
 *       {@code payload} / {@code response} wrappers.</li>
 * </ul>
 */
@Service
public class TokenFetchService {

    private static final Logger log = LoggerFactory.getLogger(TokenFetchService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final List<String> TOKEN_FIELDS = List.of(
            "access_token", "accessToken", "token", "wm_auth_token", "wmAuthToken");

    private final HttpClient http;
    private final ObjectMapper mapper;

    public TokenFetchService(ObjectMapper mapper) {
        this.mapper = mapper;
        // Permissive TLS — FICO's WFS token endpoint uses a private CA cert not
        // trusted by Java's default truststore. See PermissiveHttpClients.
        this.http = PermissiveHttpClients.build(CONNECT_TIMEOUT);
    }

    public FetchResult fetch(ProjectProfile profile) throws Exception {
        String tokenUrl = require(profile.tokenUrl(),
                "Profile has no tokenUrl. Edit the profile and set it (the OAuth2 token endpoint URL).");
        String clientId = require(profile.tokenClientId(),
                "Profile has no tokenClientId. Edit the profile and set it.");
        String clientSecret = require(profile.tokenClientSecret(),
                "Profile has no tokenClientSecret. Edit the profile and set it.");

        // FICO's registration/security/token endpoint uses the exact fields
        // {"clientId": "...", "secret": "..."} — confirmed via Postman. Not
        // standard OAuth2 naming; do not add clientSecret / grant_type / grantType
        // as they trigger "Invalid Client or Secret" or 400 errors.
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("clientId", clientId);
        bodyMap.put("secret", clientSecret);
        String body = mapper.writeValueAsString(bodyMap);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        log.info("Token fetch POST {} (profile={})", tokenUrl, profile.id());
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        int status = res.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Token endpoint returned HTTP " + status + ". Body: " + res.body());
        }

        String rawBody = res.body() == null ? "" : res.body().trim();
        if (rawBody.isEmpty()) {
            throw new IllegalStateException("Token endpoint responded 2xx with an empty body.");
        }

        // FICO's endpoint returns the token as a plain string in the body (no JSON
        // envelope). Fall back to that when the body isn't parseable as JSON.
        if (!looksLikeJson(rawBody)) {
            return new FetchResult(stripSurroundingQuotes(rawBody), null);
        }

        JsonNode json;
        try {
            json = mapper.readTree(rawBody);
        } catch (Exception e) {
            // Looked JSON-ish but wasn't — still treat as plain token if it's a single line
            if (!rawBody.contains("\n") && rawBody.length() < 4096) {
                return new FetchResult(stripSurroundingQuotes(rawBody), null);
            }
            throw new IllegalStateException("Token endpoint returned non-JSON response: " + rawBody);
        }

        // JSON string node — same as plain body but wrapped in quotes
        if (json.isTextual()) {
            String v = json.asText();
            if (v != null && !v.isBlank()) return new FetchResult(v.trim(), null);
        }

        String token = extractToken(json);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Token endpoint responded 2xx but no access_token/token field found. Body: " + rawBody);
        }
        Long expiresIn = json.hasNonNull("expires_in") ? json.get("expires_in").asLong() : null;
        return new FetchResult(token, expiresIn);
    }

    private static boolean looksLikeJson(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        return c == '{' || c == '[' || c == '"';
    }

    private static String stripSurroundingQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * Look for the token at the top level first, then peek one level deep into
     * common wrapper keys ({@code data}, {@code result}, {@code payload}) — FICO
     * services often wrap responses like {@code {"data": {"accessToken": "..."}}}.
     */
    private static String extractToken(JsonNode json) {
        String top = extractTokenFromNode(json);
        if (top != null) return top;
        for (String wrapper : List.of("data", "result", "payload", "response")) {
            if (json.has(wrapper) && json.get(wrapper).isObject()) {
                String nested = extractTokenFromNode(json.get(wrapper));
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String extractTokenFromNode(JsonNode node) {
        for (String field : TOKEN_FIELDS) {
            if (node.hasNonNull(field) && node.get(field).isTextual()) {
                String v = node.get(field).asText();
                if (v != null && !v.isBlank()) return v;
            }
        }
        return null;
    }

    private static String require(String v, String msg) {
        if (v == null || v.isBlank()) throw new IllegalStateException(msg);
        return v.trim();
    }

    public record FetchResult(String token, Long expiresInSeconds) {}
}
