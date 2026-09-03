package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Advisory cross-check: compares FAWB's facade JSON output against what the
 * FK-graph walk found. Never blocks execution — it only surfaces "did we miss
 * anything?" hints in the plan preview.
 * <p>
 * How it works:
 * <ol>
 *   <li>Fetch the facade JSON (skipped if profile has no facade config).</li>
 *   <li>Count entries per top-level collection in the JSON (scalars ignored).</li>
 *   <li>Resolve each key to a physical table via {@link FacadeKeyResolver}.</li>
 *   <li>Emit one {@link ImportPlan.FacadeCrossCheck} per key:
 *     <ul>
 *       <li>{@code match} — facade count = FK-walk row count.</li>
 *       <li>{@code count-mismatch} — mapped to a table but counts differ.</li>
 *       <li>{@code unmapped} — couldn't resolve the key to any table.</li>
 *       <li>{@code table-not-walked} — mapped OK but FK-walk didn't reach that table.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
@Service
public class FacadeValidator {

    private static final Logger log = LoggerFactory.getLogger(FacadeValidator.class);

    private final AppFacadeFetchService facadeFetch;
    private final ObjectMapper mapper;

    public FacadeValidator(AppFacadeFetchService facadeFetch, ObjectMapper mapper) {
        this.facadeFetch = facadeFetch;
        this.mapper = mapper;
    }

    /**
     * Run the cross-check. Returns an empty list (never null) if facade config
     * is missing, the fetch fails, or the response body isn't a JSON object.
     * The plan is otherwise unaffected — this is best-effort observation.
     */
    public List<ImportPlan.FacadeCrossCheck> crossCheck(ProjectProfile profile, String sourceEnv,
                                                        DataModel dataModel, String appId,
                                                        AppSqlFetchService.FetchResult fkWalk) {
        List<ImportPlan.FacadeCrossCheck> out = new ArrayList<>();
        AppFacadeFetchService.FetchResult res;
        try {
            res = facadeFetch.fetch(profile, appId, sourceEnv);
        } catch (Exception e) {
            log.info("Facade cross-check skipped — fetch threw: {}", e.getMessage());
            return out;
        }
        if (res == null) return out;                               // no facade config on profile
        if (res.status() < 200 || res.status() >= 300) {
            log.info("Facade cross-check skipped — HTTP {} from {}", res.status(), res.url());
            return out;
        }

        JsonNode root;
        try {
            root = res.body() == null || res.body().isBlank()
                    ? null : mapper.readTree(res.body());
        } catch (Exception e) {
            log.info("Facade cross-check skipped — response not valid JSON: {}", e.getMessage());
            return out;
        }
        if (root == null || !root.isObject()) return out;

        FacadeKeyResolver resolver = new FacadeKeyResolver(dataModel, profile.facadeKeyOverrides());
        Map<String, List<Map<String, Object>>> rowsByTable = fkWalk.rowsByTable();

        Iterator<String> fields = root.fieldNames();
        while (fields.hasNext()) {
            String key = fields.next();
            JsonNode node = root.get(key);

            int facadeCount = countEntries(node);
            if (facadeCount == 0) continue;   // scalars, empty collections — ignore

            Optional<String> tableOpt = resolver.toTableName(key);
            if (tableOpt.isEmpty()) {
                out.add(new ImportPlan.FacadeCrossCheck(key, null, facadeCount, 0, "unmapped"));
                continue;
            }
            String table = tableOpt.get();
            List<Map<String, Object>> rows = rowsByTable.get(table);
            if (rows == null) {
                out.add(new ImportPlan.FacadeCrossCheck(key, table, facadeCount, 0, "table-not-walked"));
                continue;
            }
            int fkCount = rows.size();
            String status = facadeCount == fkCount ? "match" : "count-mismatch";
            out.add(new ImportPlan.FacadeCrossCheck(key, table, facadeCount, fkCount, status));
        }
        return out;
    }

    /**
     * Count "entries" for a facade collection: a JSON object counts as 1, an
     * array counts as its size, everything else (scalars, nulls) counts as 0
     * because it can't be a table row on its own.
     */
    private static int countEntries(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return 0;
        if (node.isArray()) return node.size();
        if (node.isObject()) return 1;
        return 0;
    }
}
