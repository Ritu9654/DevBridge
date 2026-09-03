package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds an {@link ImportPlan} from a source-side {@link FetchResult}.
 * Determines table order via topological sort of the FK graph and, per table,
 * classifies columns (strip vs pass-through vs FK-remap vs regen-UUID).
 *
 * <p>Pure computation — no network I/O, no writes. Runs entirely from the
 * data already gathered by {@link AppSqlFetchService}.
 */
@Service
public class AppSqlPlanService {

    private static final Logger log = LoggerFactory.getLogger(AppSqlPlanService.class);
    /** Rows previewed per table. Kept small — the UI only shows one sample INSERT. */
    private static final int PREVIEW_ROW_COUNT = 1;

    private final FacadeValidator facadeValidator;
    private final ReferenceConfigDetector referenceConfigDetector;

    public AppSqlPlanService(FacadeValidator facadeValidator,
                             ReferenceConfigDetector referenceConfigDetector) {
        this.facadeValidator = facadeValidator;
        this.referenceConfigDetector = referenceConfigDetector;
    }

    public ImportPlan build(FetchResult fetch, ProjectProfile profile,
                            String sourceEnv, String targetEnv,
                            com.devbridge.datamodel.DataModel dataModel, String appId) {
        ImportPlan.Builder builder = ImportPlan.builder()
                .rootTable(profile.rootTableName())
                .rootFilterValue(appId)
                .source(buildEnv("source", sourceEnv, "read", profile))
                .target(buildEnv("target", targetEnv, "write", profile));

        for (String w : fetch.warnings()) builder.warn(w);

        FkGraph graph = fetch.graph();
        Set<String> refSet = normaliseReferences(profile.referenceTables());

        // Only include tables where we actually gathered rows
        List<String> subset = new ArrayList<>(fetch.rowsByTable().keySet());
        FkGraph.TopoResult topo = graph.topoSort(subset);
        if (topo.hasCycles()) {
            builder.warn("FK graph has cyclic dependencies among: " + String.join(", ", topo.cyclic())
                    + ". These tables will be inserted in an arbitrary order; consider a follow-up UPDATE pass.");
            builder.cyclicTables(topo.cyclic());
        }

        int order = 0;
        for (String tableName : topo.ordered()) {
            List<Map<String, Object>> rows = fetch.rowsByTable().get(tableName);
            if (rows == null || rows.isEmpty()) continue;
            DataModel.Table table = graph.table(tableName).orElse(null);
            if (table == null) continue;
            if (refSet.contains(FkGraph.norm(tableName))) continue;   // never in the plan

            ColumnPolicies policies = ColumnPolicies.compute(table, graph, refSet);
            String samplePreview = renderSampleInsert(table, rows.get(0), policies);

            ImportPlan.TableStep step = new ImportPlan.TableStep(
                    ++order,
                    tableName,
                    rows.size(),
                    policies.pkStrategy,
                    policies.pkColumns,
                    policies.stripped,
                    policies.uuidPkColumns,
                    policies.fkRemaps,
                    samplePreview,
                    policies.notes);
            builder.addStep(step);
        }

        // Facade cross-check — advisory only. Never fails the plan build.
        try {
            for (ImportPlan.FacadeCrossCheck c :
                    facadeValidator.crossCheck(profile, sourceEnv, dataModel, appId, fetch)) {
                builder.facadeCheck(c);
            }
        } catch (Exception e) {
            log.info("Facade cross-check skipped: {}", e.getMessage());
        }

        // Reference-remap preview — show which reference tables will be
        // natural-key-remapped, whether from auto-detection or user override.
        try {
            var detected = referenceConfigDetector.detectFor(fetch, profile, targetEnv);
            var user = profile.referenceTableConfigs();
            var effective = ReferenceConfigDetector.merge(detected, user);
            for (var e : effective.entrySet()) {
                String table = e.getKey();
                var cfg = e.getValue();
                boolean inUser = user != null && user.keySet().stream()
                        .anyMatch(k -> FkGraph.norm(k).equals(table));
                boolean inDetected = detected.containsKey(table);
                String src;
                if (inUser && inDetected) src = "override";
                else if (inUser) src = "user";
                else src = "auto";
                builder.addReferenceRemap(new ImportPlan.ReferenceRemap(
                        table, cfg.naturalKey(), cfg.activeFilter(), src));
            }
        } catch (Exception e) {
            log.info("Reference-remap preview skipped: {}", e.getMessage());
        }

        // Sentinel-remap preview — show which reference tables receive an
        // "any-row" fallback for audit-column FKs (CreatedBy, UpdatedBy, ...).
        java.util.Map<String, java.util.LinkedHashSet<String>> sentinelByTable = new java.util.LinkedHashMap<>();
        for (String appTable : fetch.rowsByTable().keySet()) {
            for (FkGraph.Edge edge : graph.parents(appTable)) {
                if (!isAuditFkColumn(edge.fkColumn())) continue;
                sentinelByTable.computeIfAbsent(FkGraph.norm(edge.parentTable()),
                        k -> new java.util.LinkedHashSet<>()).add(edge.fkColumn());
            }
        }
        for (var e : sentinelByTable.entrySet()) {
            builder.addSentinelRemap(new ImportPlan.SentinelRemap(
                    e.getKey(), new java.util.ArrayList<>(e.getValue())));
        }

        return builder.build();
    }

