package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scans a {@link DataModel} for columns that <em>look</em> like they hold
 * reference ids but aren't declared as FKs. Powers the "Suggest candidates"
 * button on the profile edit form so the user doesn't have to hand-list
 * every non-FK domain-value column.
 *
 * <p>This is a naming-only heuristic — it never touches source data.
 * Users decide which suggestions to accept; suggestions default to
 * {@code DomainValue} as the target table (the overwhelmingly common
 * case in FAWB), but the UI lets the user override on a per-row basis.
 */
@Service
public class VirtualFkSuggester {

    /**
     * Suffixes that suggest a column stores a reference/lookup id.
     * Case-insensitive; matched against the column name's ending.
     */
    private static final List<String> DOMAIN_SUFFIXES = List.of(
            "status", "statusid",
            "type", "typeid",
            "category", "categoryid",
            "code", "codeid",
            "kind", "kindid",
            "reason", "reasonid",
            "flag", "flagid"
    );

    /** Integer-ish SQL types that can plausibly hold an id. */
    private static final Set<String> INT_SQL_TYPES = Set.of(
            "int", "integer", "bigint", "smallint", "tinyint", "mediumint",
            "int4", "int8", "int2", "serial", "bigserial"
    );

    /** Integer-ish Java types that can plausibly hold an id. */
    private static final Set<String> INT_JAVA_TYPES = Set.of(
            "int", "integer", "long", "short", "java.lang.integer",
            "java.lang.long", "java.lang.short", "java.math.biginteger"
    );

    private static final String DEFAULT_TARGET = "DomainValue";

    public record Suggestion(String sourceTable, String sourceColumn, String suggestedTarget) {}

    /**
     * Produce a suggestion list from the given dataModel. Never returns null;
     * empty result means nothing matched the heuristics. Ordering is stable —
     * table declaration order, then column declaration order.
     */
    public List<Suggestion> suggest(DataModel model) {
        List<Suggestion> out = new ArrayList<>();
        if (model == null || model.tables() == null) return out;

        FkGraph graph = FkGraph.from(model);  // real graph only — virtual not needed here

        for (DataModel.Table table : model.tables()) {
            if (table == null || table.name() == null) continue;
            if (table.type() != null && !"TABLE".equalsIgnoreCase(table.type())) continue;
            if (table.columns() == null) continue;

            Set<String> fkColumnsLower = fkColumnsLower(graph, table.name());
            Set<String> pkColumnsLower = pkColumnsLower(table);

            for (DataModel.Column col : table.columns()) {
                if (col == null || col.name() == null) continue;
                String colLower = col.name().toLowerCase(Locale.ROOT);

                if (col.primaryKey()) continue;
                if (pkColumnsLower.contains(colLower)) continue;
                if (fkColumnsLower.contains(colLower)) continue;
                if (col.foreignKey()) continue;
                if (AppSqlPlanService.isAuditFkColumn(col.name())) continue;
                if (!isIntegerLike(col.sqlType(), col.javaType())) continue;
                if (!matchesDomainSuffix(colLower)) continue;

                out.add(new Suggestion(table.name(), col.name(), DEFAULT_TARGET));
            }
        }
        return out;
    }

    private static Set<String> fkColumnsLower(FkGraph graph, String tableName) {
        Set<String> out = new HashSet<>();
        for (FkGraph.Edge edge : graph.parents(tableName)) {
            if (edge.fkColumn() != null) out.add(edge.fkColumn().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Set<String> pkColumnsLower(DataModel.Table t) {
        Set<String> out = new HashSet<>();
        if (t.primaryKey() != null && t.primaryKey().columns() != null) {
            for (String pk : t.primaryKey().columns()) {
                if (pk != null) out.add(pk.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean matchesDomainSuffix(String colLower) {
        for (String suffix : DOMAIN_SUFFIXES) {
            if (colLower.equals(suffix) || colLower.endsWith(suffix)) return true;
        }
        return false;
    }

    private static boolean isIntegerLike(String sqlType, String javaType) {
        if (sqlType != null && INT_SQL_TYPES.contains(sqlType.toLowerCase(Locale.ROOT))) return true;
        if (javaType != null && INT_JAVA_TYPES.contains(javaType.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
