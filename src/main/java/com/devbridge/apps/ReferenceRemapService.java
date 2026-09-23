package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.profile.ReferenceTableConfig;
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
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Populates the executor's {@code idMap} with source → target mappings for
 * reference tables (DOMAINVALUE, DOMAINVALUETYPE, PRODUCT, ...), using each
 * table's configured natural key to bridge id divergence between envs.
 *
 * <p>Called once by {@link AppSqlExecutorService} before its topo loop begins.
 * From the executor's perspective, remapping a reference-table FK is then
 * identical to remapping any other FK — the id is already in the id-map.
 *
 * <p>Two logical phases:
 * <ol>
 *   <li><b>Discovery (fixed-point):</b> starting from the FK values found in
 *   the fetched app rows, batch-fetch source rows for each referenced ref
 *   table. When those rows have FK columns pointing at OTHER configured ref
 *   tables (e.g. DOMAINVALUE.DomainValueType), add those to the "still to
 *   fetch" set. Loop until nothing new is discovered.</li>
 *   <li><b>Resolution (topo order):</b> process ref tables in dependency order
 *   (a table's natural-key FK targets must be resolved first). For each ref
 *   table, group source rows by the natural-key values of all-but-last-column,
 *   substitute any FK column values through the already-populated id-map,
 *   and issue one target SELECT per group. Populate the id-map with each
 *   source-id → target-id match. Halt with a specific diagnostic on any miss.</li>
 * </ol>
 */
@Service
public class ReferenceRemapService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceRemapService.class);
    /** Cap per SELECT chunk to keep the URL under executeSQLs' GET budget. */
    private static final int MAX_IDS_PER_CHUNK = 200;
    /**
     * Special key inside {@code idMap.get(table)} that stores a "sentinel"
     * target id — any valid row from the target table, used as a fallback
     * when audit-column FKs (CreatedBy, UpdatedBy, ...) can't be resolved
     * to a specific target row. String, not object identity, so it round-trips
     * through JSON serialisation without surprises.
     */
    public static final String SENTINEL_KEY = "__DEVBRIDGE_SENTINEL__";
    /** Common "active" column names auto-tried when picking a sentinel row. */
    private static final List<String> ACTIVE_COLUMN_CANDIDATES =
            List.of("IsActive", "Active", "Enabled", "IS_ACTIVE");

    /**
     * Look up the sentinel target id for a table, if one has been resolved.
     * Returns null if no sentinel is available (e.g. no audit FKs pointed at
     * this table, or the target has zero rows).
     */
    public static Object lookupSentinel(Map<String, Map<Object, Object>> idMap, String targetTable) {
        if (idMap == null || targetTable == null) return null;
        Map<Object, Object> byTable = idMap.get(FkGraph.norm(targetTable));
        return byTable == null ? null : byTable.get(SENTINEL_KEY);
    }

    private final SqlService sqlService;
    private final ObjectMapper mapper;

    public ReferenceRemapService(SqlService sqlService, ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.mapper = mapper;
    }

    /**
     * Populate {@code idMap} with reference-table mappings. Runs against
     * both {@code sourceEnv} (to fetch source rows for referenced ids) and
     * {@code targetEnv} (to look up matching rows by natural key).
     * <p>
     * No-op when the profile has no {@code referenceTableConfigs}, or when
     * no fetched app row references any configured table.
     *
     * @throws Exception if any config is invalid, or if any referenced source
     *     id has no natural-key match in target — see {@link RemapException}.
     */
    public void prepopulate(FetchResult fetch, ProjectProfile profile,
                            String sourceEnv, String targetEnv,
                            Map<String, ReferenceTableConfig> effectiveConfigs,
                            Map<String, Map<Object, Object>> idMap) throws Exception {
        FkGraph graph = fetch.graph();

        // Phase A: natural-key remap for reference tables the user/detector configured.
        // Optional — no-op when no configs.
        Map<String, ReferenceTableConfig> configs = new LinkedHashMap<>();
        if (effectiveConfigs != null) {
            for (Map.Entry<String, ReferenceTableConfig> e : effectiveConfigs.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                configs.put(FkGraph.norm(e.getKey()), e.getValue());
            }
        }
        if (!configs.isEmpty()) {
            validateConfigs(configs, graph);

            Map<String, Set<Object>> needIds = collectDirectRefs(fetch, configs, graph);
            if (needIds.isEmpty()) {
                log.info("Reference remap: no fetched app row references any configured reference table.");
            } else {
                Map<String, Map<Object, Map<String, Object>>> sourceRows = discoverSourceRows(
                        needIds, configs, graph, profile, sourceEnv);
                List<String> resolutionOrder = topoSortRefTables(configs, graph);
                for (String refTable : resolutionOrder) {
                    Map<Object, Map<String, Object>> rows = sourceRows.get(refTable);
                    if (rows == null || rows.isEmpty()) continue;
                    ReferenceTableConfig cfg = configs.get(refTable);
                    DataModel.Table table = graph.table(refTable).orElseThrow(() ->
                            new RemapException("Reference table '" + refTable + "' missing from dataModel."));
                    resolveOneRefTable(refTable, table, cfg, rows, configs, graph, profile, targetEnv, idMap);
                }
                int totalMapped = 0;
                StringBuilder summary = new StringBuilder();
                for (String t : resolutionOrder) {
                    Map<Object, Object> m = idMap.get(t);
                    if (m == null || m.isEmpty()) continue;
                    int count = m.size();
                    if (m.containsKey(SENTINEL_KEY)) count--;
                    if (count <= 0) continue;
                    summary.append(t).append("=").append(count).append(" ");
                    totalMapped += count;
                }
                log.info("Reference natural-key remap: pre-populated idMap with {} row(s) across {} table(s) [{}]",
                        totalMapped, resolutionOrder.size(), summary.toString().trim());
            }
        }

        // Phase B: sentinel resolution for audit-column FKs (CreatedBy, UpdatedBy, ...).
        // Runs regardless of natural-key configs — every audit-column FK target
        // gets one target-side row id fetched into idMap under SENTINEL_KEY.
        Set<String> sentinelTargets = collectSentinelTargets(fetch, graph, configs);
        if (!sentinelTargets.isEmpty()) {
            resolveSentinels(sentinelTargets, graph, configs, profile, targetEnv, idMap);
        }
    }

    /* ---------- sentinel resolution ---------- */

    /**
     * Return the set of target-table names that any fetched app row's audit-column
     * FK points at. These are the tables we'll pick a "sentinel" row from.
     * The check is column-name-based (CreatedBy, UpdatedBy, ...) so the same
     * logic applies uniformly regardless of which target table the audit FK
     * happens to reference.
     */
    private static Set<String> collectSentinelTargets(FetchResult fetch, FkGraph graph,
                                                      Map<String, ReferenceTableConfig> configs) {
        Set<String> out = new LinkedHashSet<>();
        for (String appTable : fetch.rowsByTable().keySet()) {
            for (FkGraph.Edge edge : graph.parents(appTable)) {
                if (!AppSqlPlanService.isAuditFkColumn(edge.fkColumn())) continue;
                out.add(FkGraph.norm(edge.parentTable()));
            }
        }
        return out;
    }

    /**
     * For each sentinel-target table, fetch one target-side row id. Prefers
     * an active row when the table has an obvious active column (or the user
     * configured one in referenceTableConfigs.activeFilter). Falls back to
     * any-row-by-lowest-id. Stores the id under {@link #SENTINEL_KEY} inside
     * {@code idMap[table]} so the executor's FK-remap sites can find it.
     */
    private void resolveSentinels(Set<String> tables, FkGraph graph,
                                  Map<String, ReferenceTableConfig> configs,
                                  ProjectProfile profile, String targetEnv,
                                  Map<String, Map<Object, Object>> idMap) throws Exception {
        record SentTask(String tableName, DataModel.Table table, String pkCol, String activeClause) {}
        record SentResult(SentTask task, Object id) {}

        List<SentTask> tasks = new ArrayList<>();
        for (String tableName : tables) {
            DataModel.Table table = graph.table(tableName).orElse(null);
            if (table == null) continue;
            String pkCol = primaryKeyColumn(table);
            String activeClause = pickActiveClause(table, configs.get(tableName));
            tasks.add(new SentTask(tableName, table, pkCol, activeClause));
        }
        if (tasks.isEmpty()) return;

        List<SentResult> results;
        if (tasks.size() == 1) {
            SentTask t = tasks.get(0);
            Object id = fetchOneRowId(profile, targetEnv, t.table().name(), t.pkCol(), t.activeClause());
            if (id == null && t.activeClause() != null)
                id = fetchOneRowId(profile, targetEnv, t.table().name(), t.pkCol(), null);
            results = List.of(new SentResult(t, id));
        } else {
            int nThreads = Math.min(6, tasks.size());
            AtomicInteger threadId = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
                Thread th = new Thread(r, "devbridge-remap-sentinel-" + threadId.incrementAndGet());
                th.setDaemon(true);
                return th;
            });
            List<Future<SentResult>> futures = new ArrayList<>(tasks.size());
            for (SentTask t : tasks) {
                futures.add(pool.submit(() -> {
                    Object id = fetchOneRowId(profile, targetEnv, t.table().name(), t.pkCol(), t.activeClause());
                    if (id == null && t.activeClause() != null)
                        id = fetchOneRowId(profile, targetEnv, t.table().name(), t.pkCol(), null);
                    return new SentResult(t, id);
                }));
            }
            pool.shutdown();
            results = new ArrayList<>(tasks.size());
            try {
                for (Future<SentResult> f : futures) results.add(unwrap(f));
            } finally {
                pool.shutdownNow();
            }
        }

        StringBuilder log_ = new StringBuilder();
        for (SentResult r : results) {
            if (r.id() == null) {
                throw new RemapException("Sentinel resolution [" + r.task().tableName() + "]: target has 0 rows. "
                        + "Cannot substitute audit-column FKs. Seed at least one row in the target table.");
            }
            idMap.computeIfAbsent(r.task().tableName(), k -> new LinkedHashMap<>()).put(SENTINEL_KEY, r.id());
            log_.append(r.task().tableName()).append("=").append(r.id())
                .append(r.task().activeClause() != null ? " (active)" : "")
                .append("  ");
        }
        log.info("Sentinel remap: audit-column FKs will use — {}", log_.toString().trim());
    }

    private static String pickActiveClause(DataModel.Table table, ReferenceTableConfig cfg) {
        if (cfg != null && cfg.activeFilter() != null && !cfg.activeFilter().isBlank()) {
            return cfg.activeFilter().trim();
        }
        for (DataModel.Column col : safeCols(table.columns())) {
            if (col == null || col.name() == null) continue;
            for (String c : ACTIVE_COLUMN_CANDIDATES) {
                if (col.name().equalsIgnoreCase(c)) return col.name() + "=1";
            }
        }
        return null;
    }

    private Object fetchOneRowId(ProjectProfile profile, String env, String tableName, String pkCol,
                                 String activeClause) throws Exception {
        StringBuilder sb = new StringBuilder("SELECT ").append(SqlBuilder.ident(pkCol))
                .append(" FROM ").append(SqlBuilder.ident(tableName));
        if (activeClause != null) sb.append(" WHERE ").append(activeClause);
        sb.append(" ORDER BY ").append(SqlBuilder.ident(pkCol)).append(" LIMIT 1");
        List<Map<String, Object>> rows = runSelect(profile, env, sb.toString());
        if (rows.isEmpty()) return null;
        Object v = lookupCaseInsensitive(rows.get(0), pkCol);
        return v;
    }

    private static <T> List<T> safeCols(List<T> c) { return c == null ? List.of() : c; }

    /* ---------- validation ---------- */

    private static void validateConfigs(Map<String, ReferenceTableConfig> configs, FkGraph graph) {
        for (Map.Entry<String, ReferenceTableConfig> e : configs.entrySet()) {
            String table = e.getKey();
            ReferenceTableConfig cfg = e.getValue();
            DataModel.Table t = graph.table(table).orElseThrow(() ->
                    new RemapException("referenceTableConfigs['" + table
                            + "']: table not found in dataModel."));
            if (cfg.naturalKey() == null || cfg.naturalKey().isEmpty()) {
                throw new RemapException("referenceTableConfigs['" + table
                        + "']: naturalKey must have at least one column.");
            }
            Set<String> tableCols = new HashSet<>();
            for (DataModel.Column c : safe(t.columns())) {
                if (c != null && c.name() != null) tableCols.add(c.name().toLowerCase(Locale.ROOT));
            }
            for (String col : cfg.naturalKey()) {
                if (col == null || col.isBlank()) {
                    throw new RemapException("referenceTableConfigs['" + table
                            + "']: naturalKey contains a blank column name.");
                }
                if (!tableCols.contains(col.toLowerCase(Locale.ROOT))) {
                    throw new RemapException("referenceTableConfigs['" + table
                            + "']: column '" + col + "' not found on table.");
                }
                String fkTarget = fkTargetOfColumn(t, col, graph);
                if (fkTarget != null && !configs.containsKey(FkGraph.norm(fkTarget))) {
                    throw new RemapException("referenceTableConfigs['" + table
                            + "']: natural-key column '" + col + "' is an FK to '"
                            + fkTarget + "', which must also be listed in "
                            + "referenceTableConfigs so the remapper can resolve it first.");
                }
            }
        }
    }

    /* ---------- discovery ---------- */

    /**
     * Scan fetched app rows for FK values pointing to any configured reference
     * table. Audit-column FK values (CreatedBy, UpdatedBy, ...) are excluded
     * here — those get resolved via the sentinel phase instead, since
     * per-row user identity has no stable natural key across envs.
     */
    private static Map<String, Set<Object>> collectDirectRefs(FetchResult fetch,
                                                              Map<String, ReferenceTableConfig> configs,
                                                              FkGraph graph) {
        Map<String, Set<Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : fetch.rowsByTable().entrySet()) {
            String appTable = e.getKey();
            List<Map<String, Object>> rows = e.getValue();
            if (rows == null || rows.isEmpty()) continue;
            for (FkGraph.Edge edge : graph.parents(appTable)) {
                String parent = FkGraph.norm(edge.parentTable());
                if (!configs.containsKey(parent)) continue;
                if (AppSqlPlanService.isAuditFkColumn(edge.fkColumn())) continue;
                Set<Object> ids = out.computeIfAbsent(parent, k -> new LinkedHashSet<>());
                for (Map<String, Object> row : rows) {
                    Object v = lookupCaseInsensitive(row, edge.fkColumn());
                    if (v != null) ids.add(v);
                }
            }
        }
        return out;
    }

    /**
     * Fixed-point: keep fetching source rows until natural-key column values
     * that are themselves FKs to other ref tables stop revealing new ids.
     */
    private Map<String, Map<Object, Map<String, Object>>> discoverSourceRows(
            Map<String, Set<Object>> needIds,
            Map<String, ReferenceTableConfig> configs,
            FkGraph graph, ProjectProfile profile, String sourceEnv) throws Exception {
        Map<String, Map<Object, Map<String, Object>>> cache = new LinkedHashMap<>();
        record SrcTask(String refTable, DataModel.Table table, ReferenceTableConfig cfg, Set<Object> missing) {}
        record SrcResult(SrcTask task, List<Map<String, Object>> rows) {}
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Map.Entry<String, Set<Object>>> pending = new ArrayList<>(needIds.entrySet());

            // Build per-table fetch tasks — only tables with uncached IDs need an HTTP call.
            List<SrcTask> tasks = new ArrayList<>();
            for (Map.Entry<String, Set<Object>> entry : pending) {
                String refTable = entry.getKey();
                Set<Object> ids = entry.getValue();
                Map<Object, Map<String, Object>> already = cache.computeIfAbsent(refTable, k -> new LinkedHashMap<>());
                // Normalise to String: JSON may return Integer/Long while the FK value in the app
                // row is a String (or vice-versa); strict containsKey misses cause O(N²) re-fetches.
                Set<String> alreadyNorm = new HashSet<>();
                for (Object k : already.keySet()) alreadyNorm.add(String.valueOf(k));
                Set<Object> missing = new LinkedHashSet<>();
                for (Object id : ids) if (!alreadyNorm.contains(String.valueOf(id))) missing.add(id);
                if (missing.isEmpty()) continue;
                DataModel.Table table = graph.table(refTable).orElseThrow(() ->
                        new RemapException("Reference table '" + refTable + "' missing from dataModel."));
                tasks.add(new SrcTask(refTable, table, configs.get(refTable), missing));
            }
            if (tasks.isEmpty()) continue;
            changed = true;

            // Fire all source-side fetches in parallel — each is an independent SELECT.
            List<SrcResult> results;
            if (tasks.size() == 1) {
                SrcTask t = tasks.get(0);
                results = List.of(new SrcResult(t, batchFetchByIn(profile, sourceEnv,
                        t.table().name(), primaryKeyColumn(t.table()), t.missing())));
            } else {
                int nThreads = Math.min(6, tasks.size());
                AtomicInteger threadId = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
                    Thread th = new Thread(r, "devbridge-remap-src-" + threadId.incrementAndGet());
                    th.setDaemon(true);
                    return th;
                });
                List<Future<SrcResult>> futures = new ArrayList<>(tasks.size());
                for (SrcTask t : tasks) {
                    futures.add(pool.submit(() -> new SrcResult(t, batchFetchByIn(profile, sourceEnv,
                            t.table().name(), primaryKeyColumn(t.table()), t.missing()))));
                }
                pool.shutdown();
                results = new ArrayList<>(tasks.size());
                try {
                    for (Future<SrcResult> f : futures) results.add(unwrap(f));
                } finally {
                    pool.shutdownNow();
                }
            }

            // Merge results into cache and expand needIds for transitive FK discovery.
            for (SrcResult r : results) {
                Map<Object, Map<String, Object>> already = cache.get(r.task().refTable());
                String pkCol = primaryKeyColumn(r.task().table());
                for (Map<String, Object> row : r.rows()) {
                    Object pk = lookupCaseInsensitive(row, pkCol);
                    if (pk != null) already.put(pk, row);
                }
                for (String col : r.task().cfg().naturalKey()) {
                    String fkTarget = fkTargetOfColumn(r.task().table(), col, graph);
                    if (fkTarget == null) continue;
                    String fkTargetNorm = FkGraph.norm(fkTarget);
                    if (!configs.containsKey(fkTargetNorm)) continue;
                    Set<Object> upstream = needIds.computeIfAbsent(fkTargetNorm, k -> new LinkedHashSet<>());
                    for (Map<String, Object> row : r.rows()) {
                        Object v = lookupCaseInsensitive(row, col);
                        if (v != null) upstream.add(v);
                    }
                }
            }
        }
        return cache;
    }

    /* ---------- topo order ---------- */

    /**
     * Order configured ref tables such that any table whose natural key
     * contains an FK to another configured ref table is processed AFTER
     * that other table.
     */
    private static List<String> topoSortRefTables(Map<String, ReferenceTableConfig> configs, FkGraph graph) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String t : configs.keySet()) {
            deps.put(t, new LinkedHashSet<>());
            indegree.put(t, 0);
        }
        for (Map.Entry<String, ReferenceTableConfig> e : configs.entrySet()) {
            String t = e.getKey();
            DataModel.Table table = graph.table(t).orElse(null);
            if (table == null) continue;
            for (String col : e.getValue().naturalKey()) {
                String fkTarget = fkTargetOfColumn(table, col, graph);
                if (fkTarget == null) continue;
                String norm = FkGraph.norm(fkTarget);
                if (!configs.containsKey(norm) || norm.equals(t)) continue;
                if (deps.get(t).add(norm)) {
                    indegree.merge(t, 1, Integer::sum);
                }
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) queue.add(e.getKey());
        }
        List<String> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String t = queue.poll();
            ordered.add(t);
            for (String other : configs.keySet()) {
                if (deps.get(other).remove(t)) {
                    int newDeg = indegree.merge(other, -1, Integer::sum);
                    if (newDeg == 0) queue.add(other);
                }
            }
        }
        if (ordered.size() != configs.size()) {
            List<String> remaining = new ArrayList<>();
            for (String t : configs.keySet()) if (!ordered.contains(t)) remaining.add(t);
            throw new RemapException("Cycle detected in referenceTableConfigs natural-key FKs: "
                    + remaining + ". Break the cycle or reduce the natural key.");
        }
        return ordered;
    }

    /* ---------- resolution ---------- */

    private void resolveOneRefTable(String refTable, DataModel.Table table, ReferenceTableConfig cfg,
                                    Map<Object, Map<String, Object>> sourceRowsById,
                                    Map<String, ReferenceTableConfig> configs, FkGraph graph,
                                    ProjectProfile profile, String targetEnv,
                                    Map<String, Map<Object, Object>> idMap) throws Exception {
        List<String> naturalKey = cfg.naturalKey();
        String lastKeyCol = naturalKey.get(naturalKey.size() - 1);
        List<String> leadingKeyCols = naturalKey.subList(0, naturalKey.size() - 1);

        // Group source rows by their leading-natural-key tuple (already substituted
        // through the id-map when the leading column is an FK to another ref table).
        // Each group produces one target SELECT with IN(...) on the last natural-key column.
        // Rows with any NULL in their natural key are tracked as unresolvable — the
        // caller will surface those as misses (never silently drop).
        Map<List<Object>, List<Map<String, Object>>> byLeadingKey = new LinkedHashMap<>();
        Map<Object, List<Object>> naturalKeyOfSourceId = new LinkedHashMap<>();
        Map<Object, String> nullKeyReasons = new LinkedHashMap<>();
        for (Map.Entry<Object, Map<String, Object>> e : sourceRowsById.entrySet()) {
            Object sourceId = e.getKey();
            Map<String, Object> row = e.getValue();
            List<Object> leading = new ArrayList<>(leadingKeyCols.size());
            String nullCol = null;
            for (String col : leadingKeyCols) {
                Object v = lookupCaseInsensitive(row, col);
                v = substituteFkThroughIdMap(v, table, col, graph, configs, idMap);
                if (v == null) { nullCol = col; break; }
                leading.add(v);
            }
            Object lastVal = null;
            if (nullCol == null) {
                lastVal = lookupCaseInsensitive(row, lastKeyCol);
                lastVal = substituteFkThroughIdMap(lastVal, table, lastKeyCol, graph, configs, idMap);
                if (lastVal == null) nullCol = lastKeyCol;
            }
            if (nullCol != null) {
                nullKeyReasons.put(sourceId,
                        "natural-key column '" + nullCol + "' is null in source row");
                continue;
            }
            byLeadingKey.computeIfAbsent(leading, k -> new ArrayList<>()).add(row);
            List<Object> fullKey = new ArrayList<>(leading);
            fullKey.add(lastVal);
            naturalKeyOfSourceId.put(sourceId, fullKey);
        }

        // Build all (leading, sql) target queries up-front, then fire them in parallel.
        // Each group × chunk combination is an independent target-side read.
        record TgtQuery(List<Object> leading, String sql) {}
        record TgtResult(List<Object> leading, List<Map<String, Object>> rows) {}
        List<TgtQuery> tgtQueries = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Map<String, Object>>> group : byLeadingKey.entrySet()) {
            List<Object> leading = group.getKey();
            Set<Object> lastValues = new LinkedHashSet<>();
            for (Map<String, Object> row : group.getValue()) {
                Object lastVal = lookupCaseInsensitive(row, lastKeyCol);
                lastVal = substituteFkThroughIdMap(lastVal, table, lastKeyCol, graph, configs, idMap);
                if (lastVal != null) lastValues.add(lastVal);
            }
            if (lastValues.isEmpty()) continue;
            for (List<Object> chunkLast : chunkList(new ArrayList<>(lastValues), MAX_IDS_PER_CHUNK)) {
                String sql = buildTargetLookupSql(table.name(), naturalKey, leadingKeyCols, leading,
                        lastKeyCol, chunkLast, cfg.activeFilter());
                tgtQueries.add(new TgtQuery(leading, sql));
            }
        }

        Map<List<Object>, Object> targetByKey = new HashMap<>();
        if (!tgtQueries.isEmpty()) {
            List<TgtResult> tgtResults;
            if (tgtQueries.size() == 1) {
                TgtQuery q = tgtQueries.get(0);
                tgtResults = List.of(new TgtResult(q.leading(), runSelect(profile, targetEnv, q.sql())));
            } else {
                int nThreads = Math.min(8, tgtQueries.size());
                AtomicInteger threadId = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(nThreads, r -> {
                    Thread th = new Thread(r, "devbridge-remap-tgt-" + threadId.incrementAndGet());
                    th.setDaemon(true);
                    return th;
                });
                List<Future<TgtResult>> futures = new ArrayList<>(tgtQueries.size());
                for (TgtQuery q : tgtQueries) {
                    futures.add(pool.submit(() ->
                            new TgtResult(q.leading(), runSelect(profile, targetEnv, q.sql()))));
                }
                pool.shutdown();
                tgtResults = new ArrayList<>(tgtQueries.size());
                try {
                    for (Future<TgtResult> f : futures) tgtResults.add(unwrap(f));
                } finally {
                    pool.shutdownNow();
                }
            }
            for (TgtResult r : tgtResults) {
                for (Map<String, Object> tRow : r.rows()) {
                    List<Object> key = new ArrayList<>(r.leading());
                    Object lv = lookupCaseInsensitive(tRow, lastKeyCol);
                    if (lv == null) continue;
                    key.add(lv);
                    Object tid = lookupCaseInsensitive(tRow, primaryKeyColumn(table));
                    if (tid != null) targetByKey.putIfAbsent(key, tid);
                }
            }
        }

        // Populate idMap for each source id whose natural key was resolved.
        Map<Object, Object> tableMap = idMap.computeIfAbsent(refTable, k -> new LinkedHashMap<>());
        List<String> misses = new ArrayList<>();
        for (Map.Entry<Object, List<Object>> e : naturalKeyOfSourceId.entrySet()) {
            Object sourceId = e.getKey();
            List<Object> key = e.getValue();
            Object targetId = targetByKey.get(key);
            if (targetId != null) {
                tableMap.put(sourceId, targetId);
            } else {
                misses.add("source id=" + sourceId + " natural key=" + describeKey(naturalKey, key));
            }
        }
        // Source ids we couldn't even build a natural key for (NULL in a key column)
        // must also count as misses — otherwise the executor later halts with a
        // less specific "parent likely wasn't inserted" error.
        for (Map.Entry<Object, String> e : nullKeyReasons.entrySet()) {
            misses.add("source id=" + e.getKey() + " — " + e.getValue());
        }
        if (!misses.isEmpty()) {
            String activeSuffix = cfg.activeFilter() != null && !cfg.activeFilter().isBlank()
                    ? " (with " + cfg.activeFilter() + ")" : "";
            throw new RemapException("Reference remap [" + refTable + "]: "
                    + misses.size() + " source id(s) have no matching target row" + activeSuffix
                    + ". First few: " + firstFew(misses, 5)
                    + ". Seed the target env or update the source to use existing values.");
        }
    }

    /**
     * Build one target-side SELECT for a group sharing the same leading-key tuple.
     * <pre>
     * SELECT `id`, natKey1, natKey2, ... FROM `T`
     *   WHERE `leading1` = X AND `leading2` = Y
     *     AND `lastKey` IN (v1, v2, ...)
     *     AND (activeFilter)
     * </pre>
     */
    private static String buildTargetLookupSql(String tableName, List<String> naturalKey,
                                               List<String> leadingCols, List<Object> leadingVals,
                                               String lastCol, List<Object> lastVals,
                                               String activeFilter) {
        StringBuilder sb = new StringBuilder("SELECT ").append(SqlBuilder.ident("id"));
        for (String col : naturalKey) {
            sb.append(", ").append(SqlBuilder.ident(col));
        }
        sb.append(" FROM ").append(SqlBuilder.ident(tableName)).append(" WHERE ");
        boolean first = true;
        for (int i = 0; i < leadingCols.size(); i++) {
            if (!first) sb.append(" AND ");
            sb.append(SqlBuilder.ident(leadingCols.get(i))).append(" = ")
              .append(SqlBuilder.literal(leadingVals.get(i)));
            first = false;
        }
        if (!first) sb.append(" AND ");
        sb.append(SqlBuilder.ident(lastCol)).append(" IN (");
        boolean firstV = true;
        for (Object v : lastVals) {
            if (!firstV) sb.append(", ");
            sb.append(SqlBuilder.literal(v));
            firstV = false;
        }
        sb.append(")");
        if (activeFilter != null && !activeFilter.isBlank()) {
            sb.append(" AND (").append(activeFilter.trim()).append(")");
        }
        return sb.toString();
    }

    /* ---------- source-side fetching ---------- */

    /** Fetch source rows by primary key, chunked to respect URL budget. */
    private List<Map<String, Object>> batchFetchByIn(ProjectProfile profile, String env,
                                                     String table, String pkColumn,
                                                     Collection<Object> ids) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Object> chunk : chunkList(new ArrayList<>(ids), MAX_IDS_PER_CHUNK)) {
            String sql = SqlBuilder.selectByIn(table, pkColumn, chunk);
            if (sql == null) continue;
            out.addAll(runSelect(profile, env, sql));
        }
        return out;
    }

    /* ---------- HTTP + envelope parsing ---------- */

    private List<Map<String, Object>> runSelect(ProjectProfile profile, String env, String sql) throws Exception {
        ExecuteResult res = sqlService.execute(profile, env, sql);
        if (res.status() < 200 || res.status() >= 300) {
            throw new RemapException("Reference remap SELECT failed HTTP " + res.status()
                    + " for SQL: " + trunc(sql) + " — response: " + trunc(res.body()));
        }
        return parseRows(res.body());
    }

    /**
     * Parse a FAWB executeSQLs response body into a list of row maps. Same
     * envelope shape as {@code AppSqlFetchService.parseRows} — replicated
     * here to avoid coupling the two services.
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
                if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;
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

    /**
     * If {@code column} on {@code table} is an FK, return the target table's
     * physical name; otherwise null. Reference-only — the return value is
     * consulted to decide whether to substitute an FK value through the id-map.
     */
    private static String fkTargetOfColumn(DataModel.Table table, String column, FkGraph graph) {
        if (table == null || column == null) return null;
        String colLower = column.toLowerCase(Locale.ROOT);
        for (FkGraph.Edge edge : graph.parents(table.name())) {
            if (edge.fkColumn() != null && edge.fkColumn().toLowerCase(Locale.ROOT).equals(colLower)) {
                return edge.parentTable();
            }
        }
        return null;
    }

    /**
     * If {@code column} is an FK to a configured reference table, look up
     * {@code sourceValue} in the id-map for that table and return the target
     * value; otherwise return {@code sourceValue} unchanged.
     */
    private static Object substituteFkThroughIdMap(Object sourceValue, DataModel.Table table, String column,
                                                   FkGraph graph, Map<String, ReferenceTableConfig> configs,
                                                   Map<String, Map<Object, Object>> idMap) {
        if (sourceValue == null) return null;
        String fkTarget = fkTargetOfColumn(table, column, graph);
        if (fkTarget == null) return sourceValue;
        String norm = FkGraph.norm(fkTarget);
        if (!configs.containsKey(norm)) return sourceValue;
        Map<Object, Object> mapForTable = idMap.get(norm);
        if (mapForTable == null) return null;
        Object mapped = mapForTable.get(sourceValue);
        if (mapped != null) return mapped;
        // fallback: string-coerced key match (JSON round-trip may change Integer↔Long↔String).
        for (Map.Entry<Object, Object> e : mapForTable.entrySet()) {
            if (Objects.equals(String.valueOf(e.getKey()), String.valueOf(sourceValue))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String primaryKeyColumn(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) {
            throw new RemapException("Reference table '" + (t == null ? "?" : t.name()) + "' has no primary key.");
        }
        List<String> cols = t.primaryKey().columns();
        if (cols.isEmpty()) {
            throw new RemapException("Reference table '" + t.name() + "' has empty primary key columns.");
        }
        return cols.get(0);
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

    private static <T> List<List<T>> chunkList(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return out;
    }

    private static String describeKey(List<String> colNames, List<Object> values) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < colNames.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(colNames.get(i)).append("=").append(values.get(i));
        }
        return sb.append(")").toString();
    }

    private static String firstFew(List<String> items, int n) {
        if (items.size() <= n) return items.toString();
        return items.subList(0, n) + " ...(+" + (items.size() - n) + " more)";
    }

    private static <T> Collection<T> safe(Collection<T> c) { return c == null ? List.of() : c; }

    private static String trunc(String s) {
        return s == null ? "" : (s.length() > 8000 ? s.substring(0, 8000) + "…(+" + (s.length() - 8000) + " chars)" : s);
    }

    private static <T> T unwrap(Future<T> f) throws Exception {
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /** Raised for any invalid config or unresolvable reference. */
    public static class RemapException extends RuntimeException {
        public RemapException(String msg) { super(msg); }
    }
}
