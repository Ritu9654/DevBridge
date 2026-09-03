package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.profile.ReferenceTableConfig;
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
import java.util.Set;

/**
 * Auto-detects which tables are "reference" candidates for a given app import
 * and derives each one's natural key + active filter, so the user doesn't
 * need to hand-write configs for every shared lookup table.
 *
 * <p>Detection logic:
 * <ol>
 *   <li>A table is a reference candidate if some fetched app row has an FK
 *   pointing INTO it, but the walker never fetched that table itself.
 *   (Walker goes strictly downstream from the root; everything upstream that
 *   gets referenced is definitionally reference-shaped — shared across apps.)</li>
 *   <li>Natural key: prefer real unique indexes from the target env
 *   ({@code information_schema.STATISTICS}, {@code NON_UNIQUE = 0}). If a
 *   table has multiple unique indexes, the shortest wins; ties broken by
 *   presence of "natural"-looking columns (Code, Name, Key, ...). If no
 *   unique index exists, fall back to a heuristic: pick any of
 *   {@code Code}/{@code Name}/{@code Description}/{@code Identifier} that
 *   exists on the table, plus any FK column to another candidate.</li>
 *   <li>Active filter: if the table has a column named
 *   {@code IsActive}/{@code Active}/{@code Enabled}, use
 *   {@code IsActive=1} (or the actual column name). Else no filter.</li>
 * </ol>
 *
 * <p>User overrides via {@link ProjectProfile#referenceTableConfigs()} always
 * win over detection — use {@link #merge(Map, Map)} for that.
 */
@Service
public class ReferenceConfigDetector {

    private static final Logger log = LoggerFactory.getLogger(ReferenceConfigDetector.class);

    /** Column names that make a "natural key" more human-recognisable. Used to rank ties among unique indexes. */
    private static final Set<String> NATURAL_KEY_HINTS = Set.of("code", "name", "description", "identifier", "key");
    /** Column names to look for when auto-deriving activeFilter. */
    private static final List<String> ACTIVE_COLUMN_CANDIDATES = List.of("IsActive", "Active", "Enabled", "IS_ACTIVE");
    /**
     * Audit / bookkeeping column names that must NEVER end up in an auto-detected
     * natural key. These are per-row metadata (who/when), unrelated to the row's
     * identity in the business sense, and typically diverge across envs.
     */
    private static final Set<String> AUDIT_COLUMNS = Set.of(
            "createdby", "createdon", "createdat", "createddate",
            "updatedby", "updatedon", "updatedat", "updateddate",
            "modifiedby", "modifiedon", "modifiedat", "modifieddate",
            "version");

    private final SchemaMetadataService schemaMetadata;

    public ReferenceConfigDetector(SchemaMetadataService schemaMetadata) {
        this.schemaMetadata = schemaMetadata;
    }

