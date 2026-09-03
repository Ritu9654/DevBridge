package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BFS-scans the source env for all rows belonging to one app. Starting from
 * the profile's {@code rootTableName} + a filter value on its PK, walks the
 * FK graph downward: at each step, for every child table with an FK into a
 * table already gathered, issue a scoped {@code SELECT ... WHERE fk IN (parentPks)}.
 *
 * <p>Reference tables are treated as leaves — never selected, never walked.
 * The FK-graph walk is deterministic; there's no name-guessing.
 */
@Service
public class AppSqlFetchService {

    private static final Logger log = LoggerFactory.getLogger(AppSqlFetchService.class);

    private final SqlService sqlService;
    private final ObjectMapper mapper;

    public AppSqlFetchService(SqlService sqlService, ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.mapper = mapper;
    }

    /**
     * Fetch every row belonging to {@code sourceAppId} from {@code sourceEnv}.
     *
     * @param profile     active profile (used for base URL + DB name)
     * @param sourceEnv   {@code "design"} or {@code "sandbox"}
     * @param dataModel   loaded dataModel used to build the FK graph
     * @param rootTable   physical DB table name of the root (e.g. "CASE_HEADER")
     * @param rootFilterColumn  column on root table to filter by (defaults to PK if null)
     * @param sourceAppId the value to filter the root by
     * @param referenceTables entities/tables that must never be walked
     * @return the gathered rows keyed by normalised table name
     */
    public FetchResult fetch(ProjectProfile profile, String sourceEnv, DataModel dataModel,
                             String rootTable, String rootFilterColumn, Object sourceAppId,
                             List<String> referenceTables) throws Exception {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dataModel, "dataModel");
        if (rootTable == null || rootTable.isBlank()) {
            throw new IllegalArgumentException("rootTable is required (set 'Root table name' on the profile).");
        }
        if (sourceAppId == null || String.valueOf(sourceAppId).isBlank()) {
            throw new IllegalArgumentException("Source appId is required.");
        }

        FkGraph graph = FkGraph.from(dataModel);
        DataModel.Table root = graph.table(rootTable).orElseThrow(() ->
                new IllegalStateException("Root table '" + rootTable + "' not found in dataModel."));
        Set<String> refSet = normaliseReferenceTables(referenceTables);

        String filterCol = firstNonBlank(rootFilterColumn, firstPkColumn(root));
        if (filterCol == null) {
            throw new IllegalStateException(
                    "Root table '" + rootTable + "' has no PK column and no rootPkFilterColumn override.");
        }

        Map<String, List<Map<String, Object>>> gathered = new LinkedHashMap<>();
        List<String> visitOrder = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. Fetch the root row
        String rootSql = SqlBuilder.selectByEquals(root.name(), filterCol, sourceAppId);
        List<Map<String, Object>> rootRows = runSelect(profile, sourceEnv, rootSql);
        if (rootRows.isEmpty()) {
            warnings.add("No row found in " + root.name() + " where " + filterCol + " = " + sourceAppId);
            return new FetchResult(gathered, visitOrder, warnings, graph);
        }
        gathered.put(FkGraph.norm(root.name()), rootRows);
        visitOrder.add(FkGraph.norm(root.name()));

        // 2. BFS downward through the FK graph
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(FkGraph.norm(root.name()));
        Set<String> processed = new HashSet<>();
        processed.add(FkGraph.norm(root.name()));

