package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.VirtualForeignKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Foreign-key graph derived from a {@link DataModel}. Tables are keyed by their
 * physical DB name (uppercase-normalised). Each ManyToOne / OneToOne relation
 * on a table is treated as an FK edge from that table (child) to its target
 * table (parent). ManyToMany and OneToMany aren't real FK edges on the source
 * side, so they're ignored here.
 *
 * <p>The graph is directional: a call to {@link #children(String)} returns
 * tables that FK INTO the given table (i.e., they need this table's rows to
 * exist first). {@link #parents(String)} returns tables this one depends on.
 * {@link #topoSort} orders a subset so parents come before children — the
 * insert order.
 */
public final class FkGraph {

    private static final Logger log = LoggerFactory.getLogger(FkGraph.class);

    /** An FK edge from a child table to a parent table. */
    public record Edge(String parentTable, String childTable,
                       String fkColumn, String parentPkColumn) {}

    private final Map<String, DataModel.Table> tableByName;
    private final Map<String, List<Edge>> edgesByParent;
    private final Map<String, List<Edge>> edgesByChild;

    private FkGraph(Map<String, DataModel.Table> tableByName,
                    Map<String, List<Edge>> edgesByParent,
                    Map<String, List<Edge>> edgesByChild) {
        this.tableByName = tableByName;
        this.edgesByParent = edgesByParent;
        this.edgesByChild = edgesByChild;
    }

    /** Build the graph from a dataModel. Ignores views. */
    public static FkGraph from(DataModel model) {
        return from(model, null);
    }

    /**
     * Build the graph from a dataModel AND inject synthetic edges for the
     * profile's {@link VirtualForeignKey} entries. Synthetic edges are
     * appended AFTER real ones, and are dedup'd against real edges on the
     * same {@code (childTable, fkColumn)} pair — if the dataModel already
     * declares an FK on that column, the virtual entry is silently ignored
     * (real wins). Entries pointing at unknown tables, or with a source
     * column not present on the source table, are logged and skipped.
     *
     * <p>See {@link VirtualForeignKey} for why this exists.
     */
    public static FkGraph from(DataModel model, List<VirtualForeignKey> virtualForeignKeys) {
        Map<String, DataModel.Table> byName = new LinkedHashMap<>();
        Map<String, List<Edge>> byParent = new LinkedHashMap<>();
        Map<String, List<Edge>> byChild = new LinkedHashMap<>();

        if (model == null || model.tables() == null) {
            return new FkGraph(byName, byParent, byChild);
        }

        for (DataModel.Table t : model.tables()) {
            if (t == null || t.name() == null) continue;
            if (t.type() != null && !"TABLE".equalsIgnoreCase(t.type())) continue;
            byName.put(norm(t.name()), t);
        }

        for (DataModel.Table child : byName.values()) {
            if (child.relations() == null) continue;
            for (DataModel.Relation rel : child.relations()) {
                if (rel == null) continue;
                if (!isFkRelation(rel.cardinality())) continue;
                if (rel.targetTable() == null || rel.mappings() == null || rel.mappings().isEmpty()) continue;
                String parent = norm(rel.targetTable());
                if (!byName.containsKey(parent)) continue;   // dangling FK — skip
                // A relation can span multiple columns (composite FK). Emit one edge per mapping.
                for (DataModel.Mapping m : rel.mappings()) {
                    if (m == null || m.sourceColumn() == null) continue;
                    Edge edge = new Edge(parent, norm(child.name()),
                            m.sourceColumn(),
                            m.targetColumn() != null ? m.targetColumn() : "id");
                    byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(edge);
                    byChild.computeIfAbsent(norm(child.name()), k -> new ArrayList<>()).add(edge);
                }
            }
        }
        injectVirtualEdges(virtualForeignKeys, byName, byParent, byChild);
        return new FkGraph(byName, byParent, byChild);
    }

    /**
     * Append virtual-FK edges to the graph. Real edges have already been
     * emitted; anything that would collide on {@code (childTable, fkColumn)}
     * is skipped so real declarations always win.
     */
    private static void injectVirtualEdges(List<VirtualForeignKey> virtualForeignKeys,
                                           Map<String, DataModel.Table> byName,
                                           Map<String, List<Edge>> byParent,
                                           Map<String, List<Edge>> byChild) {
        if (virtualForeignKeys == null || virtualForeignKeys.isEmpty()) return;
        int injected = 0;
        int skipped = 0;
        for (VirtualForeignKey v : virtualForeignKeys) {
            if (v == null) continue;
            if (v.sourceTable() == null || v.sourceTable().isBlank()
                    || v.sourceColumn() == null || v.sourceColumn().isBlank()
                    || v.targetTable() == null || v.targetTable().isBlank()) {
                log.warn("virtualForeignKeys: skipping incomplete entry {}", v);
                skipped++;
                continue;
            }
            String child = norm(v.sourceTable());
            String parent = norm(v.targetTable());
            DataModel.Table childTable = byName.get(child);
            DataModel.Table parentTable = byName.get(parent);
            if (childTable == null) {
                log.warn("virtualForeignKeys: unknown source table '{}' — skipping", v.sourceTable());
                skipped++;
                continue;
            }
            if (parentTable == null) {
                log.warn("virtualForeignKeys: unknown target table '{}' (from {}.{}) — skipping",
                        v.targetTable(), v.sourceTable(), v.sourceColumn());
                skipped++;
                continue;
            }
            if (!columnExists(childTable, v.sourceColumn())) {
                log.warn("virtualForeignKeys: column '{}' not found on table '{}' — skipping",
                        v.sourceColumn(), v.sourceTable());
                skipped++;
                continue;
            }
            String colLower = v.sourceColumn().toLowerCase(Locale.ROOT);
            boolean alreadyDeclared = false;
            for (Edge existing : byChild.getOrDefault(child, List.of())) {
                if (existing.fkColumn() != null
                        && existing.fkColumn().toLowerCase(Locale.ROOT).equals(colLower)) {
                    alreadyDeclared = true;
                    break;
                }
            }
            if (alreadyDeclared) {
                log.info("virtualForeignKeys: {}.{} already has a real FK — real wins, virtual skipped",
                        v.sourceTable(), v.sourceColumn());
                skipped++;
                continue;
            }
            String targetPk = firstPk(parentTable);
            Edge edge = new Edge(parent, child, v.sourceColumn(),
                    targetPk != null ? targetPk : "id");
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(edge);
            byChild.computeIfAbsent(child, k -> new ArrayList<>()).add(edge);
            injected++;
        }
        if (injected > 0 || skipped > 0) {
            log.info("virtualForeignKeys: injected {} synthetic edge(s), skipped {}", injected, skipped);
        }
    }

    private static boolean columnExists(DataModel.Table table, String column) {
        if (table == null || column == null || table.columns() == null) return false;
        String colLower = column.toLowerCase(Locale.ROOT);
        for (DataModel.Column c : table.columns()) {
            if (c != null && c.name() != null
                    && c.name().toLowerCase(Locale.ROOT).equals(colLower)) return true;
        }
        return false;
    }

    private static String firstPk(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }

    /** Tables that FK into the given parent (i.e., its dependent tables). */
    public List<Edge> children(String parentTable) {
        return edgesByParent.getOrDefault(norm(parentTable), List.of());
    }

    /** Tables the given child depends on. */
    public List<Edge> parents(String childTable) {
        return edgesByChild.getOrDefault(norm(childTable), List.of());
    }

    /** Look up a table by physical name (case-insensitive). */
    public Optional<DataModel.Table> table(String name) {
        return Optional.ofNullable(tableByName.get(norm(name)));
    }

    /** All tables in the graph (uppercase names). */
    public Set<String> allTableNames() {
        return tableByName.keySet();
    }

    /**
     * Kahn's algorithm on the subgraph induced by {@code subset}. Parents
     * appear before children. Tables not in {@code subset} are ignored.
     *
     * <p>If a cycle exists in the subgraph (self-reference or mutual FKs),
     * the offending tables are appended at the end and returned via
     * {@link TopoResult#cyclic}. Callers should surface this as a warning —
     * cyclic groups typically need a two-pass insert (NULL FK first, UPDATE
     * later) which the executor handles separately.
     */
    public TopoResult topoSort(Collection<String> subset) {
        Set<String> normSubset = new LinkedHashSet<>();
        for (String s : subset) if (s != null) normSubset.add(norm(s));

        // in-degree from parents within the subset
        Map<String, Integer> indegree = new HashMap<>();
        for (String t : normSubset) indegree.put(t, 0);
        for (String t : normSubset) {
            for (Edge e : parents(t)) {
                if (normSubset.contains(e.parentTable()) && !e.parentTable().equals(t)) {
                    indegree.merge(t, 1, Integer::sum);
                }
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        // Seed with tables whose in-degree is 0, in the order they appear in subset
        for (String t : normSubset) if (indegree.get(t) == 0) queue.add(t);

        List<String> ordered = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        while (!queue.isEmpty()) {
            String t = queue.poll();
            if (!emitted.add(t)) continue;
            ordered.add(t);
            for (Edge e : children(t)) {
                if (!normSubset.contains(e.childTable())) continue;
                if (e.childTable().equals(t)) continue;   // self-loop
                int newDeg = indegree.merge(e.childTable(), -1, Integer::sum);
                if (newDeg == 0) queue.add(e.childTable());
            }
        }

        List<String> cyclic = new ArrayList<>();
        for (String t : normSubset) if (!emitted.contains(t)) cyclic.add(t);
        // Append cyclic tables at the end (arbitrary intra-group order)
        ordered.addAll(cyclic);
        return new TopoResult(ordered, cyclic);
    }

    public record TopoResult(List<String> ordered, List<String> cyclic) {
        public boolean hasCycles() { return cyclic != null && !cyclic.isEmpty(); }
    }

    /**
     * Same as {@link #topoSort} but returns tables grouped by FK depth level.
     * All tables within the same level have no FK dependency on each other and
     * can be inserted in parallel. The last entry contains any cyclic tables.
     */
    public List<List<String>> topoLevels(Collection<String> subset) {
        Set<String> normSubset = new LinkedHashSet<>();
        for (String s : subset) if (s != null) normSubset.add(norm(s));

        Map<String, Integer> indegree = new HashMap<>();
        for (String t : normSubset) indegree.put(t, 0);
        for (String t : normSubset) {
            for (Edge e : parents(t)) {
                if (normSubset.contains(e.parentTable()) && !e.parentTable().equals(t)) {
                    indegree.merge(t, 1, Integer::sum);
                }
            }
        }

        List<List<String>> levels = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        List<String> current = new ArrayList<>();
        for (String t : normSubset) if (indegree.get(t) == 0) current.add(t);

        while (!current.isEmpty()) {
            levels.add(new ArrayList<>(current));
            for (String t : current) emitted.add(t);
            List<String> next = new ArrayList<>();
            for (String t : current) {
                for (Edge e : children(t)) {
                    if (!normSubset.contains(e.childTable())) continue;
                    if (e.childTable().equals(t)) continue;
                    int deg = indegree.merge(e.childTable(), -1, Integer::sum);
                    if (deg == 0 && !emitted.contains(e.childTable())) next.add(e.childTable());
                }
            }
            current = next;
        }
        List<String> cyclic = new ArrayList<>();
        for (String t : normSubset) if (!emitted.contains(t)) cyclic.add(t);
        if (!cyclic.isEmpty()) levels.add(cyclic);
        return levels;
    }

    /* ---------- helpers ---------- */

    private static boolean isFkRelation(String cardinality) {
        return "ManyToOne".equalsIgnoreCase(cardinality)
                || "OneToOne".equalsIgnoreCase(cardinality);
    }

    /** Uppercase-normalise a table name for case-insensitive lookup. */
    static String norm(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
