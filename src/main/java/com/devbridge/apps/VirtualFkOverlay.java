package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.VirtualForeignKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Projects user-configured {@link VirtualForeignKey} entries onto a
 * {@link DataModel} by adding synthetic {@link DataModel.Relation} entries to
 * each source table. From the consumer's perspective (DB Explorer, SQL Runner
 * jump-to-referenced-row, ERD graph), a virtual FK becomes indistinguishable
 * from a real ManyToOne relation — same shape, same fields, same rendering
 * code path. The {@code virtual=true} flag lets the UI badge it distinctly.
 *
 * <p>This is a client-facing overlay only. The runtime import path uses
 * {@link FkGraph#from(DataModel, java.util.List)} which does its own injection
 * for the execution graph.
 */
public final class VirtualFkOverlay {

    private static final Logger log = LoggerFactory.getLogger(VirtualFkOverlay.class);

    /** Prefix on the synthetic relation's {@code name} so it's traceable in logs / debug dumps. */
    public static final String SYNTHETIC_RELATION_PREFIX = "_devbridgeVirtualFk_";

    private VirtualFkOverlay() {}

    /**
     * Return a new {@link DataModel} whose tables carry additional synthetic
     * relations for every valid virtual FK. Entries pointing at unknown source
     * or target tables, or naming a source column that doesn't exist, are
     * silently skipped (they're the config author's problem, not ours to crash
     * on here). Entries that would duplicate a real FK on the same
     * {@code (sourceTable, sourceColumn)} are also skipped so real definitions
     * always win.
     *
     * <p>Returns the original {@code model} unchanged when there are no valid
     * entries or when {@code virtualForeignKeys} is null/empty.
     */
    public static DataModel apply(DataModel model, List<VirtualForeignKey> virtualForeignKeys) {
        if (model == null || model.tables() == null) return model;
        if (virtualForeignKeys == null || virtualForeignKeys.isEmpty()) {
            log.debug("VirtualFkOverlay: no virtualForeignKeys on profile — nothing to overlay");
            return model;
        }

        // Index tables by uppercase name for case-insensitive lookup.
        java.util.Map<String, DataModel.Table> byUpper = new java.util.LinkedHashMap<>();
        for (DataModel.Table t : model.tables()) {
            if (t != null && t.name() != null) byUpper.put(t.name().toUpperCase(Locale.ROOT), t);
        }

        // Group valid virtual FKs by source table (uppercase). Skip anything
        // malformed or duplicating a real FK.
        java.util.Map<String, List<DataModel.Relation>> syntheticBySource = new java.util.LinkedHashMap<>();
        for (VirtualForeignKey v : virtualForeignKeys) {
            if (v == null) continue;
            if (isBlank(v.sourceTable()) || isBlank(v.sourceColumn()) || isBlank(v.targetTable())) continue;

            String sourceUpper = v.sourceTable().toUpperCase(Locale.ROOT);
            String targetUpper = v.targetTable().toUpperCase(Locale.ROOT);
            DataModel.Table sourceTable = byUpper.get(sourceUpper);
            DataModel.Table targetTable = byUpper.get(targetUpper);
            if (sourceTable == null || targetTable == null) continue;
            if (!columnExists(sourceTable, v.sourceColumn())) continue;
            if (columnAlreadyMapped(sourceTable, v.sourceColumn())) continue;

            String targetPk = firstPkColumn(targetTable);
            DataModel.Mapping mapping = new DataModel.Mapping(
                    v.sourceColumn(),
                    targetPk != null ? targetPk : "id");
            DataModel.Relation rel = new DataModel.Relation(
                    SYNTHETIC_RELATION_PREFIX + v.sourceColumn(),
                    "ManyToOne",
                    /* fieldName= */ v.sourceColumn(),
                    /* sourceTable= */ sourceTable.name(),
                    /* targetTable= */ targetTable.name(),
                    List.of(mapping),
                    /* cascadeEnabled= */ false,
                    /* virtual= */ true);
            syntheticBySource
                    .computeIfAbsent(sourceUpper, k -> new ArrayList<>())
                    .add(rel);
        }

        if (syntheticBySource.isEmpty()) {
            log.info("VirtualFkOverlay: {} entry(ies) configured but none were valid (unknown tables, missing columns, or duplicates of real FKs) — nothing overlaid",
                    virtualForeignKeys.size());
            return model;
        }
        int total = 0;
        for (List<DataModel.Relation> rels : syntheticBySource.values()) total += rels.size();
        log.info("VirtualFkOverlay: overlaid {} synthetic relation(s) across {} source table(s)",
                total, syntheticBySource.size());

        // Rebuild each affected table with an augmented relations list.
        List<DataModel.Table> newTables = new ArrayList<>(model.tables().size());
        for (DataModel.Table t : model.tables()) {
            if (t == null || t.name() == null) { newTables.add(t); continue; }
            List<DataModel.Relation> synths = syntheticBySource.get(t.name().toUpperCase(Locale.ROOT));
            if (synths == null || synths.isEmpty()) { newTables.add(t); continue; }
            List<DataModel.Relation> merged = new ArrayList<>();
            if (t.relations() != null) merged.addAll(t.relations());
            merged.addAll(synths);
            newTables.add(new DataModel.Table(
                    t.name(), t.entityName(), t.catalog(), t.type(),
                    t.columns(), t.primaryKey(), merged));
        }
        return new DataModel(model.name(), model.packageName(), newTables);
    }

    /* ---------- helpers ---------- */

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static boolean columnExists(DataModel.Table table, String column) {
        if (table.columns() == null) return false;
        String needle = column.toLowerCase(Locale.ROOT);
        for (DataModel.Column c : table.columns()) {
            if (c != null && c.name() != null
                    && c.name().toLowerCase(Locale.ROOT).equals(needle)) return true;
        }
        return false;
    }

    /**
     * Real-FK-wins: if a relation on the table already maps this column as an
     * FK source, don't emit a synthetic relation for it.
     */
    private static boolean columnAlreadyMapped(DataModel.Table table, String column) {
        if (table.relations() == null) return false;
        String needle = column.toLowerCase(Locale.ROOT);
        for (DataModel.Relation r : table.relations()) {
            if (r == null || r.mappings() == null) continue;
            String card = r.cardinality() == null ? "" : r.cardinality().toLowerCase(Locale.ROOT);
            if (!"manytoone".equals(card) && !"onetoone".equals(card)) continue;
            for (DataModel.Mapping m : r.mappings()) {
                if (m != null && m.sourceColumn() != null
                        && m.sourceColumn().toLowerCase(Locale.ROOT).equals(needle)) return true;
            }
        }
        return false;
    }

    private static String firstPkColumn(DataModel.Table t) {
        if (t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }
}
