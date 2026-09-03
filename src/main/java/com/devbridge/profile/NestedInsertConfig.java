package com.devbridge.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Optional profile-level config for inserting a specific parent+child pair via
 * a project-specific "bundle" REST endpoint that accepts JSON directly.
 *
 * <p><b>When to configure this:</b> your schema has a child table with a
 * {@code json_valid}-style CHECK constraint on a LONGTEXT column, and the
 * FAWB CSV {@code /import} path can't transport that JSON (parser splits
 * multi-key JSON on internal commas — see CHANGELOG, 2026-08-31), AND the
 * auto-generated per-entity REST create endpoint HTML-encodes the JSON
 * (XSS filter breaks {@code json_valid}). The one code path that works is
 * a custom "bundle" endpoint that accepts the parent entity as a JSON body
 * with the child entities nested as an array — and where the child's JSON
 * column is typed as {@code ObjectNode} in the Java entity (so Jackson
 * deserialises straight into JSON, bypassing string XSS).
 *
 * <p><b>CaseManager example</b> (the one project this was built against):
 * <pre>
 * {
 *   "endpointUrl":     "/services/CaseheaderService/caseheader/caseHeader",
 *   "parentTable":     "CASE_HEADER",
 *   "childTable":      "CASE_DATUM",
 *   "nestedFieldName": "data",
 *   "queryParams":     { "process": "false", "validationEvent": "SAVE_EVENT" }
 * }
 * </pre>
 *
 * <p><b>Behaviour when set:</b> at import time, when the executor reaches
 * {@code parentTable}, it collects the {@code childTable}'s rows (already
 * fetched from source), builds a JSON body with {@code nestedFieldName}
 * populated, POSTs to {@code endpointUrl}. The {@code childTable} is
 * removed from standalone processing (its rows go with the parent).
 *
 * <p><b>Trade-off:</b> the endpoint's server-side {@code @CreationTimestamp}
 * and {@code @GeneratorType} will overwrite the parent's audit fields
 * ({@code created_by}, {@code created_on}, etc.) with server-current values.
 * Business fields, ids, and child JSON content are preserved.
 *
 * <p><b>When unset:</b> null / omitted → routing falls through to the
 * standard SQL/CSV paths (child table will fail json_valid CHECK if
 * present in the schema; workaround: add it to {@code referenceTables}
 * to skip it).
 *
 * @param endpointUrl     Relative URL under the target env's base URL. Must
 *                        start with "/". Example:
 *                        {@code /services/CaseheaderService/caseheader/caseHeader}.
 * @param parentTable     DB table name (uppercase or original case) whose
 *                        row becomes the top-level JSON body. Example:
 *                        {@code CASE_HEADER}.
 * @param childTable      DB table name whose rows are collected into the
 *                        nested list. Example: {@code CASE_DATUM}.
 * @param nestedFieldName Java-field-side JSON name for the nested list on the
 *                        parent. Example: {@code data}. Case-sensitive — must
 *                        match the field in the target entity (Hibernate
 *                        camelCase).
 * @param queryParams     Optional query-string parameters appended to
 *                        {@code endpointUrl}. Values are URL-encoded.
 *                        Example: {@code {"process": "false"}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record NestedInsertConfig(
        String endpointUrl,
        String parentTable,
        String childTable,
        String nestedFieldName,
        Map<String, String> queryParams
) {
    /** True when this config is fully specified and usable. Guards against half-configured records. */
    public boolean isUsable() {
        return endpointUrl != null && !endpointUrl.isBlank()
                && parentTable != null && !parentTable.isBlank()
                && childTable != null && !childTable.isBlank()
                && nestedFieldName != null && !nestedFieldName.isBlank();
    }
}
