package com.devbridge.api;

import com.devbridge.apps.AppDeleteExecutorService;
import com.devbridge.apps.AppSqlExecutorService;
import com.devbridge.apps.AppSqlFetchService;
import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.apps.AppSqlPlanService;
import com.devbridge.apps.DeleteJournal;
import com.devbridge.apps.ImportJournal;
import com.devbridge.apps.ImportJournalStore;
import com.devbridge.apps.ImportPlan;
import com.devbridge.apps.SqlBuilder;
import com.devbridge.datamodel.DataModel;
import com.devbridge.datamodel.DataModelService;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
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

/**
 * REST endpoints for Module 2 (App Import). All heavy lifting is delegated:
 * {@link AppSqlFetchService} pulls rows from the source env via SQL SELECTs,
 * {@link AppSqlPlanService} builds a preview plan, {@link AppSqlExecutorService}
 * writes the rows to the target env via SQL INSERTs. The controller only
 * validates input, resolves the active profile + dataModel, and shapes responses.
 */
@RestController
@RequestMapping("/api/apps")
public class AppImportController {

    private static final Logger log = LoggerFactory.getLogger(AppImportController.class);

    private final ProfileService profileService;
    private final ActiveProfileHolder activeProfileHolder;
    private final DataModelService dataModelService;
    private final AppSqlFetchService fetchService;
    private final AppSqlPlanService planService;
    private final AppSqlExecutorService executorService;
    private final AppDeleteExecutorService deleteExecutor;
    private final SqlService sqlService;
    private final ImportJournalStore journalStore;

    public AppImportController(ProfileService profileService,
                               ActiveProfileHolder activeProfileHolder,
                               DataModelService dataModelService,
                               AppSqlFetchService fetchService,
                               AppSqlPlanService planService,
                               AppSqlExecutorService executorService,
                               AppDeleteExecutorService deleteExecutor,
                               SqlService sqlService,
                               ImportJournalStore journalStore) {
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.dataModelService = dataModelService;
        this.fetchService = fetchService;
        this.planService = planService;
        this.executorService = executorService;
        this.deleteExecutor = deleteExecutor;
        this.sqlService = sqlService;
        this.journalStore = journalStore;
    }

