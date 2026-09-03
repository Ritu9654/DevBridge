package com.devbridge.sql;

import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.ProjectProfile;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Builds a FAWB SQL execution request for a given profile + env + SQL,
 * then delegates the HTTP call to {@link FawbClient}. Same runtime endpoint
 * works for both envs — only the base URL differs:
 * <pre>{@code GET {baseUrl}/services/schema/executeSQLs?db=X&dbCommands=Y}</pre>
 * Bearer auth via {@code WM_AUTH_TOKEN} in both cases.
 */
@Service
public class SqlService {

    private final FawbClient client;

    public SqlService(FawbClient client) {
        this.client = client;
    }

    public ExecuteResult execute(ProjectProfile profile, String env, String sql) throws Exception {
        if (env == null) throw new IllegalArgumentException("env is required (sandbox or design)");
        String envLower = env.toLowerCase();
        String baseUrl;
        String db;
        String dbFieldHint;
        // Design and sandbox usually have different underlying DB names — sandbox
        // provisions its own (e.g. CaseManagerJ17__1) while design has its own
        // schema (e.g. usrzzifdsi3l). Fields are per-env; both fall back to
        // dbServiceName for older / same-name projects.
        if (envLower.equals("design")) {
            baseUrl = requireNonBlank(profile.designBaseUrl(),
                    "Profile has no design base URL. Edit the profile and add it.");
            db = firstNonBlank(profile.sqlDbName(), profile.dbServiceName());
            dbFieldHint = "sqlDbName";
        } else if (envLower.equals("sandbox")) {
            baseUrl = requireNonBlank(profile.sandboxBaseUrl(),
                    "Profile has no sandbox base URL. Edit the profile and add it.");
            db = firstNonBlank(profile.sandboxSqlDbName(), profile.dbServiceName());
            dbFieldHint = "sandboxSqlDbName";
        } else {
            throw new IllegalArgumentException(
                    "env must be 'sandbox' or 'design', got: " + env);
        }
        if (db == null || db.isBlank()) {
            throw new IllegalStateException(
                    "Profile has no SQL DB name for env '" + env + "'. Set " + dbFieldHint
                            + " (or dbServiceName as fallback).");
        }
        String url = trimTrailingSlash(baseUrl)
                + "/services/schema/executeSQLs"
                + "?db=" + urlEncode(db)
                + "&dbCommands=" + urlEncode(sql);
        // Log the SQL to console AND append to a diagnostic file. File approach
        // works regardless of how DevBridge is launched (IDE, jar, service).
        String logSql = sql.length() > 500 ? sql.substring(0, 500) + "…(+" + (sql.length() - 500) + " chars)" : sql;
        org.slf4j.LoggerFactory.getLogger(SqlService.class).info("SQL to {}: {}", env, logSql);
        HttpResponse<String> res = client.get(url, profile.id());
        String logRes = res.body() != null && res.body().length() > 500
                ? res.body().substring(0, 500) + "…"
                : res.body();
        org.slf4j.LoggerFactory.getLogger(SqlService.class).info("SQL response status={} body={}", res.statusCode(), logRes);
        appendToSqlDiagnosticLog(profile.id(), env, sql, res.statusCode(), res.body());
        return new ExecuteResult(url, res.statusCode(), res.body());
    }

    /** Append this SQL call to a per-profile diagnostic file. Never throws. */
    private static void appendToSqlDiagnosticLog(String profileId, String env, String sql,
                                                  int status, String body) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path dir = java.nio.file.Paths.get(home, ".devbridge", "imports",
                    profileId != null ? profileId : "unknown");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("sql-diagnostic.log");
            String entry = "[" + java.time.Instant.now() + "] env=" + env + "\n"
                    + "  SQL: " + (sql.length() > 800 ? sql.substring(0, 800) + "…(+" + (sql.length() - 800) + ")" : sql) + "\n"
                    + "  STATUS: " + status + "\n"
                    + "  BODY: " + (body != null && body.length() > 800 ? body.substring(0, 800) + "…" : body) + "\n\n";
            java.nio.file.Files.writeString(file, entry,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // diagnostic only — never let this interfere with the real request path
        }
    }

    public record ExecuteResult(String url, int status, String body) {}

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String requireNonBlank(String value, String errorMessage) {
        if (value == null || value.isBlank()) throw new IllegalStateException(errorMessage);
        return value.trim();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
