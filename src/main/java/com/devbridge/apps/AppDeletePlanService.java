package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link DeletePlan} from an env-side {@link FetchResult}. Reverses the
 * topological FK order so children (leaves) are deleted before parents (roots).
 * Reference tables are skipped — they are shared data and never in the delete plan.
 *
 * <p>Pure computation — no network I/O.
 */
@Service
public class AppDeletePlanService {

    private static final Logger log = LoggerFactory.getLogger(AppDeletePlanService.class);

    public DeletePlan build(FetchResult fetch, ProjectProfile profile, String env, String appId) {
        String baseUrl = resolveBaseUrl(profile, env);
        String dbName = firstNonBlank(profile.sqlDbName(), profile.dbServiceName());
        DeletePlan.Environment envMeta = new DeletePlan.Environment(
                "target",
                capitalize(env) + " (delete from)",
                env,
                baseUrl,
                dbName);

        DeletePlan.Builder builder = DeletePlan.builder()
                .env(envMeta)
                .rootTable(profile.rootTableName())
                .rootFilterValue(appId);

        for (String w : fetch.warnings()) builder.warn(w);

        FkGraph graph = fetch.graph();
        Set<String> refSet = normaliseReferences(profile.referenceTables());

        List<String> subset = new ArrayList<>(fetch.rowsByTable().keySet());
        FkGraph.TopoResult topo = graph.topoSort(subset);
        if (topo.hasCycles()) {
            builder.warn("FK graph has cyclic dependencies among: " + String.join(", ", topo.cyclic())
                    + ". These tables will be deleted in an arbitrary order within the cycle.");
            builder.cyclicTables(topo.cyclic());
        }

        // Reverse the topo order: children (leaves) first, root last.
        List<String> deleteOrder = new ArrayList<>(topo.ordered());
        Collections.reverse(deleteOrder);

        Set<String> cyclicSet = new HashSet<>(topo.cyclic());
        int order = 0;
        for (String tableName : deleteOrder) {
            List<Map<String, Object>> rows = fetch.rowsByTable().get(tableName);
            if (rows == null || rows.isEmpty()) continue;
            DataModel.Table table = graph.table(tableName).orElse(null);
            if (table == null) continue;
            if (refSet.contains(FkGraph.norm(tableName))) continue;

            String pkColumn = firstPkColumn(table);
            String samplePreview;
            if (pkColumn != null) {
                Object samplePk = lookupCaseInsensitive(rows.get(0), pkColumn);
                samplePreview = SqlBuilder.deleteByEquals(table.name(), pkColumn, samplePk);
            } else {
                samplePreview = "-- no PK column resolved for " + table.name() + "; delete will be skipped";
            }

            String notes;
            if (cyclicSet.contains(tableName)) {
                notes = "Cyclic — order within group is arbitrary; FK constraints may need attention.";
            } else if (FkGraph.norm(tableName).equals(FkGraph.norm(profile.rootTableName()))) {
                notes = "Root table — deleted last.";
            } else {
                notes = "";
            }

            builder.addStep(new DeletePlan.TableStep(
                    ++order,
                    tableName,
                    table.entityName(),
                    rows.size(),
                    pkColumn,
                    samplePreview,
                    notes));
        }

        DeletePlan built = builder.build();
        log.debug("Built delete plan: {} tables, {} rows for app {}", built.totalTables(), built.totalRows(), appId);
        return built;
    }

    private static String resolveBaseUrl(ProjectProfile profile, String env) {
        if ("design".equalsIgnoreCase(env)) return profile.designBaseUrl();
        return profile.sandboxBaseUrl();
    }

    private static String firstPkColumn(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }

    private static Object lookupCaseInsensitive(Map<String, Object> row, String col) {
        if (row == null || col == null) return null;
        if (row.containsKey(col)) return row.get(col);
        String lower = col.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        return null;
    }

    private static Set<String> normaliseReferences(List<String> configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String s : configured) if (s != null && !s.isBlank()) out.add(FkGraph.norm(s.trim()));
        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
