package com.devbridge.api;

import com.devbridge.apps.SqlBuilder;
import com.devbridge.auth.AuthTokenHolder;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private static final Logger log = LoggerFactory.getLogger(SqlController.class);

    private final SqlService sqlService;
    private final ProfileService profileService;
    private final ActiveProfileHolder activeProfileHolder;
    private final AuthTokenHolder tokenHolder;
    private final ObjectMapper mapper;

    public SqlController(SqlService sqlService,
                         ProfileService profileService,
                         ActiveProfileHolder activeProfileHolder,
                         AuthTokenHolder tokenHolder,
                         ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.tokenHolder = tokenHolder;
        this.mapper = mapper;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody ExecuteRequest req) {
        String profileId = activeProfileHolder.getActiveProfileId();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(errorBody("No active profile — activate one first."));
        }
        Optional<ProjectProfile> profile = profileService.findById(profileId);
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("Active profile not found."));
        }
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            return ResponseEntity.badRequest().body(errorBody("SQL text is required."));
        }

        try {
            ExecuteResult res = sqlService.execute(profile.get(), req.env(), req.sql());
            int status = res.status();
            if (status >= 200 && status < 300) {
                JsonNode data = mapper.readTree(res.body());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("status", status);
                body.put("requestUrl", res.url());
                body.put("data", data);
                return ResponseEntity.ok(body);
            }
            if (status == 401) {
                // Bearer expired — clear the slot so the UI can prompt for a refresh.
                tokenHolder.clear(profileId);
                Map<String, Object> body = errorBody(status,
                        "Token expired — paste a fresh WM_AUTH_TOKEN for this profile.");
                body.put("requestUrl", res.url());
                return ResponseEntity.status(status).body(body);
            }
            Map<String, Object> body = errorWithBody(status, "FAWB returned HTTP " + status + ".", res.body());
            body.put("requestUrl", res.url());
            return ResponseEntity.status(status).body(body);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("SQL execution failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }

    private Map<String, Object> errorBody(int status, String msg) {
        Map<String, Object> m = errorBody(msg);
        m.put("status", status);
        return m;
    }

    private Map<String, Object> errorWithBody(int status, String msg, String body) {
        Map<String, Object> m = errorBody(status, msg);
        m.put("body", body);
        return m;
    }

    public record ExecuteRequest(String env, String sql) {}

    /**
     * Update a single cell of a single row.
     * Body: {@code {env, table, column, newValue, pk: {pkCol1: pkVal1, ...}}}.
     *
     * <p>Fires {@code UPDATE `table` SET `column` = <lit> WHERE `pk1` = <lit>
     * AND `pk2` = <lit>} — composite-PK safe. The client is responsible for
     * supplying every PK column so the WHERE clause is precise; if it can't,
     * it disables the button on its side. This endpoint refuses when
     * {@code pk} is empty precisely so we never emit a WHERE-less UPDATE.
     *
     * <p>Success returns HTTP 200 with the emitted SQL. Failure surfaces
     * FAWB's status + body so the UI shows the exact reason (constraint
     * violation, non-existent column, etc.) rather than a generic "failed".
     */
    @PostMapping("/update-cell")
    public ResponseEntity<?> updateCell(@RequestBody UpdateCellRequest req) {
        String profileId = activeProfileHolder.getActiveProfileId();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(errorBody("No active profile — activate one first."));
        }
        Optional<ProjectProfile> profile = profileService.findById(profileId);
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("Active profile not found."));
        }
        if (req == null || req.table() == null || req.table().isBlank()) {
            return ResponseEntity.badRequest().body(errorBody(
                    "Target table is required — the query's table couldn't be inferred."));
        }
        if (req.column() == null || req.column().isBlank()) {
            return ResponseEntity.badRequest().body(errorBody("Column to update is required."));
        }
        if (req.pk() == null || req.pk().isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody(
                    "Primary-key column(s) required — refusing to run a WHERE-less UPDATE."));
        }

        String sql;
        try {
            sql = buildUpdateCellSql(req.table(), req.column(), req.newValue(), req.pk());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        }

        try {
            ExecuteResult res = sqlService.execute(profile.get(), req.env(), sql);
            int status = res.status();
            if (status >= 200 && status < 300) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("status", status);
                body.put("requestUrl", res.url());
                body.put("sql", sql);
                // Some FAWB responses embed nested error payloads even under 2xx —
                // treat "SQLIntegrityConstraint..." / "error" markers in the body
                // as failure so the client doesn't misreport success.
                if (looksLikeEmbeddedError(res.body())) {
                    Map<String, Object> failBody = errorWithBody(status,
                            "FAWB returned 2xx but the response body indicates a failure.",
                            res.body());
                    failBody.put("requestUrl", res.url());
                    failBody.put("sql", sql);
                    return ResponseEntity.status(500).body(failBody);
                }
                body.put("responseBody", res.body());
                return ResponseEntity.ok(body);
            }
            if (status == 401) {
                tokenHolder.clear(profileId);
                Map<String, Object> body = errorBody(status,
                        "Token expired — paste a fresh WM_AUTH_TOKEN for this profile.");
                body.put("requestUrl", res.url());
                body.put("sql", sql);
                return ResponseEntity.status(status).body(body);
            }
            Map<String, Object> body = errorWithBody(status,
                    "FAWB returned HTTP " + status + ".", res.body());
            body.put("requestUrl", res.url());
            body.put("sql", sql);
            return ResponseEntity.status(status).body(body);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Map<String, Object> body = errorBody(e.getMessage());
            body.put("sql", sql);
            return ResponseEntity.badRequest().body(body);
        } catch (Exception e) {
            log.warn("update-cell execution failed for {}.{}", req.table(), req.column(), e);
            Map<String, Object> body = errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)"));
            body.put("sql", sql);
            return ResponseEntity.internalServerError().body(body);
        }
    }

    /**
     * Build {@code UPDATE `t` SET `col` = <lit> WHERE `pk1` = <lit> AND `pk2` = <lit>}.
     * Uses {@link SqlBuilder#ident} + {@link SqlBuilder#literal} so escaping
     * matches every other statement the runtime emits — no ad-hoc quoting.
     */
    static String buildUpdateCellSql(String table, String column, Object newValue,
                                     Map<String, Object> pk) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table is required");
        if (column == null || column.isBlank()) throw new IllegalArgumentException("column is required");
        if (pk == null || pk.isEmpty()) throw new IllegalArgumentException("pk is empty");
        StringBuilder where = new StringBuilder();
        boolean firstPk = true;
        for (Map.Entry<String, Object> e : pk.entrySet()) {
            String pkCol = e.getKey();
            if (pkCol == null || pkCol.isBlank()) continue;
            if (!firstPk) where.append(" AND ");
            where.append(SqlBuilder.ident(pkCol)).append(" = ")
                 .append(SqlBuilder.literal(e.getValue()));
            firstPk = false;
        }
        if (firstPk) throw new IllegalArgumentException("no non-blank PK column names");
        return "UPDATE " + SqlBuilder.ident(table)
                + " SET " + SqlBuilder.ident(column) + " = " + SqlBuilder.literal(newValue)
                + " WHERE " + where;
    }

    private static boolean looksLikeEmbeddedError(String body) {
        if (body == null || body.isBlank()) return false;
        String s = body.toLowerCase(java.util.Locale.ROOT);
        return s.contains("sqlintegrityconstraint")
                || s.contains("sqlsyntaxerror")
                || s.contains("dataintegrityviolation")
                || s.contains("\"error\":\"")
                || s.contains("\"errorcode\"");
    }

    public record UpdateCellRequest(
            String env,
            String table,
            String column,
            Object newValue,
            java.util.LinkedHashMap<String, Object> pk
    ) {}
}
