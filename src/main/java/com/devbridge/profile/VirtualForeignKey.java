package com.devbridge.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A "virtual" foreign key — a column that logically holds ids from another
 * table but is NOT declared as an FK in the DB schema (or the dataModel).
 * Common case: a column holding a {@code DomainValue.id} where the FAWB
 * team chose not to add a hard FK constraint. The reference-remap engine
 * needs to know about these columns so it can rewrite their values from
 * source ids to target ids at insert time — otherwise the source id gets
 * copied blindly and FAWB's app-submit validator rejects it as "unknown
 * domain value" in the local env.
 *
 * <p>Configured on {@link ProjectProfile#virtualForeignKeys()}. At plan
 * time each entry is projected into the {@link com.devbridge.apps.FkGraph}
 * as a synthetic edge; from that point everything downstream (candidate
 * detection, natural-key remap, executor FK substitution) treats it
 * identically to a real FK.
 *
 * @param sourceTable  physical DB table name that owns the column
 *     (e.g. {@code Application})
 * @param sourceColumn column on {@code sourceTable} that holds the id
 *     (e.g. {@code StatusId})
 * @param targetTable  physical DB table name the id refers to
 *     (e.g. {@code DomainValue}). Should be a reference / setup table
 *     whose ids diverge between source and target envs; the remapper
 *     needs a matching entry in {@link ProjectProfile#referenceTableConfigs()}
 *     — or the auto-detector needs to find a discoverable natural key —
 *     otherwise the value falls through as pass-through.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record VirtualForeignKey(
        String sourceTable,
        String sourceColumn,
        String targetTable
) {}
