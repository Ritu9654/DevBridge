package com.devbridge.api;

import com.devbridge.apps.AppDeleteExecutorService;
import com.devbridge.apps.AppDeletePlanService;
import com.devbridge.apps.AppSqlFetchService;
import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.apps.DeleteJournal;
import com.devbridge.apps.DeleteJournalStore;
import com.devbridge.apps.DeletePlan;
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
 * REST endpoints for Delete App. Mirrors {@link AppImportController} but with
 * a single fixed env (sandbox) — design is never nukable through this flow.
 * No FK remap and no rollback: delete is a straight reverse-topo walk.
 */
@RestController
@RequestMapping("/api/apps")
public class AppDeleteController {

    private static final Logger log = LoggerFactory.getLogger(AppDeleteController.class);
    private static final String DELETE_ENV = "sandbox";

    private final ProfileService profileService;
    private final ActiveProfileHolder activeProfileHolder;
    private final DataModelService dataModelService;
    private final AppSqlFetchService fetchService;
    private final AppDeletePlanService planService;
    private final AppDeleteExecutorService executorService;
    private final DeleteJournalStore journalStore;

    public AppDeleteController(ProfileService profileService,
                               ActiveProfileHolder activeProfileHolder,
                               DataModelService dataModelService,
                               AppSqlFetchService fetchService,
                               AppDeletePlanService planService,
                               AppDeleteExecutorService executorService,
                               DeleteJournalStore journalStore) {
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.dataModelService = dataModelService;
        this.fetchService = fetchService;
        this.planService = planService;
        this.executorService = executorService;
        this.journalStore = journalStore;
    }

    /**
     * Live-progress endpoint: returns the most recent delete journal for the
     * given profile. Client polls this every ~1s during an execute-delete
     * request so the user sees per-table progress as it happens.
     */
    @org.springframework.web.bind.annotation.GetMapping("/delete-journal/latest")
    public ResponseEntity<?> latestJournal(
            @org.springframework.web.bind.annotation.RequestParam("profileId") String profileId) {
        return journalStore.readLatest(profileId)
                .map(j -> ResponseEntity.ok((Object) j))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Compute a dry-run delete plan. Fetches rows from sandbox via the same
     * FK-graph walk import uses. Returns tables in reverse-topo (DELETE) order
     * with row counts. No writes.
     */
    @PostMapping("/delete-plan")
    public ResponseEntity<?> plan(@RequestBody DeleteRequest req) {
        Preconditions p = preflight(req);
        if (p.errorBody != null) return ResponseEntity.status(p.errorStatus).body(p.errorBody);

        String filterCol = resolveFilterColumn(req, p.profile);
        try {
            FetchResult fetch = fetchService.fetch(
                    p.profile, DELETE_ENV, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), java.util.List.of(),
                    /*scanOrphans=*/ true);
            DeletePlan plan = planService.build(fetch, p.profile, DELETE_ENV, req.appId().trim());
            return ResponseEntity.ok(plan);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("Delete plan failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    /**
     * Execute a delete. WRITES (removes rows) from sandbox. Requires
     * {@code confirm=true} and {@code confirmToken == appId} (client-echoed).
     */
    @PostMapping("/execute-delete")
    public ResponseEntity<?> execute(@RequestBody DeleteRequest req) {
        Preconditions p = preflight(req);
        if (p.errorBody != null) return ResponseEntity.status(p.errorStatus).body(p.errorBody);
        if (!Boolean.TRUE.equals(req.confirm())) {
            return ResponseEntity.badRequest().body(errorBody(
                    "'confirm' must be true. Deletion refused."));
        }
        if (req.confirmToken() == null || !req.confirmToken().trim().equals(req.appId().trim())) {
            return ResponseEntity.badRequest().body(errorBody(
                    "'confirmToken' must exactly equal the appId. Deletion refused."));
        }

        String filterCol = resolveFilterColumn(req, p.profile);
        try {
            FetchResult fetch = fetchService.fetch(
                    p.profile, DELETE_ENV, p.dataModel,
                    p.profile.rootTableName(), filterCol,
                    req.appId().trim(), java.util.List.of(),
                    /*scanOrphans=*/ true);
            DeleteJournal journal = executorService.execute(
                    fetch, p.profile, DELETE_ENV, req.appId().trim());
            return ResponseEntity.ok(journal);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("Delete execute failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    /* ---------- preflight ---------- */

    private Preconditions preflight(DeleteRequest req) {
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
            return p.err(400, "Profile has no 'Root table name' set. Edit the profile and set it.");
        }
        if (p.profile.sandboxBaseUrl() == null || p.profile.sandboxBaseUrl().isBlank()) {
            return p.err(400, "Profile has no sandbox base URL. Delete only supports sandbox.");
        }
        Optional<DataModel> maybeModel = dataModelService.get(profileId);
        if (maybeModel.isEmpty()) {
            return p.err(400,
                    "No dataModel uploaded for this profile. Edit the profile and upload it before deleting.");
        }
        p.dataModel = maybeModel.get();
        if (req == null || req.appId() == null || req.appId().isBlank()) {
            return p.err(400, "'appId' is required.");
        }
        return p;
    }

    /**
     * Decide which column on the root table the caller wants to filter by.
     * Priority: request-body override → profile default (rootPkFilterColumn)
     * → null (fetch service falls back to the root table's PK). Blank/empty
     * request values are ignored.
     */
    private static String resolveFilterColumn(DeleteRequest req, ProjectProfile profile) {
        if (req != null && req.lookupColumn() != null && !req.lookupColumn().isBlank()) {
            return req.lookupColumn().trim();
        }
        return profile.rootPkFilterColumn();
    }

    private static final class Preconditions {
        ProjectProfile profile;
        DataModel dataModel;
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
     * Unified request body for /delete-plan and /execute-delete.
     *
     * @param appId the identifier value — the user's typed input. When
     *   {@code lookupColumn} is unset, this is matched against the root
     *   table's PK. When {@code lookupColumn} is set (e.g. "applicationNumber"),
     *   this value is matched against that column instead.
     * @param lookupColumn optional column name on the root table to filter by.
     *   Empty/null → falls back to the profile's {@code rootPkFilterColumn},
     *   which itself defaults to the root table's PK.
     */
    public record DeleteRequest(
            String appId,
            String lookupColumn,
            Boolean confirm,
            String confirmToken
    ) {}
}
