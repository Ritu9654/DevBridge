package com.devbridge.api;

import com.devbridge.apps.AppDeleteExecutorService;
import com.devbridge.apps.AppSqlExecutorService;
import com.devbridge.apps.AppSqlFetchService;
import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.apps.AppSqlPlanService;
import com.devbridge.apps.DeleteJournal;
import com.devbridge.apps.ImportJournal;
import com.devbridge.apps.ImportPlan;
import com.devbridge.datamodel.DataModel;
import com.devbridge.datamodel.DataModelService;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
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

    public AppImportController(ProfileService profileService,
                               ActiveProfileHolder activeProfileHolder,
                               DataModelService dataModelService,
                               AppSqlFetchService fetchService,
                               AppSqlPlanService planService,
                               AppSqlExecutorService executorService,
                               AppDeleteExecutorService deleteExecutor) {
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.dataModelService = dataModelService;
        this.fetchService = fetchService;
        this.planService = planService;
        this.executorService = executorService;
        this.deleteExecutor = deleteExecutor;
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
            FetchResult fetch = fetchService.fetch(
                    p.profile, p.sourceEnv, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), p.profile.referenceTables());
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
            // of this app (by the same lookup column), delete it and its full
            // tree before we insert. Turns Import into "replace" semantics
            // and prevents FK/UK collisions from prior partial runs.
            String cleanupSummary = preImportCleanup(p.profile, p.targetEnv, p.dataModel,
                    filterCol, req.appId().trim());

            FetchResult fetch = fetchService.fetch(
                    p.profile, p.sourceEnv, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), p.profile.referenceTables());
            ImportJournal journal = executorService.execute(
                    fetch, p.profile, p.sourceEnv, p.targetEnv, req.appId().trim());

            // Prepend the cleanup summary to the journal's error message field
            // so the UI + toast can show what pre-cleanup did. Doesn't override
            // an actual failure message — appended below the failure if any.
            if (cleanupSummary != null && !cleanupSummary.isBlank()) {
                String existing = journal.errorMessage();
                String combined = existing == null || existing.isBlank()
                        ? cleanupSummary
                        : cleanupSummary + " | " + existing;
                journal = journal.withStatus(journal.status(), combined, journal.endedAt());
            }
            return ResponseEntity.ok(journal);
        } catch (PreCleanupFailedException e) {
            return ResponseEntity.internalServerError().body(errorBody(
                    "Pre-import cleanup failed — cannot safely proceed. " + e.getMessage()));
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
        FetchResult targetFetch;
        try {
            targetFetch = fetchService.fetch(profile, targetEnv, dataModel,
                    profile.rootTableName(), filterCol, appIdentifier,
                    profile.referenceTables());
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
