package com.devbridge.apps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A dry-run import plan built from a source-side FK-graph walk. One step per
 * table (topologically ordered — parents before children). Each step carries
 * the metadata a reviewer needs to decide whether the import looks right:
 * row count, PK strategy, columns that will be stripped, FK columns that will
 * be remapped, and a preview of the first INSERT statement.
 *
 * <p>The full row data lives elsewhere (in {@code AppSqlFetchService.FetchResult});
 * this record is UI-facing and stays small.
 */
public record ImportPlan(
        Environment source,
        Environment target,
        String rootTable,
        Object rootFilterValue,
        int totalTables,
        int totalRows,
        List<TableStep> steps,
        List<String> warnings,
        List<FacadeCrossCheck> facadeChecks,
        List<String> cyclicTables,
        List<ReferenceRemap> referenceRemaps,
        List<SentinelRemap> sentinelRemaps
) {

    /** Where data is fetched from and where it will be written to. */
    public record Environment(
            String role,         // "source" or "target"
            String label,        // e.g. "Design (read)"
            String env,          // "design" | "sandbox"
            String baseUrl,
            String dbName
    ) {}

    /**
     * One table's slice of the import. All rows in this table share the same
     * INSERT shape (columns, strip list, FK remap list); the values differ.
     */
    public record TableStep(
            int order,
            String tableName,
            int rowCount,
            String pkStrategy,               // identity | assigned | sequence | ...
            List<String> pkColumns,
            List<String> strippedColumns,    // omitted from INSERT (server-generated, insertable=false, etc.)
            List<String> uuidPkColumns,      // assigned UUID PKs we regenerate client-side
            List<FkRemap> fkColumns,         // FK columns that need id-map remap at execute time
            String sampleInsertSql,          // preview: first row rendered as INSERT ... SET
            String notes
    ) {}

    /** An FK column that will have its value replaced at execute time via the id-map. */
    public record FkRemap(
            String fkColumn,
            String targetTable,
            String targetPkColumn,
            boolean isReferenceTarget,       // true → pass-through (never remap; target row already exists)
            boolean useSentinel              // true → audit-style column (CreatedBy, UpdatedBy, ...). If the
                                             // regular id-map lookup misses, fall back to any-row sentinel from the target.
    ) {}

    /** One line of the facade cross-check — advisory only, never blocks execute. */
    public record FacadeCrossCheck(
            String facadeKey,
            String resolvedTable,            // null if we couldn't map the facade key to a table
            int facadeCount,
            int fkWalkCount,
            String status                    // "match" | "count-mismatch" | "unmapped" | "table-not-walked"
    ) {}

    /**
     * One entry per reference table whose id will be remapped from source
     * to target via natural-key matching. Surfaced in the plan preview so
     * the user can see exactly what the executor will do before running.
     *
     * @param source how this config was derived: {@code "auto"} =
     *   auto-detected from target-env unique indexes / heuristic;
     *   {@code "user"} = defined only in profile.referenceTableConfigs;
     *   {@code "override"} = auto-detected but replaced by a user override.
     */
    public record ReferenceRemap(
            String tableName,
            List<String> naturalKey,
            String activeFilter,
            String source
    ) {}

    /**
     * One entry per reference table that will receive a "sentinel" remap for
     * audit-column FKs (CreatedBy, UpdatedBy, ...) pointing at it. The
     * executor picks any target-side row from this table and substitutes its
     * id for all audit-column FKs whose original source value has no other
     * mapping. Purely informational — the executor uses the compiled
     * FkRemap.useSentinel flag, not this list.
     *
     * @param auditColumns FK column names on the app tables that pointed at
     *   this target and triggered its sentinel entry. Non-exhaustive helper
     *   for the UI.
     */
    public record SentinelRemap(
            String tableName,
            List<String> auditColumns
    ) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Environment source;
        private Environment target;
        private String rootTable;
        private Object rootFilterValue;
        private final List<TableStep> steps = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<FacadeCrossCheck> facadeChecks = new ArrayList<>();
        private final List<String> cyclicTables = new ArrayList<>();
        private final List<ReferenceRemap> referenceRemaps = new ArrayList<>();
        private final List<SentinelRemap> sentinelRemaps = new ArrayList<>();

        public Builder source(Environment e) { this.source = e; return this; }
        public Builder target(Environment e) { this.target = e; return this; }
        public Builder rootTable(String t) { this.rootTable = t; return this; }
        public Builder rootFilterValue(Object v) { this.rootFilterValue = v; return this; }
        public Builder addStep(TableStep s) { steps.add(s); return this; }
        public Builder warn(String msg) { warnings.add(msg); return this; }
        public Builder facadeCheck(FacadeCrossCheck c) { facadeChecks.add(c); return this; }
        public Builder cyclicTables(List<String> cyclic) {
            if (cyclic != null) cyclicTables.addAll(cyclic);
            return this;
        }
        public Builder addReferenceRemap(ReferenceRemap r) { referenceRemaps.add(r); return this; }
        public Builder addSentinelRemap(SentinelRemap r) { sentinelRemaps.add(r); return this; }

        public ImportPlan build() {
            int totalRows = 0;
            for (TableStep s : steps) totalRows += s.rowCount();
            return new ImportPlan(source, target, rootTable, rootFilterValue,
                    steps.size(), totalRows,
                    List.copyOf(steps),
                    List.copyOf(warnings),
                    List.copyOf(facadeChecks),
                    List.copyOf(cyclicTables),
                    List.copyOf(referenceRemaps),
                    List.copyOf(sentinelRemaps));
        }
    }

    /** Utility: empty INSERT preview stand-in when a row would produce no columns. */
    public static Map<String, Object> emptyBody() { return new LinkedHashMap<>(); }
}