    /**
     * Compute the auto-detected reference configs for this fetch, against
     * {@code targetEnv}'s schema. Iterates to fixed point: an initial set of
     * candidates (upstream tables referenced by fetched app rows) is expanded
     * whenever a computed natural key contains an FK to a table that hasn't
     * been processed yet. For example, {@code DOMAINVALUE}'s natural key of
     * {@code (Code, DomainValueType)} pulls {@code DomainValueType} in as a
     * transitive candidate even though no fetched app row FKs directly at it.
     * <p>
     * Never throws — failure to detect (permissions, missing information_schema
     * access) results in an empty map plus a WARN log.
     */
    public Map<String, ReferenceTableConfig> detectFor(FetchResult fetch, ProjectProfile profile,
                                                       String targetEnv) {
        Set<String> initial = collectCandidates(fetch);
        if (initial.isEmpty()) return Map.of();

        // Track already-fetched app tables so we never treat one as reference.
        Set<String> walked = new HashSet<>();
        for (String t : fetch.rowsByTable().keySet()) walked.add(FkGraph.norm(t));

        // Batch-fetch unique indexes for the initial set. Any transitive
        // discoveries later fire a small one-off query.
        Map<String, List<SchemaMetadataService.UniqueIndex>> indexesByTable =
                new LinkedHashMap<>(schemaMetadata.fetchUniqueIndexes(profile, targetEnv, initial));

        Map<String, ReferenceTableConfig> out = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(initial);
        Set<String> attempted = new HashSet<>();

        while (!queue.isEmpty()) {
            String tableUpper = FkGraph.norm(queue.poll());
            if (!attempted.add(tableUpper)) continue;
            if (walked.contains(tableUpper)) continue;   // in-app data — not reference
            DataModel.Table t = fetch.graph().table(tableUpper).orElse(null);
            if (t == null) continue;

            // On-demand unique-index fetch for transitively discovered tables
            // that weren't in the original batch.
            if (!indexesByTable.containsKey(tableUpper)) {
                indexesByTable.putAll(schemaMetadata.fetchUniqueIndexes(
                        profile, targetEnv, java.util.Set.of(tableUpper)));
            }
            List<SchemaMetadataService.UniqueIndex> idxs =
                    indexesByTable.getOrDefault(tableUpper, List.of());

            List<String> naturalKey = pickNaturalKey(t, idxs, fetch.graph(), walked);
            if (naturalKey.isEmpty()) {
                log.warn("Reference auto-detect: no natural key discoverable for '{}' — skipping. "
                        + "Add an explicit config in profile.referenceTableConfigs if this table is referenced.", tableUpper);
                continue;
            }
            String activeFilter = pickActiveFilter(t);
            out.put(tableUpper, new ReferenceTableConfig(naturalKey, activeFilter));

            // Discover transitive candidates: any FK column in this table's
            // natural key whose target isn't already configured / walked.
            for (String col : naturalKey) {
                String fkTarget = fkTargetOfColumn(t, col, fetch.graph());
                if (fkTarget == null) continue;
                String norm = FkGraph.norm(fkTarget);
                if (out.containsKey(norm) || walked.contains(norm) || attempted.contains(norm)) continue;
                queue.add(norm);
            }
        }
        // Post-process: if a table's natural key contains an FK to a table that
        // didn't end up in configs (e.g. we skipped it because it had no
        // discoverable natural key), drop that column. If pruning leaves the
        // natural key empty, drop the whole config — else validateConfigs would
        // halt the entire import at Phase A, blocking downstream tables that
        // might have been fine.
        pruneOrphanFkColumns(out, fetch.graph(), walked);

        if (!out.isEmpty()) {
            log.info("Reference auto-detect: {} table(s) — {}", out.size(), summarize(out));
        }
        return out;
    }