    /**
     * Live-progress endpoint: returns the most recent import journal on disk
     * for the given profile. Client polls this every ~1s during an execute
     * request so the user sees per-table progress as it happens.
     */
    @org.springframework.web.bind.annotation.GetMapping("/import-journal/latest")
    public ResponseEntity<?> latestJournal(
            @org.springframework.web.bind.annotation.RequestParam("profileId") String profileId) {
        return journalStore.readLatest(profileId)
                .map(j -> ResponseEntity.ok((Object) j))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Compute a dry-run import plan for one app.
     * <p>
     * Fetches rows via SQL SELECTs from the source env (BFS the FK graph from
     * {@code rootTableName} with the supplied appId). Returns an {@link ImportPlan}
     * summarising the tables + row counts + insert order. No writes to any env.
     */
    @PostMapping("/import-plan")
    public ResponseEntity<?> plan(@RequestBody ImportRequest req) {
        Preconditions p = preflight(req);
        if (p.errorBody != null) return ResponseEntity.status(p.errorStatus).body(p.errorBody);

        String filterCol = resolveFilterColumn(req, p.profile);
        try {
            // Refuse plan-build if the app already exists in the target env.
            // Building the plan (which shows source-side counts) is misleading
            // when the actual import would be blocked; better to surface the
            // conflict at preview time so the user doesn't waste time.
            long existingRows = countExistingInTarget(p.profile, p.targetEnv, p.dataModel,
                    filterCol, req.appId().trim());
            if (existingRows > 0) {
                Map<String, Object> body = errorBody(
                        "This app already exists in the target environment (" + p.targetEnv
                                + "). Preview refused. Delete the existing instance via the "
                                + "Delete App section, then re-preview.");
                body.put("code", "APP_ALREADY_EXISTS");
                body.put("existingRowCount", existingRows);
                body.put("suggestedAction", "delete-first");
                return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(body);
            }

            FetchResult fetch = fetchService.fetch(
                    p.profile, p.sourceEnv, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), p.profile.referenceTables(),
                    /*scanOrphans=*/ true);
            ImportPlan plan = planService.build(fetch, p.profile, p.sourceEnv, p.targetEnv,
                    p.dataModel, req.appId().trim());
            return ResponseEntity.ok(plan);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("Import plan failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    /**
     * Execute an import plan. WRITES to the target env. Requires
     * {@code confirm=true} and {@code confirmToken == appId} (server-side echo
     * of the client's typed confirmation).
     */
    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody ImportRequest req) {
        Preconditions p = preflight(req);
        if (p.errorBody != null) return ResponseEntity.status(p.errorStatus).body(p.errorBody);
        if (!Boolean.TRUE.equals(req.confirm())) {
            return ResponseEntity.badRequest().body(errorBody(
                    "'confirm' must be true. Execution refused."));
        }
        if (req.confirmToken() == null || !req.confirmToken().trim().equals(req.appId().trim())) {
            return ResponseEntity.badRequest().body(errorBody(
                    "'confirmToken' must exactly equal the source appId. Execution refused."));
        }

        String filterCol = resolveFilterColumn(req, p.profile);
        try {
            // Pre-import auto-cleanup: if the target already has an instance
            // of this app (by the same lookup column), refuse — force the user
            // to explicitly delete it first via the Delete App section.
            // Prior behaviour was to auto-cleanup, but that hid state changes;
            // explicit is safer.
            long existingRows = countExistingInTarget(p.profile, p.targetEnv, p.dataModel,
                    filterCol, req.appId().trim());
            if (existingRows > 0) {
                Map<String, Object> body = errorBody(
                        "This app already exists in the target environment (" + p.targetEnv
                                + "). Import refused. Delete the existing instance via the "
                                + "Delete App section, then re-import.");
                body.put("code", "APP_ALREADY_EXISTS");
                body.put("existingRowCount", existingRows);
                body.put("suggestedAction", "delete-first");
                return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(body);
            }

            FetchResult fetch = fetchService.fetch(
                    p.profile, p.sourceEnv, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), p.profile.referenceTables(),
                    /*scanOrphans=*/ true);
            ImportJournal journal = executorService.execute(
                    fetch, p.profile, p.sourceEnv, p.targetEnv, req.appId().trim());
            return ResponseEntity.ok(journal);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("Import execute failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    /**
     * If the target env already holds an instance of this app (by the same
     * lookup column), run Delete App against it before the import proceeds.
     * <p>
     * The lookup column matters here: when it's a stable natural key (like
     * {@code applicationNumber}) the target row we find is the same logical
     * app in a different env. When it's the root PK ({@code id}) and target's
     * ids differ from source, this may find nothing (target has no app with
     * that id) or, in the pathological case, find an UNRELATED app that
     * happens to share that id. That's the user's responsibility — the
     * safer default is to look up by a natural key.
     *
     * @return a human-readable summary of what was deleted, or null if
     *   nothing existed / cleanup was a no-op.
     * @throws PreCleanupFailedException when the delete phase itself fails
     *   part-way; we never proceed to import in that case.
     */
    private String preImportCleanup(ProjectProfile profile, String targetEnv, DataModel dataModel,
                                    String filterCol, String appIdentifier) throws Exception {
        // Fast-path: if the root table has 0 matching rows, skip the whole
        // reverse-graph cleanup walk. Common for demo runs into a fresh sandbox
        // (or when the same app id was already deleted). Saves 2-4 min because
        // fetch() otherwise runs one SELECT per FK-reachable table.
        String rootTable = profile.rootTableName();
        String effectiveFilter = filterCol != null && !filterCol.isBlank()
                ? filterCol : firstPk(dataModel, rootTable);
        if (effectiveFilter != null && rootTable != null) {
            try {
                String countSql = "SELECT COUNT(*) AS n FROM `" + rootTable + "` WHERE `"
                        + effectiveFilter + "` = " + SqlBuilder.literal(appIdentifier);
                var res = sqlService.execute(profile, targetEnv, countSql);
                if (res.status() >= 200 && res.status() < 300) {
                    long n = extractCount(res.body());
                    if (n == 0) {
                        log.info("Pre-cleanup fast-path: target has 0 rows in {} where {}={} — skipping full FK walk.",
                                rootTable, effectiveFilter, appIdentifier);
                        return null;
                    }
                }
            } catch (Exception e) {
                // Fall through to the normal fetch path — better to be slow than to skip incorrectly
                log.debug("Pre-cleanup fast-path COUNT failed ({}), falling back to full walk.", e.getMessage());
            }
        }

        FetchResult targetFetch;
        try {
            // Scan orphans too — if a previous partial import left orphan rows
            // in the target, we need to delete them before re-importing.
            // Symmetric with the Delete App flow.
            targetFetch = fetchService.fetch(profile, targetEnv, dataModel,
                    profile.rootTableName(), filterCol, appIdentifier,
                    profile.referenceTables(), /*scanOrphans=*/ true);
        } catch (Exception e) {
            log.warn("Pre-import cleanup fetch failed against {} (filterCol={}): {}",
                    targetEnv, filterCol, e.getMessage());
            return null;   // no cleanup possible; proceed with import — it may still succeed
        }
        if (targetFetch.totalRowCount() == 0) {
            log.info("Pre-import cleanup: target has no existing rows for {}={} — nothing to delete.",
                    filterCol == null ? "(root PK)" : filterCol, appIdentifier);
            return null;
        }
        log.info("Pre-import cleanup: target has {} row(s) across {} table(s) for {}={} — deleting first.",
                targetFetch.totalRowCount(), targetFetch.rowsByTable().size(),
                filterCol == null ? "(root PK)" : filterCol, appIdentifier);
        DeleteJournal deleteJournal = deleteExecutor.execute(
                targetFetch, profile, targetEnv, appIdentifier);
        if (!"success".equals(deleteJournal.status())) {
            throw new PreCleanupFailedException(
                    "Delete journal status=" + deleteJournal.status()
                            + "; error=" + (deleteJournal.errorMessage() == null ? "(none)" : deleteJournal.errorMessage()));
        }
        int deleted = 0;
        if (deleteJournal.entries() != null) {
            for (DeleteJournal.Entry e : deleteJournal.entries()) {
                if ("deleted".equals(e.result())) deleted++;
            }
        }
        return "Pre-cleanup removed " + deleted + " row(s) from target.";
    }

    /**
     * Count rows on the target env for the given filter column + value.
     * Used before Execute Import to detect an existing instance of the app
     * and refuse rather than silently double-write. Returns 0 if the check
     * couldn't run (permissive — better to attempt import than block on a
     * transient probe failure). Returns -1 if the check ran but couldn't
     * parse the response.
     */
    private long countExistingInTarget(ProjectProfile profile, String targetEnv, DataModel dataModel,
                                        String filterCol, String appIdentifier) {
        String rootTable = profile.rootTableName();
        String effectiveFilter = filterCol != null && !filterCol.isBlank()
                ? filterCol : firstPk(dataModel, rootTable);
        if (effectiveFilter == null || rootTable == null) return 0;
        try {
            String countSql = "SELECT COUNT(*) AS n FROM `" + rootTable + "` WHERE `"
                    + effectiveFilter + "` = " + SqlBuilder.literal(appIdentifier);
            var res = sqlService.execute(profile, targetEnv, countSql);
            if (res.status() < 200 || res.status() >= 300) return 0;
            long n = extractCount(res.body());
            return n < 0 ? 0 : n;
        } catch (Exception e) {
            log.debug("countExistingInTarget probe failed ({}); permitting import to proceed.", e.getMessage());
            return 0;
        }
    }

    /** First PK column of the given table in the dataModel, or null. */
    private static String firstPk(DataModel dataModel, String tableName) {
        if (dataModel == null || tableName == null) return null;
        for (DataModel.Table t : dataModel.tables()) {
            if (t == null || t.name() == null) continue;
            if (!t.name().equalsIgnoreCase(tableName)) continue;
            if (t.primaryKey() == null || t.primaryKey().columns() == null) return null;
            var cols = t.primaryKey().columns();
            return cols.isEmpty() ? null : cols.get(0);
        }
        return null;
    }

    /**
     * Parse the number out of a FAWB executeSQLs response for a COUNT(*) query.
     * The response wraps the row JSON as a string inside a JSON envelope, so
     * the "n" key appears with either literal or backslash-escaped quotes:
     *   Escaped form (typical): [{"sql":null,"response":"{\"n\":42}"}]
     *   Non-escaped form:       [{"sql":null,"response":{"n":42}}]
     * The regex accepts both. Returns -1 on parse failure so the caller can
     * distinguish "genuinely 0 rows" from "couldn't parse" and fall back to
     * the full walk (safer than mistakenly skipping cleanup).
     */
    private static long extractCount(String body) {
        if (body == null || body.isBlank()) return -1;
        // \\?"n\\?"  — optional backslash before each quote; matches both
        // escaped and unescaped forms of the JSON key "n".
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\\\?\"n\\\\?\"\\s*:\\s*(-?\\d+)").matcher(body);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); }
            catch (NumberFormatException ignore) { return -1; }
        }
        return -1;
    }

