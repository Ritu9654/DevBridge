package com.devbridge.fawb;

import com.devbridge.auth.AuthTokenHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for talking to any FAWB environment. Injects the target
 * profile's bearer token (per-profile, keyed on profileId).
 * Never follows redirects (a login-page redirect would otherwise return 200
 * and mask real auth failures). Never logs the token value.
 */
@Component
public class FawbClient {

    private static final Logger log = LoggerFactory.getLogger(FawbClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AuthTokenHolder tokenHolder;
    private final HttpClient http;

    public FawbClient(AuthTokenHolder tokenHolder) {
        this.tokenHolder = tokenHolder;
        // Permissive TLS — several FICO endpoints (e.g. WFS token) present certs
        // signed by private CAs not in Java's default truststore. See
        // PermissiveHttpClients for the security-posture rationale.
        this.http = PermissiveHttpClients.build(CONNECT_TIMEOUT);
    }

    /**
     * GET the given URL using the token slot for the given profile.
     * If the slot has no token, the request is sent without an Authorization header
     * — callers should typically check {@link #hasToken(String)} first.
     */
    public HttpResponse<String> get(String url, String profileId) throws Exception {
        String token = tokenHolder.getToken(profileId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        log.debug("GET {} (profile={})", url, profileId);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * POST a JSON body to the given URL using the token slot for the given profile.
     * Body must already be a serialised JSON string. Sets Content-Type: application/json.
     * Never logs the body (it may contain PII); only the URL is logged at debug level.
     */
    public HttpResponse<String> post(String url, String jsonBody, String profileId) throws Exception {
        String token = tokenHolder.getToken(profileId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        log.info("POST {} (profile={}, bytes={})", url, profileId,
                jsonBody != null ? jsonBody.length() : 0);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * POST a multipart/form-data request with a single file part. Content-Type
     * of the file part is explicitly set — critical because FAWB's CSV import
     * endpoint rejects {@code application/octet-stream} (the default when
     * curl/Postman can't infer). Java's built-in HttpClient has no multipart
     * helper, so we build the body byte-array manually with a UUID boundary.
     *
     * @param url             target URL (e.g. {@code .../services/CaseManager/CaseDatum/import})
     * @param fieldName       multipart field name (e.g. {@code "file"})
     * @param filename        filename to declare in the part header
     * @param fileContentType MIME type of the file part (e.g. {@code "text/csv"})
     * @param fileBytes       payload bytes
     * @param profileId       auth token slot
     */
    public HttpResponse<String> postMultipart(String url, String fieldName, String filename,
                                               String fileContentType, byte[] fileBytes,
                                               String profileId) throws Exception {
        String token = tokenHolder.getToken(profileId);
        String boundary = "----DevBridgeBoundary" + java.util.UUID.randomUUID().toString().replace("-", "");
        String crlf = "\r\n";

        String header = "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"" + crlf
                + "Content-Type: " + fileContentType + crlf
                + crlf;
        String footer = crlf + "--" + boundary + "--" + crlf;

        byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        log.info("POST-multipart {} (profile={}, bytes={}, part-content-type={})",
                url, profileId, body.length, fileContentType);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * DELETE the given URL using the token slot for the given profile. Used
     * by rollback for REST-CRUD-inserted rows: {@code DELETE /services/{db}/{Entity}/{id}}.
     */
    public HttpResponse<String> delete(String url, String profileId) throws Exception {
        String token = tokenHolder.getToken(profileId);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .DELETE();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        log.info("DELETE {} (profile={})", url, profileId);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public boolean hasToken(String profileId) {
        return tokenHolder.isSet(profileId);
    }
}
