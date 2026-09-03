package com.devbridge.apps;

import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.ProjectProfile;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Fetches one app's composite JSON tree from FAWB's facade endpoint.
 * <p>
 * <b>Advisory role only.</b> The primary import path (fetch + plan + execute)
 * is FK-graph driven and never depends on this service. This exists solely for
 * the {@link FacadeValidator} cross-check — comparing what the facade endpoint
 * reports as belonging to an app vs. what the FK-walk discovers.
 * <p>
 * If the profile lacks facade config, this service is skipped entirely and the
 * cross-check simply produces no output.
 *
 * <p>URL pattern:
 * <pre>
 *   GET {baseUrl}/services/{facadeServicePath}{facadeEndpoint}?{facadeQueryParam}={appId}
 * </pre>
 */
@Service
public class AppFacadeFetchService {

    private final FawbClient client;

    public AppFacadeFetchService(FawbClient client) {
        this.client = client;
    }

    /**
     * @return {@code null} if the profile has no facade config — no exception thrown,
     *         since the caller (cross-check) treats it as an optional feature.
     */
    public FetchResult fetch(ProjectProfile profile, String appId, String env) throws Exception {
        String baseUrl = resolveBaseUrl(profile, env);
        if (isBlank(baseUrl)
                || isBlank(profile.facadeServicePath())
                || isBlank(profile.facadeEndpoint())
                || isBlank(profile.facadeQueryParam())
                || isBlank(appId)) {
            return null;
        }
        String url = trimTrailingSlash(baseUrl)
                + "/services/" + profile.facadeServicePath()
                + ensureLeadingSlash(profile.facadeEndpoint())
                + "?" + urlEncode(normaliseParamName(profile.facadeQueryParam()))
                + "=" + urlEncode(appId);
        HttpResponse<String> res = client.get(url, profile.id());
        return new FetchResult(url, res.statusCode(), res.body());
    }

    private static String resolveBaseUrl(ProjectProfile p, String env) {
        if (env == null) return p.designBaseUrl();
        return switch (env.toLowerCase()) {
            case "sandbox" -> p.sandboxBaseUrl();
            case "design"  -> p.designBaseUrl();
            default -> null;
        };
    }

    private static String normaliseParamName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int cut = s.length();
        for (char c : new char[]{'=', '?', '&'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut).trim();
        while (!s.isEmpty() && (s.charAt(0) == '?' || s.charAt(0) == '&')) s = s.substring(1).trim();
        return s;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String trimTrailingSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String ensureLeadingSlash(String s) { return s.startsWith("/") ? s : "/" + s; }
    private static String urlEncode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    public record FetchResult(String url, int status, String body) {}
}
