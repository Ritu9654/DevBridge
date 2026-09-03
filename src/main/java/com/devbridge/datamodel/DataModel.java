package com.devbridge.datamodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * In-memory representation of a FAWB / WaveMaker <code>*_published_dataModel.json</code>.
 * Jackson deserialises by field name. Unknown fields are ignored so the model
 * stays forward-compatible with FAWB releases that add new keys.
 *
 * <p>Only the fields we need are declared. As the walker grows we'll add more;
 * everything else in the raw JSON is silently dropped on parse.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DataModel(
        String name,
        String packageName,
        List<Table> tables
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(
            String name,          // physical DB table name (e.g., "APPLICATION")
            String entityName,    // FAWB entity name (e.g., "Application")
            String catalog,
            String type,          // "TABLE" | "VIEW"
            List<Column> columns,
            @JsonProperty("primaryKey") PrimaryKey primaryKey,
            List<Relation> relations
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(
            String name,          // DB column name
            String fieldName,     // FAWB Java field name (may differ from name)
            String sqlType,
            String javaType,
            boolean nullable,
            boolean primaryKey,
            boolean foreignKey,
            String generatorType, // "identity" | "assigned" | "sequence" | ...
            ColumnValue columnValue,
            Mask mask
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnValue(
            boolean insertable,
            boolean updatable,
            String defaultValue,
            String type           // "user-defined" | "database-defined"
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mask(
            String type,          // "NONE" | "CUSTOM" | ...
            String className
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrimaryKey(
            List<String> columns,
            boolean composite,
            Generator generator,
            boolean virtual
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Generator(
            String generatorType, // "identity" | "assigned" | ...
            String generatorValue
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(
            String name,
            String cardinality,   // "ManyToOne" | "OneToMany" | "OneToOne" | "ManyToMany"
            String fieldName,
            String sourceTable,
            String targetTable,
            List<Mapping> mappings,
            boolean cascadeEnabled,
            boolean virtual
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mapping(
            String sourceColumn,
            String targetColumn
    ) {}

    /* ---------- Convenience counters, used for summary responses ---------- */

    public int tableCount() {
        return tables == null ? 0 : tables.size();
    }

    public int columnCount() {
        if (tables == null) return 0;
        int total = 0;
        for (Table t : tables) if (t.columns() != null) total += t.columns().size();
        return total;
    }

    public int relationCount() {
        if (tables == null) return 0;
        int total = 0;
        for (Table t : tables) if (t.relations() != null) total += t.relations().size();
        return total;
    }

    public int virtualRelationCount() {
        if (tables == null) return 0;
        int total = 0;
        for (Table t : tables) {
            if (t.relations() != null) {
                for (Relation r : t.relations()) if (r.virtual()) total++;
            }
        }
        return total;
    }
}
