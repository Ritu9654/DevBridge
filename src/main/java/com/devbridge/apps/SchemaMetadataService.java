package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Detects schema-level facts about the target DB that aren't in the FAWB
 * dataModel JSON. Currently: which tables have CHECK constraints. Any such
 * table with a large-text column must route through the CSV {@code /import}
 * path — SQL's split-INSERT + CONCAT-UPDATE leaves the column in a partial
 * state between chunks, and a CHECK like {@code json_valid(col)} rejects
 * the intermediate value.
 *
 * <p>The dataModel exposes columns and FKs but not CHECK constraints, so
 * we query {@code information_schema.TABLE_CONSTRAINTS} directly via the
 * existing {@code executeSQLs} endpoint. If the query fails (permissions,
 * older DB, etc.), the caller falls back to profile config / built-in
 * defaults — this is diagnostic, not authoritative.
 */
@Service
public class SchemaMetadataService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMetadataService.class);

    private final SqlService sqlService;
    private final ObjectMapper mapper;

    public SchemaMetadataService(SqlService sqlService, ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.mapper = mapper;
    }

    /**
     * Return the set of DB table names (upper-cased) that MUST use the CSV
     * import path because they carry a CHECK constraint AND at least one
     * large-text column. If detection fails for any reason, returns null;
     * caller should fall back to profile / default.
     *
     * @param profile the profile to authenticate + resolve DB name
     * @param env {@code "sandbox"} or {@code "design"} — usually the target env
     * @param graphOrModel provides the tables + columns to intersect with
     *        the DB's CHECK-constraint list. Pass the same FkGraph the
     *        executor uses.
     */
    public Set<String> detectCsvOnlyTables(ProjectProfile profile, String env, FkGraph graphOrModel) {
        // Strategy A: information_schema (fast, one query).
        Set<String> tablesWithCheck = fetchTablesWithCheckConstraints(profile, env);

        // Strategy B: SHOW CREATE TABLE per large-text table (fallback if A
        // returned null or empty). More expensive but works even when
        // information_schema is restricted or when a CHECK is defined in a
        // way TABLE_CONSTRAINTS doesn't expose (rare, but seen in practice
        // with WaveMaker-generated schemas). Parses the CREATE TABLE for
        // "CHECK (" substring.
        if (tablesWithCheck == null || tablesWithCheck.isEmpty()) {
            Set<String> viaShowCreate = detectViaShowCreateTable(profile, env, graphOrModel);
            if (viaShowCreate != null && !viaShowCreate.isEmpty()) {
                log.info("Auto-detected CSV-only tables via SHOW CREATE TABLE: {}", viaShowCreate);
                return viaShowCreate;
            }
            // If strategy A returned null AND strategy B returned null too,
            // detection is truly unavailable — let caller fall back.
            if (tablesWithCheck == null && (viaShowCreate == null || viaShowCreate.isEmpty())) return null;
            // Otherwise strategy A's empty result stands.
            return Set.of();
        }

        // Strategy A gave us a set of tables with CHECK — intersect with
        // "has large-text column" so we don't route tables whose CHECK is on
        // a small column (single INSERT fits, no partial-content problem).
        Set<String> result = new HashSet<>();
        for (String tableUpper : tablesWithCheck) {
            DataModel.Table t = graphOrModel.table(tableUpper).orElse(null);
            if (t == null) continue;
            if (hasLargeTextColumn(t)) result.add(tableUpper);
        }
        log.info("Auto-detected CSV-only tables (CHECK + large-text): {}", result);
        return result;
    }

    /**
     * Fallback detection: for each dataModel table with a large-text column,
     * run {@code SHOW CREATE TABLE <name>} and check if the returned DDL
     * contains a CHECK clause. Returns the set of table names (upper-cased)
     * whose CREATE TABLE has "CHECK (" in it. Null if we couldn't run any
     * query; empty if we ran queries but found no CHECK clauses.
     */
    private Set<String> detectViaShowCreateTable(ProjectProfile profile, String env, FkGraph graph) {
        if (graph == null) return null;
        Set<String> largeTextTables = new HashSet<>();
        for (String tableName : graph.allTableNames()) {
            DataModel.Table t = graph.table(tableName).orElse(null);
            if (t != null && hasLargeTextColumn(t)) largeTextTables.add(t.name());
        }
        if (largeTextTables.isEmpty()) return Set.of();

        Set<String> result = new HashSet<>();
        int successes = 0;
        int failures = 0;
        for (String tableName : largeTextTables) {
            String sql = "SHOW CREATE TABLE `" + tableName.replace("`", "``") + "`";
            try {
                ExecuteResult res = sqlService.execute(profile, env, sql);
                if (res.status() < 200 || res.status() >= 300) { failures++; continue; }
                String ddl = extractShowCreateDdl(res.body());
                if (ddl == null) { failures++; continue; }
                successes++;
                // Case-insensitive scan for "CHECK (" — the standard MariaDB
                // rendering; also match "check(" without space for safety.
                String upper = ddl.toUpperCase(Locale.ROOT);
                if (upper.contains("CHECK (") || upper.contains("CHECK(")) {
                    result.add(tableName.toUpperCase(Locale.ROOT));
                }
            } catch (Exception e) {
                failures++;
            }
        }
        log.info("SHOW CREATE TABLE probe: {} succeeded, {} failed, {} table(s) have CHECK clauses",
                successes, failures, result.size());
        if (successes == 0) return null;   // couldn't probe anything — treat as detection unavailable
        return result;
    }

    /** Extract the CREATE TABLE DDL text from the executeSQLs envelope response. */
    private String extractShowCreateDdl(String body) throws Exception {
        if (body == null || body.isBlank()) return null;
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray() || arr.isEmpty()) return null;
        JsonNode resp = arr.get(0).get("response");
        if (resp == null || !resp.isTextual()) return null;
        String text = resp.asText().trim();
        if (text.isEmpty() || text.charAt(0) != '[') return null;
        JsonNode rows = mapper.readTree(text);
        if (!rows.isArray() || rows.isEmpty()) return null;
        JsonNode firstRow = rows.get(0);
        // MariaDB returns SHOW CREATE TABLE result with columns "Table" and "Create Table".
        // Both cases in case of Hibernate-camelCase conversion.
        for (String key : new String[]{"Create Table", "create Table", "createTable", "CREATE TABLE"}) {
            JsonNode v = firstRow.get(key);
            if (v != null && v.isTextual()) return v.asText();
        }
        // Fallback: pick the LONGEST textual field — that's likely the DDL.
        String longest = null;
        var it = firstRow.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isTextual() && (longest == null || e.getValue().asText().length() > longest.length())) {
                longest = e.getValue().asText();
            }
        }
        return longest;
    }

    /**
     * Try multiple metadata queries to find tables with CHECK constraints.
     * MariaDB's information_schema exposes them via TABLE_CONSTRAINTS
     * (CONSTRAINT_TYPE='CHECK'). Some deploys also expose CHECK_CONSTRAINTS
     * with a slightly different shape. And in some restricted setups
     * information_schema queries are blocked entirely, so as a last-resort
     * schema probe we run {@code SHOW CREATE TABLE} for each dataModel
     * table with a large-text column and grep for "CHECK (".
     */
    private Set<String> fetchTablesWithCheckConstraints(ProjectProfile profile, String env) {
        String db = resolveDbName(profile, env);
        if (db == null || db.isBlank()) {
            log.warn("Cannot detect CHECK constraints — no DB name resolvable for env '{}'", env);
            return null;
        }
        String dbEsc = db.replace("'", "''");

        // Strategy 1: TABLE_CONSTRAINTS view
        Set<String> viaTableConstraints = querySchemaForCheckTables(profile, env,
                "SELECT DISTINCT TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = '" + dbEsc + "' AND CONSTRAINT_TYPE = 'CHECK'");
        if (viaTableConstraints != null && !viaTableConstraints.isEmpty()) {
            log.info("CHECK-constraint detection via TABLE_CONSTRAINTS found {} table(s): {}",
                    viaTableConstraints.size(), viaTableConstraints);
            return viaTableConstraints;
        }

        // Strategy 2: CHECK_CONSTRAINTS view (MariaDB 10.2+, includes constraint definition)
        // CHECK_CONSTRAINTS doesn't have TABLE_NAME — join to TABLE_CONSTRAINTS.
        Set<String> viaCheckConstraints = querySchemaForCheckTables(profile, env,
                "SELECT DISTINCT tc.TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS tc "
                        + "JOIN information_schema.CHECK_CONSTRAINTS cc "
                        + "  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                        + "  AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                        + "WHERE tc.CONSTRAINT_SCHEMA = '" + dbEsc + "'");
        if (viaCheckConstraints != null && !viaCheckConstraints.isEmpty()) {
            log.info("CHECK-constraint detection via CHECK_CONSTRAINTS found {} table(s): {}",
                    viaCheckConstraints.size(), viaCheckConstraints);
            return viaCheckConstraints;
        }

        // Both empty? Log and return empty — but preserve nullness meaning:
        // null = "could not detect, use fallback"; empty = "detected zero".
        // If BOTH queries executed successfully and returned no rows, that's
        // authoritative "no CHECK constraints" — the caller trusts empty.
        // If BOTH queries FAILED (HTTP error), we return null.
        if (viaTableConstraints == null && viaCheckConstraints == null) {
            log.warn("CHECK-constraint detection unavailable — both TABLE_CONSTRAINTS and CHECK_CONSTRAINTS queries failed. Falling back to profile/default.");
            return null;
        }
        log.warn("CHECK-constraint detection ran but returned zero rows on schema '{}'. "
                + "This may mean the DB truly has no CHECK constraints, OR the query was silently rejected by a security filter. "
                + "If a downstream INSERT fails with a CHECK constraint error, set profile.csvOnlyTables explicitly.",
                db);
        return Set.of();
    }

    /** Run one info-schema query. Returns null on HTTP error, empty set on 0-row success, or the parsed set. Logs raw response tail. */
    private Set<String> querySchemaForCheckTables(ProjectProfile profile, String env, String sql) {
        try {
            ExecuteResult res = sqlService.execute(profile, env, sql);
            if (res.status() < 200 || res.status() >= 300) {
                log.warn("Info-schema query HTTP {}: {} → body: {}",
                        res.status(), sql, trunc(res.body()));
                return null;
            }
            Set<String> parsed = parseTableNames(res.body());
            if (parsed.isEmpty()) {
                // Log the raw response so we can see if it was an empty result
                // OR a parse mismatch OR an error dressed as HTTP 200.
                log.info("Info-schema query returned empty. SQL: {} | raw response: {}",
                        sql, trunc(res.body()));
            }
            return parsed;
        } catch (Exception e) {
            log.warn("Info-schema query threw {}: {} for SQL: {}",
                    e.getClass().getSimpleName(), e.getMessage(), sql);
            return null;
        }
    }

    /**
     * Parse the executeSQLs envelope. FAWB wraps each SELECT row in its own
     * envelope — the outer body is an array of {@code {sql, response}} pairs
     * where each {@code response} is a stringified JSON object for that row.
     * This is NOT the shape the docs would lead you to expect (one envelope
     * with a JSON array of rows) — verified by inspecting an actual response
     * from {@code sql-diagnostic.log}:
     * <pre>
     * [
     *   {"sql":null,"response":"{\"TABLE_NAME\":\"AUDIT_CASE\"}"},
     *   {"sql":null,"response":"{\"TABLE_NAME\":\"CASE_DATUM\"}"},
     *   …
     * ]
     * </pre>
     * We also accept the alternate shape (one envelope with array) in case
     * the wire format ever changes or a different endpoint uses that layout.
     * Returns a Set of upper-cased table names.
     */
    private Set<String> parseTableNames(String body) throws Exception {
        Set<String> out = new HashSet<>();
        if (body == null || body.isBlank()) return out;
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray()) return out;
        for (JsonNode env : arr) {
            if (env == null || !env.isObject()) continue;
            JsonNode resp = env.get("response");
            if (resp == null || !resp.isTextual()) continue;
            String text = resp.asText().trim();
            if (text.isEmpty()) continue;
            // Only try to JSON-parse if it looks like JSON. "success" strings
            // from non-SELECT queries are silently skipped.
            char first = text.charAt(0);
            if (first != '{' && first != '[') continue;
            JsonNode parsed;
            try {
                parsed = mapper.readTree(text);
            } catch (Exception ignore) { continue; }
            if (parsed.isObject()) {
                addTableName(parsed, out);
            } else if (parsed.isArray()) {
                for (JsonNode row : parsed) {
                    if (row != null && row.isObject()) addTableName(row, out);
                }
            }
        }
        return out;
    }

    private static void addTableName(JsonNode row, Set<String> out) {
        JsonNode tn = row.get("TABLE_NAME");
        if (tn == null || !tn.isTextual()) tn = row.get("table_name");
        if (tn == null || !tn.isTextual()) tn = row.get("tableName");
        if (tn != null && tn.isTextual()) out.add(tn.asText().toUpperCase(Locale.ROOT));
    }

    private static boolean hasLargeTextColumn(DataModel.Table table) {
        if (table.columns() == null) return false;
        for (DataModel.Column c : table.columns()) {
            if (c == null || c.javaType() == null) continue;
            String jt = c.javaType().toLowerCase(Locale.ROOT);
            if ("text".equals(jt) || "clob".equals(jt) || "longtext".equals(jt) || "blob".equals(jt)) {
                return true;
            }
        }
        return false;
    }

    /** Resolve the DB name used by {@code executeSQLs}'s {@code ?db=} for a given env. Mirrors SqlService's logic. */
    private static String resolveDbName(ProjectProfile p, String env) {
        if (p == null || env == null) return null;
        String e = env.toLowerCase(Locale.ROOT);
        String candidate = switch (e) {
            case "design" -> firstNonBlank(p.sqlDbName(), p.dbServiceName());
            case "sandbox" -> firstNonBlank(p.sandboxSqlDbName(), p.dbServiceName());
            default -> null;
        };
        return candidate;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String trunc(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    /**
     * Fetch unique indexes for a set of tables in one round trip. Queries
     * {@code information_schema.STATISTICS} where {@code NON_UNIQUE = 0}
     * and {@code INDEX_NAME &lt;&gt; 'PRIMARY'}. Returns a per-table list of
     * unique indexes, each with its column list in {@code SEQ_IN_INDEX} order.
     * <p>
     * If the query fails (permissions, restricted schema, etc.) returns
     * {@link Map#of()} — callers must have a fallback path.
     *
     * @param profile the profile providing auth + DB name
     * @param env the target env whose indexes to inspect
     * @param tableNames physical DB table names to fetch (case-insensitive matched)
     */
    public Map<String, List<UniqueIndex>> fetchUniqueIndexes(ProjectProfile profile, String env,
                                                              Collection<String> tableNames) {
        if (tableNames == null || tableNames.isEmpty()) return Map.of();
        String db = resolveDbName(profile, env);
        if (db == null || db.isBlank()) {
            log.warn("Cannot fetch unique indexes — no DB name resolvable for env '{}'", env);
            return Map.of();
        }
        String dbEsc = db.replace("'", "''");

        StringBuilder inList = new StringBuilder();
        boolean firstT = true;
        for (String t : tableNames) {
            if (t == null || t.isBlank()) continue;
            if (!firstT) inList.append(", ");
            inList.append("'").append(t.replace("'", "''")).append("'");
            firstT = false;
        }
        if (firstT) return Map.of();

        String sql = "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX "
                + "FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = '" + dbEsc + "' "
                + "  AND NON_UNIQUE = 0 "
                + "  AND INDEX_NAME <> 'PRIMARY' "
                + "  AND UPPER(TABLE_NAME) IN (" + inList + ") "
                + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";

        try {
            ExecuteResult res = sqlService.execute(profile, env, sql);
            if (res.status() < 200 || res.status() >= 300) {
                log.warn("Unique-index query HTTP {} — body: {}", res.status(), trunc(res.body()));
                return Map.of();
            }
            return parseUniqueIndexes(res.body());
        } catch (Exception e) {
            log.warn("Unique-index query threw {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Parse envelope-wrapped rows into {@code Map<upperTable, List<UniqueIndex>>}.
     * Envelope shape mirrors the CHECK-constraint parser above:
     * {@code [{"sql":null,"response":"{\"TABLE_NAME\":\"...\",\"INDEX_NAME\":\"...\","COLUMN_NAME":\"...\",\"SEQ_IN_INDEX\":1}"},...]}
     */
    private Map<String, List<UniqueIndex>> parseUniqueIndexes(String body) throws Exception {
        Map<String, Map<String, TreeMap<Integer, String>>> byTableIndex = new HashMap<>();
        if (body == null || body.isBlank()) return Map.of();
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray()) return Map.of();
        for (JsonNode env : arr) {
            if (env == null || !env.isObject()) continue;
            JsonNode resp = env.get("response");
            if (resp == null || !resp.isTextual()) continue;
            String text = resp.asText().trim();
            if (text.isEmpty()) continue;
            char first = text.charAt(0);
            if (first != '{' && first != '[') continue;
            JsonNode parsed;
            try { parsed = mapper.readTree(text); }
            catch (Exception ignore) { continue; }
            if (parsed.isObject()) {
                addIndexRow(parsed, byTableIndex);
            } else if (parsed.isArray()) {
                for (JsonNode row : parsed) {
                    if (row != null && row.isObject()) addIndexRow(row, byTableIndex);
                }
            }
        }
        Map<String, List<UniqueIndex>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, TreeMap<Integer, String>>> perTable : byTableIndex.entrySet()) {
            List<UniqueIndex> idxs = new ArrayList<>();
            for (Map.Entry<String, TreeMap<Integer, String>> perIndex : perTable.getValue().entrySet()) {
                idxs.add(new UniqueIndex(perIndex.getKey(), new ArrayList<>(perIndex.getValue().values())));
            }
            out.put(perTable.getKey(), idxs);
        }
        return out;
    }

    private static void addIndexRow(JsonNode row,
                                    Map<String, Map<String, TreeMap<Integer, String>>> byTableIndex) {
        String table = textAny(row, "TABLE_NAME", "table_name", "tableName");
        String idx = textAny(row, "INDEX_NAME", "index_name", "indexName");
        String col = textAny(row, "COLUMN_NAME", "column_name", "columnName");
        Integer seq = intAny(row, "SEQ_IN_INDEX", "seq_in_index", "seqInIndex");
        if (table == null || idx == null || col == null || seq == null) return;
        String tableKey = table.toUpperCase(Locale.ROOT);
        byTableIndex.computeIfAbsent(tableKey, k -> new LinkedHashMap<>())
                    .computeIfAbsent(idx, k -> new TreeMap<>())
                    .put(seq, col);
    }

    private static String textAny(JsonNode row, String... keys) {
        for (String k : keys) {
            JsonNode v = row.get(k);
            if (v != null && v.isTextual()) return v.asText();
        }
        return null;
    }

    private static Integer intAny(JsonNode row, String... keys) {
        for (String k : keys) {
            JsonNode v = row.get(k);
            if (v == null) continue;
            if (v.isNumber()) return v.intValue();
            if (v.isTextual()) {
                try { return Integer.parseInt(v.asText().trim()); } catch (NumberFormatException ignore) {}
            }
        }
        return null;
    }

    /** One unique index on a table. Column list is in {@code SEQ_IN_INDEX} order. */
    public record UniqueIndex(String name, List<String> columns) {}
}