    /**
     * Priority for the root-table filter column: request-body override →
     * profile.rootPkFilterColumn → null (fetch service falls back to the
     * root table's PK).
     */
    private static String resolveFilterColumn(ImportRequest req, ProjectProfile profile) {
        if (req != null && req.lookupColumn() != null && !req.lookupColumn().isBlank()) {
            return req.lookupColumn().trim();
        }
        return profile.rootPkFilterColumn();
    }

    /** Thrown when pre-import cleanup runs but leaves the target dirty; import must not proceed. */
    private static class PreCleanupFailedException extends RuntimeException {
        PreCleanupFailedException(String msg) { super(msg); }
    }

    /* ---------- preflight ---------- */

    private Preconditions preflight(ImportRequest req) {
        Preconditions p = new Preconditions();
        String profileId = activeProfileHolder.getActiveProfileId();
        if (profileId == null) {
            return p.err(400, "No active profile — activate one first.");
        }
        Optional<ProjectProfile> profile = profileService.findById(profileId);
        if (profile.isEmpty()) {
            return p.err(400, "Active profile not found.");
        }
        p.profile = profile.get();
        if (p.profile.rootTableName() == null || p.profile.rootTableName().isBlank()) {
            return p.err(400, "Profile has no 'Root table name' set. Edit the profile and set it "
                    + "(e.g., 'CASE_HEADER' for CaseManager, 'APPLICATION' for Core).");
        }
        Optional<DataModel> maybeModel = dataModelService.get(profileId);
        if (maybeModel.isEmpty()) {
            return p.err(400,
                    "No dataModel uploaded for this profile. Edit the profile and upload it before importing.");
        }
        p.dataModel = maybeModel.get();
        if (req == null || req.appId() == null || req.appId().isBlank()) {
            return p.err(400, "'appId' is required.");
        }
        p.sourceEnv = req.sourceEnv() != null ? req.sourceEnv() : "design";
        p.targetEnv = req.targetEnv() != null ? req.targetEnv() : "sandbox";
        if (p.sourceEnv.equalsIgnoreCase(p.targetEnv)) {
            // Not fatal — plan will surface a warning too, but flag early
            log.info("Source and target env are the same ({}) — user will see a duplicate-rows warning.",
                    p.sourceEnv);
        }
        return p;
    }

    /** Small struct to carry validated request state (or an error) between preflight and handler. */
    private static final class Preconditions {
        ProjectProfile profile;
        DataModel dataModel;
        String sourceEnv;
        String targetEnv;
        int errorStatus;
        Map<String, Object> errorBody;

        Preconditions err(int status, String msg) {
            this.errorStatus = status;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", false);
            m.put("error", msg);
            this.errorBody = m;
            return this;
        }
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }

    /**
     * Unified request body for both /import-plan and /execute.
     *
     * @param appId identifier value (source-side PK value or a stable business
     *   number like applicationNumber, per {@code lookupColumn}).
     * @param lookupColumn optional root-table column name to filter by.
     *   Empty/null → use profile's rootPkFilterColumn (defaults to root PK).
     *   When set to a stable natural key like {@code applicationNumber},
     *   pre-import auto-cleanup will also use it to identify and remove any
     *   existing instance in the target env before inserting the fresh data.
     */
    public record ImportRequest(
            String appId,
            String lookupColumn,
            String sourceEnv,
            String targetEnv,
            Boolean confirm,
            String confirmToken
    ) {}
}
