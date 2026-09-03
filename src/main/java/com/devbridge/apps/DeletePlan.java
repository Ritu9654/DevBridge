package com.devbridge.apps;

import java.util.ArrayList;
import java.util.List;

/**
 * A dry-run delete plan built from an env-side FK-graph walk. One step per
 * table in reverse-topological order (children before parents — the DELETE
 * order). Deliberately smaller than {@link ImportPlan}: no FK remaps, no PK
 * regen, no facade cross-check. All the reviewer needs is which tables get
 * hit, how many rows each, and a sample DELETE statement.
 */
public record DeletePlan(
        Environment env,
        String rootTable,
        Object rootFilterValue,
        int totalTables,
        int totalRows,
        List<TableStep> steps,
        List<String> warnings,
        List<String> cyclicTables
) {

    /** The single env being deleted from. */
    public record Environment(
            String role,         // always "target" for delete
            String label,        // e.g. "Sandbox (delete from)"
            String env,          // "sandbox" (only sandbox is supported)
            String baseUrl,
            String dbName
    ) {}

    /** One table's slice of the delete. All rows share the same PK column. */
    public record TableStep(
            int order,                // 1-based delete order (children first)
            String tableName,
            String entityName,        // dataModel entityName; may be null
            int rowCount,
            String pkColumn,
            String sampleDeleteSql,   // preview: DELETE FROM T WHERE pk = <first row's pk>
            String notes
    ) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Environment env;
        private String rootTable;
        private Object rootFilterValue;
        private final List<TableStep> steps = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> cyclicTables = new ArrayList<>();

        public Builder env(Environment e) { this.env = e; return this; }
        public Builder rootTable(String t) { this.rootTable = t; return this; }
        public Builder rootFilterValue(Object v) { this.rootFilterValue = v; return this; }
        public Builder addStep(TableStep s) { steps.add(s); return this; }
        public Builder warn(String msg) { warnings.add(msg); return this; }
        public Builder cyclicTables(List<String> cyclic) {
            if (cyclic != null) cyclicTables.addAll(cyclic);
            return this;
        }

        public DeletePlan build() {
            int totalRows = 0;
            for (TableStep s : steps) totalRows += s.rowCount();
            return new DeletePlan(env, rootTable, rootFilterValue,
                    steps.size(), totalRows,
                    List.copyOf(steps),
                    List.copyOf(warnings),
                    List.copyOf(cyclicTables));
        }
    }
}
