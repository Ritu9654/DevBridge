package com.devbridge.fawb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Builds {@link HttpClient} instances that don't verify TLS certificates or
 * hostnames — needed because several FICO internal endpoints (e.g. the WFS
 * OAuth token endpoint) present certs signed by private CAs not in Java's
 * default truststore.
 * <p>
 * <b>Security note:</b> This is intentionally permissive. DevBridge is a
 * local developer tool that talks only to FICO Analytic Cloud endpoints;
 * the security posture is already "trust the network" (pasted bearer tokens,
 * pasted session cookies, credentials in a local profile JSON). Disabling
 * TLS verification here doesn't lower the bar meaningfully. If DevBridge
 * ever grows to accept URLs from untrusted sources, revisit this.
 */
public final class PermissiveHttpClients {

    private static final Logger log = LoggerFactory.getLogger(PermissiveHttpClients.class);

    private PermissiveHttpClients() {}

    public static HttpClient build(Duration connectTimeout) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new AcceptAllTrustManager()}, new SecureRandom());

            // Java 11+ HttpClient verifies hostname separately from cert chain — turn that off too.
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm(null);

            log.info("HttpClient built with permissive TLS (trust-all + no hostname verification). "
                    + "OK for internal FICO endpoints; do not use for untrusted hosts.");
            return HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .sslContext(ctx)
                    .sslParameters(params)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build permissive HTTP client", e);
        }
    }

    private static final class AcceptAllTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