    /**
     * Remove natural-key FK columns whose target table isn't itself configured
     * (and isn't walked). Iterates until stable: dropping one column may
     * empty a table's key, which then triggers dropping its own dependents.
     */
    private static void pruneOrphanFkColumns(Map<String, ReferenceTableConfig> out,
                                             FkGraph graph, Set<String> walked) {
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, ReferenceTableConfig> replacement = new LinkedHashMap<>();
            for (Map.Entry<String, ReferenceTableConfig> e : out.entrySet()) {
                String tableUpper = e.getKey();
                ReferenceTableConfig cfg = e.getValue();
                DataModel.Table t = graph.table(tableUpper).orElse(null);
                if (t == null) continue;
                List<String> filtered = new ArrayList<>();
                List<String> dropped = new ArrayList<>();
                for (String col : cfg.naturalKey()) {
                    String fkTarget = fkTargetOfColumn(t, col, graph);
                    if (fkTarget == null) { filtered.add(col); continue; }
                    String norm = FkGraph.norm(fkTarget);
                    if (walked.contains(norm)) {
                        // Already excluded upstream in pickNaturalKey, but be defensive.
                        dropped.add(col);
                        continue;
                    }
                    if (!out.containsKey(norm)) {
                        dropped.add(col);
                        continue;
                    }
                    filtered.add(col);
                }
                if (!dropped.isEmpty()) {
                    log.warn("Reference auto-detect [{}]: dropping natural-key column(s) {} — "
                            + "FK target has no discoverable config. Add an explicit override in "
                            + "profile.referenceTableConfigs if this needs remapping.", tableUpper, dropped);
                    changed = true;
                }
                if (filtered.isEmpty()) {
                    log.warn("Reference auto-detect [{}]: no natural-key columns left after pruning — "
                            + "dropping table from auto-config. FKs pointing here will fall back to pass-through.", tableUpper);
                    changed = true;
                    continue;
                }
                replacement.put(tableUpper, new ReferenceTableConfig(filtered, cfg.activeFilter()));
            }
            out.clear();
            out.putAll(replacement);
        }
    }

    private static String fkTargetOfColumn(DataModel.Table t, String column, FkGraph graph) {
        if (t == null || column == null) return null;
        String colLower = column.toLowerCase(Locale.ROOT);
        for (FkGraph.Edge edge : graph.parents(t.name())) {
            if (edge.fkColumn() != null && edge.fkColumn().toLowerCase(Locale.ROOT).equals(colLower)) {
                return edge.parentTable();
            }
        }
        return null;
    }

    /**
     * Combine {@code detected} with the user's {@code userOverrides}. User
     * config wins per-table (any entry present in userOverrides replaces the
     * detected one entirely). Missing user overrides leave the detected
     * config in place. Missing detection with an override present passes
     * the override through.
     */
    public static Map<String, ReferenceTableConfig> merge(Map<String, ReferenceTableConfig> detected,
                                                          Map<String, ReferenceTableConfig> userOverrides) {
        Map<String, ReferenceTableConfig> merged = new LinkedHashMap<>();
        if (detected != null) {
            for (Map.Entry<String, ReferenceTableConfig> e : detected.entrySet()) {
                if (e.getValue() != null) merged.put(FkGraph.norm(e.getKey()), e.getValue());
            }
        }
        if (userOverrides != null) {
            for (Map.Entry<String, ReferenceTableConfig> e : userOverrides.entrySet()) {
                if (e.getValue() != null) merged.put(FkGraph.norm(e.getKey()), e.getValue());
            }
        }
        return merged;
    }

    /* ---------- candidates ---------- */

    /**
     * A table is a reference candidate iff:
     *   (a) at least one fetched app row has a NON-audit FK pointing into it, AND
     *   (b) the walker never fetched this table (i.e., it's not in fetch.rowsByTable).
     * <p>
     * Audit-only-referenced tables (typically USER via CreatedBy/UpdatedBy only)
     * are handled by the separate sentinel-remap phase, not by natural-key
     * matching — trying to natural-key-match a random per-row user id across
     * environments has no reliable stable key.
     */
    private static Set<String> collectCandidates(FetchResult fetch) {
        Set<String> walked = new HashSet<>();
        for (String t : fetch.rowsByTable().keySet()) walked.add(FkGraph.norm(t));
        Set<String> candidates = new LinkedHashSet<>();
        for (String appTable : fetch.rowsByTable().keySet()) {
            for (FkGraph.Edge edge : fetch.graph().parents(appTable)) {
                String parent = FkGraph.norm(edge.parentTable());
                if (walked.contains(parent)) continue;
                if (AppSqlPlanService.isAuditFkColumn(edge.fkColumn())) continue;
                candidates.add(parent);
            }
        }
        return candidates;
    }

    /* ---------- natural key selection ---------- */

    private static List<String> pickNaturalKey(DataModel.Table table,
                                               List<SchemaMetadataService.UniqueIndex> indexes,
                                               FkGraph graph, Set<String> walkedTablesUpper) {
        // Skip any unique index that only contains audit columns or contains the PK id column.
        String pkCol = firstPkColumn(table);
        String pkColLower = pkCol == null ? null : pkCol.toLowerCase(Locale.ROOT);
        List<SchemaMetadataService.UniqueIndex> usable = new ArrayList<>();
        for (SchemaMetadataService.UniqueIndex ui : indexes) {
            if (ui.columns() == null || ui.columns().isEmpty()) continue;
            boolean containsPk = false;
            for (String c : ui.columns()) {
                if (c != null && c.toLowerCase(Locale.ROOT).equals(pkColLower)) { containsPk = true; break; }
            }
            if (containsPk) continue;    // unique index that includes the id is just the PK in disguise
            // Reject unique indexes that include an FK to a walked (app-scoped)
            // table. Even though the DB enforces that combination as unique,
            // we can't resolve it in Phase A because the walker inserts happen
            // after — so the target-side lookup would need a per-app id we
            // don't yet know. Fall through to heuristic in that case.
            boolean includesWalkedFk = false;
            if (walkedTablesUpper != null) {
                for (String c : ui.columns()) {
                    String fkTarget = fkTargetOfColumn(table, c, graph);
                    if (fkTarget != null && walkedTablesUpper.contains(FkGraph.norm(fkTarget))) {
                        includesWalkedFk = true;
                        break;
                    }
                }
            }
            if (includesWalkedFk) continue;
            usable.add(ui);
        }
        if (!usable.isEmpty()) {
            usable.sort((a, b) -> {
                int c = Integer.compare(a.columns().size(), b.columns().size());
                if (c != 0) return c;
                // Tie-break: prefer indexes containing "natural"-looking columns.
                return Integer.compare(scoreNaturalness(b.columns()), scoreNaturalness(a.columns()));
            });
            return new ArrayList<>(usable.get(0).columns());
        }

        // Heuristic fallback: no unique index available.
        List<String> heuristic = new ArrayList<>();
        Set<String> tableColsLower = new HashSet<>();
        for (DataModel.Column c : safe(table.columns())) {
            if (c != null && c.name() != null) tableColsLower.add(c.name().toLowerCase(Locale.ROOT));
        }
        for (String candidate : List.of("Code", "Name", "Description", "Identifier", "Key")) {
            if (tableColsLower.contains(candidate.toLowerCase(Locale.ROOT))) {
                heuristic.add(properCase(table, candidate));
                break;
            }
        }
        // Also include any non-audit FK column that points at another
        // reference table (i.e. NOT one the walker already fetched as
        // app-scoped data). FKs to walked tables get their remapped values
        // from the walker's id-map at execute time — they don't belong in a
        // natural key because their per-app value isn't a stable identity
        // across envs.
        for (FkGraph.Edge edge : graph.parents(table.name())) {
            if (edge.fkColumn() == null) continue;
            String lowerFk = edge.fkColumn().toLowerCase(Locale.ROOT);
            if (AUDIT_COLUMNS.contains(lowerFk)) continue;
            if (walkedTablesUpper != null
                    && walkedTablesUpper.contains(FkGraph.norm(edge.parentTable()))) {
                continue;
            }
            if (!heuristic.contains(edge.fkColumn())) heuristic.add(edge.fkColumn());
        }
        return heuristic;
    }

    private static int scoreNaturalness(List<String> cols) {
        int score = 0;
        for (String c : cols) {
            if (c != null && NATURAL_KEY_HINTS.contains(c.toLowerCase(Locale.ROOT))) score++;
        }
        return score;
    }

    /* ---------- active filter ---------- */

    private static String pickActiveFilter(DataModel.Table table) {
        for (DataModel.Column col : safe(table.columns())) {
            if (col == null || col.name() == null) continue;
            for (String candidate : ACTIVE_COLUMN_CANDIDATES) {
                if (col.name().equalsIgnoreCase(candidate)) {
                    return col.name() + "=1";
                }
            }
        }
        return null;
    }

    /* ---------- helpers ---------- */

    private static String firstPkColumn(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }

    private static String properCase(DataModel.Table table, String candidateLower) {
        for (DataModel.Column c : safe(table.columns())) {
            if (c != null && c.name() != null && c.name().equalsIgnoreCase(candidateLower)) return c.name();
        }
        return candidateLower;
    }

    private static <T> List<T> safe(List<T> list) { return list == null ? List.of() : list; }

    private static String summarize(Map<String, ReferenceTableConfig> configs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, ReferenceTableConfig> e : configs.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("(").append(String.join("+", e.getValue().naturalKey()));
            if (e.getValue().activeFilter() != null) sb.append(" +active");
            sb.append(")");
            first = false;
        }
        return sb.toString();
    }
}
