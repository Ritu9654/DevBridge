package com.devbridge.fawb;

import com.devbridge.auth.AuthTokenHolder;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Probes each configured env URL on a profile to verify reachability + auth.
 * Uses that profile's dedicated token slot (from {@link AuthTokenHolder}).
 * The probe hits a well-known WaveMaker security endpoint that requires auth,
 * so the HTTP status alone tells us whether the token was accepted.
 */
@Service
public class TestConnectionService {

    private static final Logger log = LoggerFactory.getLogger(TestConnectionService.class);
    private static final String PROBE_PATH = "/services/security/user";

    private final FawbClient client;
    private final AuthTokenHolder tokenHolder;

    public TestConnectionService(FawbClient client, AuthTokenHolder tokenHolder) {
        this.client = client;
        this.tokenHolder = tokenHolder;
    }

    public List<TestConnectionResult> test(ProjectProfile profile) {
        List<TestConnectionResult> results = new ArrayList<>();

        if (profile.sandboxBaseUrl() != null && !profile.sandboxBaseUrl().isBlank()) {
            results.add(probe("sandbox", profile.sandboxBaseUrl(), profile.id()));
        }
        if (profile.designBaseUrl() != null && !profile.designBaseUrl().isBlank()) {
            results.add(probe("design", profile.designBaseUrl(), profile.id()));
        }
        if (results.isEmpty()) {
            results.add(TestConnectionResult.fail("(none)", 0,
                    "Profile has no base URLs configured — edit it and add at least one."));
        }
        return results;
    }

    private TestConnectionResult probe(String env, String baseUrl, String profileId) {
        if (!tokenHolder.isSet(profileId)) {
            return TestConnectionResult.fail(env, 0, "No token for this profile — paste one first.");
        }
        String url = trimTrailingSlash(baseUrl) + PROBE_PATH;
        try {
            HttpResponse<String> res = client.get(url, profileId);
            int status = res.statusCode();
            if (status >= 200 && status < 300) {
                return TestConnectionResult.ok(env, status, "Reachable and authorized.");
            }
            if (status == 401) {
                // Token expired or invalid — clear the slot so the sidebar reflects reality
                tokenHolder.clear(profileId);
                return TestConnectionResult.fail(env, status, "Token expired — paste a fresh WM_AUTH_TOKEN for this profile.");
            }
            if (status == 403) {
                return TestConnectionResult.fail(env, status, "Token accepted but missing the required role.");
            }
            if (status >= 300 && status < 400) {
                return TestConnectionResult.fail(env, status, "Redirected — token likely expired or not sent.");
            }
            if (status == 404) {
                return TestConnectionResult.fail(env, status, "Server reachable but the probe endpoint does not exist here.");
            }
            return TestConnectionResult.fail(env, status, "Unexpected HTTP status.");
        } catch (Exception e) {
            log.warn("Test connection failed for {} ({}): {}", env, url, e.getMessage());
            return TestConnectionResult.fail(env, 0,
                    e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "(no detail)"));
        }
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
