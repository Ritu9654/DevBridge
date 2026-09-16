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
import java.util.LinkedHashSet;
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
        return fetch(profile, sourceEnv, dataModel, rootTable, rootFilterColumn,
                sourceAppId, referenceTables, /*scanOrphans=*/ false);
    }

    /**
     * Same as the 7-arg overload but with an option to also gather "orphan"
     * rows: tables that are NOT FK-reachable from the root but have a column
     * whose name matches the filter column and whose value matches the filter
     * value. Used by the delete flow to catch denormalized / log / audit
     * tables that stamp the app id but aren't wired into the FK graph.
     *
     * <p>The import flow should NOT set this to true — orphan rows aren't
     * FK-tied to the app graph, so we can't remap their contents when copying
     * to a target env.
     */
    public FetchResult fetch(ProjectProfile profile, String sourceEnv, DataModel dataModel,
                             String rootTable, String rootFilterColumn, Object sourceAppId,
                             List<String> referenceTables, boolean scanOrphans) throws Exception {
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
        Set<String> orphanTables = new LinkedHashSet<>();

        // 1. Fetch the root row
        String rootSql = SqlBuilder.selectByEquals(root.name(), filterCol, sourceAppId);
        List<Map<String, Object>> rootRows = runSelect(profile, sourceEnv, rootSql);
        if (rootRows.isEmpty()) {
            warnings.add("No row found in " + root.name() + " where " + filterCol + " = " + sourceAppId);
            return new FetchResult(gathered, visitOrder, warnings, graph, orphanTables);
        }
        gathered.put(FkGraph.norm(root.name()), rootRows);
        visitOrder.add(FkGraph.norm(root.name()));

        // 2. BFS downward through the FK graph — level-based with parallel
        // per-level child queries. Each iteration drains the entire current
        // frontier (all parents at the same topological depth), collects
        // every child SELECT that needs to run, fires them concurrently
        // via a small pool, then processes results in submission order to
        // keep visitOrder + gathered mutation deterministic on the main
        // thread. Workers only run SELECTs — no shared state mutation.
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(FkGraph.norm(root.name()));
        Set<String> processed = new HashSet<>();
        processed.add(FkGraph.norm(root.name()));

        while (!frontier.isEmpty()) {
            // Drain the current level (all parents at this depth).
            List<String> currentLevel = new ArrayList<>(frontier);
            frontier.clear();

            // Collect every child SELECT we need to run for this level.
            List<ChildQuery> queries = new ArrayList<>();
            for (String parent : currentLevel) {
                DataModel.Table parentTable = graph.table(parent).orElse(null);
                if (parentTable == null) continue;
                for (FkGraph.Edge edge : graph.children(parent)) {
                    String childName = FkGraph.norm(edge.childTable());
                    if (childName.equals(parent)) continue;
                    if (refSet.contains(childName)) continue;
                    DataModel.Table childTable = graph.table(childName).orElse(null);
                    if (childTable == null) continue;
                    List<Map<String, Object>> parentRows = gathered.get(parent);
                    if (parentRows == null || parentRows.isEmpty()) continue;
                    List<Object> parentPks = extractColumn(parentRows, edge.parentPkColumn());
                    if (parentPks.isEmpty()) continue;
                    String sql = SqlBuilder.selectByIn(childTable.name(), edge.fkColumn(), parentPks);
                    if (sql == null) continue;
                    queries.add(new ChildQuery(parent, childTable, childName, edge, sql));
                }
            }
            if (queries.isEmpty()) continue;

            // Fire the SELECTs — concurrent if there are >1, inline otherwise
            List<ChildResult> results;
            if (queries.size() > 1) {
                int nThreads = Math.min(6, queries.size());
                final java.util.concurrent.atomic.AtomicInteger tid = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.ExecutorService pool =
                        java.util.concurrent.Executors.newFixedThreadPool(nThreads, r -> {
                            Thread th = new Thread(r, "devbridge-fk-walk-" + tid.incrementAndGet());
                            th.setDaemon(true);
                            return th;
                        });
                List<java.util.concurrent.Future<ChildResult>> futures = new ArrayList<>(queries.size());
                for (final ChildQuery q : queries) {
                    futures.add(pool.submit(() -> {
                        try {
                            return new ChildResult(q, runSelect(profile, sourceEnv, q.sql()), null);
                        } catch (Exception ex) {
                            return new ChildResult(q, null, ex);
                        }
                    }));
                }
                pool.shutdown();
                log.info("FK walk parallel: level with {} parent(s), {} child SELECT(s) across {} thread(s)",
                        currentLevel.size(), queries.size(), nThreads);
                results = new ArrayList<>(queries.size());
                try {
                    for (java.util.concurrent.Future<ChildResult> f : futures) results.add(f.get());
                } finally {
                    pool.shutdownNow();
                }
            } else {
                ChildQuery q = queries.get(0);
                try {
                    results = java.util.List.of(new ChildResult(q, runSelect(profile, sourceEnv, q.sql()), null));
                } catch (Exception ex) {
                    results = java.util.List.of(new ChildResult(q, null, ex));
                }
            }

            // Process results sequentially so gathered/visitOrder/frontier mutations
            // stay deterministic. Order matches submission order = graph.children()
            // iteration order = the same order the old serial code visited them.
            for (ChildResult r : results) {
                if (r.exception() != null) {
                    warnings.add("SELECT failed for " + r.query().childTable().name() + " via "
                            + r.query().edge().fkColumn() + " -> " + r.query().parent()
                            + "." + r.query().edge().parentPkColumn() + ": " + r.exception().getMessage());
                    continue;
                }
                if (r.rows() == null || r.rows().isEmpty()) continue;
                String childName = r.query().childName();
                List<Map<String, Object>> existing = gathered.get(childName);
                if (existing == null) {
                    gathered.put(childName, r.rows());
                    visitOrder.add(childName);
                } else {
                    String childPk = firstPkColumn(r.query().childTable());
                    if (childPk != null) mergeRows(existing, r.rows(), childPk);
                    else existing.addAll(r.rows());
                }
                if (processed.add(childName)) frontier.add(childName);
            }
        }

        // 3. Optional orphan scan — pick up rows in tables that have a column
        // named like the filter column but are NOT reachable through the FK
        // graph. Common in real schemas: audit logs, denormalized reporting
        // tables, integration queues that stamp the app id as a plain column.
        // Only enabled for the delete flow; import intentionally skips these.
        //
        // Perf: the candidate scan runs one SELECT per table with a matching
        // column — up to ~90 on wfs. Each is independent (no shared state
        // read/write during workers), so we fire them concurrently and then
        // collect results in submission order to keep visitOrder stable.
        if (scanOrphans) {
            String filterColLower = filterCol.toLowerCase(Locale.ROOT);
            // First pass: locally identify orphan candidates (no HTTP).
            List<DataModel.Table> candidates = new ArrayList<>();
            List<String> matchedCols = new ArrayList<>();
            for (DataModel.Table t : dataModel.tables()) {
                if (t == null || t.name() == null || t.columns() == null) continue;
                String upper = FkGraph.norm(t.name());
                if (upper.equals(FkGraph.norm(root.name()))) continue;
                if (gathered.containsKey(upper)) continue;
                if (refSet.contains(upper)) continue;
                String matched = null;
                for (DataModel.Column c : t.columns()) {
                    if (c != null && c.name() != null
                            && c.name().toLowerCase(Locale.ROOT).equals(filterColLower)) {
                        matched = c.name();
                        break;
                    }
                }
                if (matched == null) continue;
                candidates.add(t);
                matchedCols.add(matched);
            }

            // Second pass: fire the SELECTs concurrently. Daemon threads so
            // a leak can't hang the JVM. All state mutation (gathered, warnings,
            // orphanTables) still happens sequentially in the collector loop
            // below — workers only run the SELECT and return results.
            if (!candidates.isEmpty()) {
                int nThreads = Math.min(6, candidates.size());
                final java.util.concurrent.atomic.AtomicInteger tid = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.ExecutorService orphanPool =
                        java.util.concurrent.Executors.newFixedThreadPool(nThreads, r -> {
                            Thread th = new Thread(r, "devbridge-orphan-scan-" + tid.incrementAndGet());
                            th.setDaemon(true);
                            return th;
                        });
                List<java.util.concurrent.Future<OrphanScanResult>> futures = new ArrayList<>(candidates.size());
                for (int i = 0; i < candidates.size(); i++) {
                    final DataModel.Table t = candidates.get(i);
                    final String matched = matchedCols.get(i);
                    futures.add(orphanPool.submit(() -> {
                        String sql = SqlBuilder.selectByEquals(t.name(), matched, sourceAppId);
                        try {
                            return new OrphanScanResult(t, matched, runSelect(profile, sourceEnv, sql), null);
                        } catch (Exception ex) {
                            return new OrphanScanResult(t, matched, null, ex);
                        }
                    }));
                }
                orphanPool.shutdown();
                log.info("Orphan scan parallel prefetch: {} candidates across {} thread(s)",
                        candidates.size(), nThreads);
                try {
                    for (java.util.concurrent.Future<OrphanScanResult> f : futures) {
                        OrphanScanResult r = f.get();
                        if (r.exception != null) {
                            warnings.add("Orphan scan: SELECT failed for " + r.table.name() + " on "
                                    + r.matchedCol + ": " + r.exception.getMessage());
                            continue;
                        }
                        if (r.rows == null || r.rows.isEmpty()) continue;
                        String upper = FkGraph.norm(r.table.name());
                        gathered.put(upper, r.rows);
                        visitOrder.add(upper);
                        orphanTables.add(upper);
                        warnings.add("Orphan match: " + r.table.name() + " has " + r.rows.size()
                                + " row(s) with " + r.matchedCol + " = " + sourceAppId
                                + " (not FK-linked to root — added to delete plan)");
                    }
                } finally {
                    orphanPool.shutdownNow();
                }
            }
        }

        return new FetchResult(gathered, visitOrder, warnings, graph, orphanTables);
    }

    /** Outcome of one orphan-scan SELECT run on a worker thread. */
    private record OrphanScanResult(DataModel.Table table, String matchedCol,
                                    List<Map<String, Object>> rows, Exception exception) {}

    /** One child-table SELECT to fire during the level-based FK walk. */
    private record ChildQuery(String parent, DataModel.Table childTable, String childName,
                              FkGraph.Edge edge, String sql) {}

    /** Result of running a {@link ChildQuery} on a worker thread. */
    private record ChildResult(ChildQuery query, List<Map<String, Object>> rows, Exception exception) {}

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
            FkGraph graph,
            Set<String> orphanTables
    ) {
        // Backward-compat: 4-arg constructor for callers that don't need orphan tracking.
        public FetchResult(Map<String, List<Map<String, Object>>> rowsByTable,
                           List<String> visitOrder, List<String> warnings, FkGraph graph) {
            this(rowsByTable, visitOrder, warnings, graph, java.util.Collections.emptySet());
        }
        public int totalRowCount() {
            int n = 0;
            for (List<Map<String, Object>> l : rowsByTable.values()) n += l.size();
            return n;
        }
    }
}
