package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;

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
        return new FkGraph(byName, byParent, byChild);
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
