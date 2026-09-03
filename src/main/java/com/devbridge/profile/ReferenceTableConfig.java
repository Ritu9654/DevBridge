package com.devbridge.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-reference-table config used by the natural-key remapper. A "reference"
 * table (e.g. {@code DOMAINVALUE}, {@code DOMAINVALUETYPE}) has identity ids
 * that differ per environment, but a stable natural key (like {@code Code}
 * plus its {@code DomainValueType}). When present in
 * {@link ProjectProfile#referenceTableConfigs()}, the import walker won't
 * touch this table's rows; instead the executor resolves each source id to
 * the target's own id by matching on the natural key.
 *
 * @param naturalKey column names on this table forming the natural key.
 *     Order matters — the last column is used as the {@code IN (...)} axis
 *     in target-side lookups. If any of these columns is itself an FK to
 *     another reference table, that table must also be configured here so
 *     the remapper can resolve it first.
 * @param activeFilter optional raw SQL {@code WHERE}-fragment appended to
 *     the target-side lookup, e.g. {@code "IsActive=1"}. Used to prefer
 *     currently-active rows when soft-deprecated duplicates exist. Null or
 *     blank ⇒ no extra filter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReferenceTableConfig(
        List<String> naturalKey,
        String activeFilter
) {}