    /* ---------- helpers ---------- */

    private static ImportPlan.Environment buildEnv(String role, String env, String action, ProjectProfile p) {
        if (env == null) return null;
        String baseUrl = switch (env.toLowerCase(Locale.ROOT)) {
            case "design" -> p.designBaseUrl();
            case "sandbox" -> p.sandboxBaseUrl();
            default -> null;
        };
        String dbName = switch (env.toLowerCase(Locale.ROOT)) {
            case "design" -> firstNonBlank(p.sqlDbName(), p.dbServiceName());
            case "sandbox" -> firstNonBlank(p.sandboxSqlDbName(), p.dbServiceName());
            default -> null;
        };
        String pretty = Character.toUpperCase(env.charAt(0)) + env.substring(1).toLowerCase(Locale.ROOT);
        return new ImportPlan.Environment(role, pretty + " (" + action + ")", env.toLowerCase(Locale.ROOT), baseUrl, dbName);
    }

    private static Set<String> normaliseReferences(List<String> configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String s : configured) if (s != null && !s.isBlank()) out.add(FkGraph.norm(s.trim()));
        return out;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c;
        return null;
    }

    /**
     * Render the first row's INSERT preview using the same policies the
     * executor will use. FK columns show a {@code <pending: table.value>}
     * placeholder because their real target IDs are only known at execute time.
     */
    private static String renderSampleInsert(DataModel.Table table, Map<String, Object> row, ColumnPolicies pol) {
        Map<String, Object> setPairs = new LinkedHashMap<>();
        for (DataModel.Column col : table.columns()) {
            String cname = col.name();
            if (cname == null) continue;
            String lower = cname.toLowerCase(Locale.ROOT);
            if (pol.strippedLower.contains(lower)) continue;

            Object v = lookupColumn(row, cname);
            if (pol.uuidPkColumnsLower.contains(lower)) {
                setPairs.put(cname, "<new UUID at execute time>");
                continue;
            }
            ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
            if (remap != null) {
                if (remap.isReferenceTarget()) {
                    setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
                } else {
                    setPairs.put(cname, "<pending: " + remap.targetTable() + "." + v + ">");
                }
                continue;
            }
            setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
        }
        if (setPairs.isEmpty()) return "-- no columns to insert";
        // Render manually so placeholders are visible (SqlBuilder would quote them as strings)
        StringBuilder sb = new StringBuilder(128);
        sb.append("INSERT INTO ").append(SqlBuilder.ident(table.name())).append(" SET ");
        boolean first = true;
        for (Map.Entry<String, Object> e : setPairs.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(SqlBuilder.ident(e.getKey())).append(" = ");
            Object v = e.getValue();
            if (v instanceof String s && s.startsWith("<") && s.endsWith(">")) {
                sb.append(s);   // placeholder — don't quote
            } else {
                sb.append(SqlBuilder.literal(v));
            }
            first = false;
        }
        return sb.toString();
    }

    private static Object lookupColumn(Map<String, Object> row, String colName) {
        if (row == null || colName == null) return null;
        if (row.containsKey(colName)) return row.get(colName);
        String lower = colName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        // snake_case → camelCase fallback (executeSQLs sometimes echoes Hibernate field names, not DB columns)
        if (colName.indexOf('_') >= 0) {
            StringBuilder sb = new StringBuilder(colName.length());
            boolean up = false;
            for (int i = 0; i < colName.length(); i++) {
                char c = colName.charAt(i);
                if (c == '_') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
            String camel = sb.toString();
            if (row.containsKey(camel)) return row.get(camel);
            String cLower = camel.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(cLower)) return e.getValue();
            }
        }
        return null;
    }

    /**
     * FK column names that are "audit" — they identify the actor who
     * created/updated/deleted a row, not the row's business identity. The
     * specific target row (usually a User) doesn't matter across envs, so
     * these FKs get a sentinel remap (any target row) rather than natural-key
     * matching. Matched case-insensitively against the column name.
     */
    private static final Set<String> AUDIT_FK_COLUMNS = Set.of(
            "createdby", "createdbyid",
            "updatedby", "updatedbyid",
            "modifiedby", "modifiedbyid",
            "lastmodifiedby", "lastmodifiedbyid",
            "deletedby", "deletedbyid");

    /** Matches an audit-style FK column name (case-insensitive). */
    public static boolean isAuditFkColumn(String columnName) {
        return columnName != null
                && AUDIT_FK_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT));
    }

    /**
     * Column-level policies for one table. Computed once per table; the executor
     * applies the same rules to every row.
     */
    public static final class ColumnPolicies {
        public final String pkStrategy;
        public final List<String> pkColumns;
        public final Set<String> strippedLower;
        public final List<String> stripped;
        public final Set<String> uuidPkColumnsLower;
        public final List<String> uuidPkColumns;
        public final Map<String, ImportPlan.FkRemap> fkByColumnLower;
        public final List<ImportPlan.FkRemap> fkRemaps;
        public final String notes;

        private ColumnPolicies(String pkStrategy, List<String> pkColumns,
                               Set<String> strippedLower, List<String> stripped,
                               Set<String> uuidPkColumnsLower, List<String> uuidPkColumns,
                               Map<String, ImportPlan.FkRemap> fkByColumnLower,
                               List<ImportPlan.FkRemap> fkRemaps, String notes) {
            this.pkStrategy = pkStrategy;
            this.pkColumns = pkColumns;
            this.strippedLower = strippedLower;
            this.stripped = stripped;
            this.uuidPkColumnsLower = uuidPkColumnsLower;
            this.uuidPkColumns = uuidPkColumns;
            this.fkByColumnLower = fkByColumnLower;
            this.fkRemaps = fkRemaps;
            this.notes = notes;
        }

        public static ColumnPolicies compute(DataModel.Table table, FkGraph graph, Set<String> refSet) {
            String pkStrategy = "unknown";
            List<String> pkColumns = List.of();
            if (table.primaryKey() != null) {
                if (table.primaryKey().generator() != null
                        && table.primaryKey().generator().generatorType() != null) {
                    pkStrategy = table.primaryKey().generator().generatorType();
                }
                if (table.primaryKey().columns() != null) pkColumns = table.primaryKey().columns();
            }

            // FK columns keyed by (lowercased) source column name → remap target
            Map<String, ImportPlan.FkRemap> fkByColumn = new LinkedHashMap<>();
            List<ImportPlan.FkRemap> fkRemaps = new ArrayList<>();
            for (FkGraph.Edge edge : graph.parents(table.name())) {
                String targetTable = edge.parentTable();
                boolean refTarget = refSet.contains(FkGraph.norm(targetTable));
                boolean isAudit = isAuditFkColumn(edge.fkColumn());
                ImportPlan.FkRemap remap = new ImportPlan.FkRemap(
                        edge.fkColumn(), targetTable, edge.parentPkColumn(), refTarget, isAudit);
                fkByColumn.put(edge.fkColumn().toLowerCase(Locale.ROOT), remap);
                if (!refTarget) fkRemaps.add(remap);
            }

            List<String> stripped = new ArrayList<>();
            Set<String> strippedLower = new HashSet<>();
            List<String> uuidPkCols = new ArrayList<>();
            Set<String> uuidPkLower = new HashSet<>();
            Set<String> pkLower = new HashSet<>();
            for (String pk : pkColumns) if (pk != null) pkLower.add(pk.toLowerCase(Locale.ROOT));

            for (DataModel.Column col : safe(table.columns())) {
                String cname = col.name();
                if (cname == null) continue;
                String lower = cname.toLowerCase(Locale.ROOT);
                boolean isPk = col.primaryKey() || pkLower.contains(lower);

                // 1. Server-generated PK → strip (identity, sequence, uuid, guid, ...)
                if (isPk && !"assigned".equalsIgnoreCase(pkStrategy)) {
                    stripped.add(cname + " (" + pkStrategy + " PK — server-assigned)");
                    strippedLower.add(lower);
                    continue;
                }
                // 2. database-defined → strip
                if (col.columnValue() != null && "database-defined".equalsIgnoreCase(col.columnValue().type())) {
                    stripped.add(cname + " (database-defined)");
                    strippedLower.add(lower);
                    continue;
                }
                // 3. insertable=false → strip
                if (col.columnValue() != null && !col.columnValue().insertable()) {
                    stripped.add(cname + " (insertable=false)");
                    strippedLower.add(lower);
                    continue;
                }
                // 4. Assigned + UUID-shaped column + non-FK PK → regenerate UUID
                if (isPk && "assigned".equalsIgnoreCase(pkStrategy) && !col.foreignKey()
                        && looksUuidTyped(col)) {
                    uuidPkCols.add(cname);
                    uuidPkLower.add(lower);
                }
                // Everything else passes through (FK remap handled by fkByColumn)
            }

            String notes;
            if ("assigned".equalsIgnoreCase(pkStrategy)) {
                notes = "Assigned PK. " + (uuidPkCols.isEmpty()
                        ? "PK values passed through as-is."
                        : "UUID PK columns regenerated at execute time: " + String.join(", ", uuidPkCols) + ".");
            } else {
                notes = "Server-assigned PK (generator=" + pkStrategy + "). "
                        + fkRemaps.size() + " FK column(s) to remap.";
            }

            return new ColumnPolicies(pkStrategy, pkColumns, strippedLower, stripped,
                    uuidPkLower, uuidPkCols, fkByColumn, fkRemaps, notes);
        }

        /** Heuristic: assigned PK column that is string-typed with length 36 → treat as UUID. */
        private static boolean looksUuidTyped(DataModel.Column col) {
            String javaType = col.javaType();
            if (javaType == null) return false;
            String t = javaType.toLowerCase(Locale.ROOT);
            // WaveMaker uses "string" for VARCHAR/TEXT — the length check catches actual UUIDs
            return "string".equals(t) || "uuid".equals(t) || t.contains("uuid");
        }

        private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }
    }
}