        while (!frontier.isEmpty()) {
            String parent = frontier.poll();
            DataModel.Table parentTable = graph.table(parent).orElse(null);
            if (parentTable == null) continue;

            for (FkGraph.Edge edge : graph.children(parent)) {
                String childName = FkGraph.norm(edge.childTable());
                if (childName.equals(parent)) continue;    // self-reference — separate handling
                if (refSet.contains(childName)) continue;   // reference — never walked
                DataModel.Table childTable = graph.table(childName).orElse(null);
                if (childTable == null) continue;

                // Collect the parent-side PK values from what we've already fetched
                List<Map<String, Object>> parentRows = gathered.get(parent);
                if (parentRows == null || parentRows.isEmpty()) continue;
                List<Object> parentPks = extractColumn(parentRows, edge.parentPkColumn());
                if (parentPks.isEmpty()) continue;

                String sql = SqlBuilder.selectByIn(childTable.name(), edge.fkColumn(), parentPks);
                if (sql == null) continue;

                List<Map<String, Object>> childRows;
                try {
                    childRows = runSelect(profile, sourceEnv, sql);
                } catch (Exception e) {
                    warnings.add("SELECT failed for " + childTable.name() + " via "
                            + edge.fkColumn() + " -> " + parent + "." + edge.parentPkColumn()
                            + ": " + e.getMessage());
                    continue;
                }
                if (childRows.isEmpty()) continue;

                // Merge (a child may be reached from multiple parents — dedupe by PK value)
                List<Map<String, Object>> existing = gathered.get(childName);
                if (existing == null) {
                    gathered.put(childName, childRows);
                    visitOrder.add(childName);
                } else {
                    String childPk = firstPkColumn(childTable);
                    if (childPk != null) mergeRows(existing, childRows, childPk);
                    else existing.addAll(childRows);
                }
                if (processed.add(childName)) frontier.add(childName);
            }
        }
        return new FetchResult(gathered, visitOrder, warnings, graph);
    }

    /**
     * Run a single SELECT against the given env and parse the response envelopes
     * into row maps. Halts on HTTP error.
     */
    private List<Map<String, Object>> runSelect(ProjectProfile profile, String env, String sql) throws Exception {
        ExecuteResult res = sqlService.execute(profile, env, sql);
        if (res.status() < 200 || res.status() >= 300) {
            throw new IllegalStateException("SELECT failed HTTP " + res.status() + " for SQL: " + trunc(sql)
                    + " — response: " + trunc(res.body()));
        }
        return parseRows(res.body());
    }

    /**
     * Parse a FAWB executeSQLs response body into a list of row maps.
     * <ul>
     *   <li>Empty response ({@code []}) → empty list (zero rows).</li>
     *   <li>Envelope array → each envelope's {@code response} field is a
     *       JSON-encoded object (one row) or already an object; parsed and returned.</li>
     * </ul>
     */
    private List<Map<String, Object>> parseRows(String body) throws Exception {
        if (body == null || body.isBlank()) return List.of();
        JsonNode root = mapper.readTree(body);
        if (!root.isArray() || root.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(root.size());
        for (JsonNode env : root) {
            if (env == null || !env.isObject()) continue;
            JsonNode resp = env.get("response");
            if (resp == null || resp.isNull()) continue;
            JsonNode parsed;
            if (resp.isTextual()) {
                String s = resp.asText();
                if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;   // no data
                try { parsed = mapper.readTree(s); }
                catch (Exception e) { continue; }
            } else {
                parsed = resp;
            }
            if (parsed.isObject()) {
                out.add(mapper.convertValue(parsed, new TypeReference<Map<String, Object>>() {}));
            } else if (parsed.isArray()) {
                for (JsonNode row : parsed) {
                    if (row.isObject()) out.add(mapper.convertValue(row, new TypeReference<Map<String, Object>>() {}));
                }
            }
        }
        return out;
    }

    /* ---------- helpers ---------- */

    private static List<Object> extractColumn(List<Map<String, Object>> rows, String column) {
        String lowerCol = column == null ? null : column.toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>(rows.size());
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(column);
            if (v == null && lowerCol != null) {
                // case-insensitive fallback — MariaDB may return column names in a different case
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lowerCol)) {
                        v = e.getValue();
                        break;
                    }
                }
            }
            if (v == null) continue;
            if (seen.add(v)) out.add(v);
        }
        return out;
    }

    private static void mergeRows(List<Map<String, Object>> existing, List<Map<String, Object>> incoming, String pkCol) {
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> r : existing) if (r.get(pkCol) != null) seen.add(r.get(pkCol));
        for (Map<String, Object> r : incoming) {
            Object pk = r.get(pkCol);
            if (pk == null || seen.add(pk)) existing.add(r);
        }
    }

    private static String firstPkColumn(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }

    private static Set<String> normaliseReferenceTables(List<String> configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String s : configured) if (s != null && !s.isBlank()) out.add(FkGraph.norm(s.trim()));
        return out;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    private static String trunc(String s) {
        return s == null ? "" : (s.length() > 8000 ? s.substring(0, 8000) + "…(+" + (s.length() - 8000) + " chars)" : s);
    }

    /** Result of a source-side fetch. */
    public record FetchResult(
            Map<String, List<Map<String, Object>>> rowsByTable,
            List<String> visitOrder,
            List<String> warnings,
            FkGraph graph
    ) {
        public int totalRowCount() {
            int n = 0;
            for (List<Map<String, Object>> l : rowsByTable.values()) n += l.size();
            return n;
        }
    }
}
