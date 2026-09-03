package com.devbridge.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One project's configuration. Persisted as a JSON file under
 * %USERPROFILE%\.devbridge\profiles\{id}.json — never committed to git.
 * <p>
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} lets us load older
 * profile JSONs written with a previous field layout — dropped fields
 * become null on the new record and the user re-enters them in the UI.
 *
 * @param sqlDbName {@code ?db=} value used by the SQL runner when hitting the
 *     <b>design</b> env. Older FAWB projects (like Core) use the same value
 *     for the JPA service and the underlying DB (e.g. both {@code Core}), so
 *     {@link #dbServiceName()} is enough. Newer projects provision per-user
 *     schemas on design (e.g. {@code usrzzifdsi3l}); set that here. Blank ⇒
 *     falls back to {@link #dbServiceName()}.
 * @param sandboxSqlDbName {@code ?db=} value used by the SQL runner when
 *     hitting the <b>sandbox</b> env. Sandbox usually has a different underlying
 *     DB name than design (e.g. {@code CaseManagerJ17__1} on sandbox while
 *     design uses {@code usrzzifdsi3l}), so this is a distinct field. Blank ⇒
 *     falls back to {@link #dbServiceName()}.
 * @param rootTableName the DB table name the import walker starts from (the
 *     top of the FK graph for one app). Varies per project — e.g. {@code APPLICATION}
 *     for the Core project, {@code CASE_HEADER} for CaseManager. All downstream
 *     tables + insert order are derived from the FK graph in the dataModel;
 *     only the entry point is configured here.
 * @param rootPkFilterColumn optional override for the root table's filter column.
 *     Defaults to the root table's PK column (from the dataModel). Set only when
 *     you want to scope on a different column than the PK.
 * @param tokenUrl OAuth2 token endpoint for auto-fetching the FAWB bearer.
 *     When set together with {@link #tokenClientId()} + {@link #tokenClientSecret()},
 *     DevBridge can request a fresh {@code WM_AUTH_TOKEN} on demand via
 *     the client_credentials grant, saving the manual paste every 30 min.
 * @param tokenClientId OAuth2 client ID for the token endpoint.
 * @param tokenClientSecret OAuth2 client secret for the token endpoint.
 *     Stored in the profile JSON on disk — never committed to git.
 * @param facadeKeyOverrides advanced: map of facade JSON key → DB table name,
 *     used only by the {@code FacadeValidator} cross-check (compares facade
 *     coverage vs the FK-graph walk). Example: {@code {"applicants": "CASE_PARTY"}}.
 *     The import itself is FK-graph driven and never depends on these.
 * @param referenceTables advanced: entity or table names that should NEVER
 *     be written to during import. The walker skips these entirely and any
 *     FK references to them are passed through unchanged (assumes the same
 *     reference data exists in both source and target envs). Common
 *     examples: {@code DomainValue}, {@code Locale}, {@code Permission}.
 *     Pass-through is the fallback behaviour when a table has no matching
 *     entry in {@link #referenceTableConfigs()} — id divergence between
 *     envs will cause FK errors at insert time.
 * @param referenceTableConfigs advanced: per-reference-table natural-key
 *     config used by the remapper. When a table appears here, the executor
 *     pre-populates its id-map by fetching source rows for the referenced
 *     ids, matching them against the target env by the configured natural
 *     key, and remapping id-to-id. Tables listed in {@link #referenceTables()}
 *     but missing from this map fall back to pass-through. Keys are physical
 *     DB table names (case is upper-normalized on lookup).
 * @param csvOnlyTables advanced: DB table names that must be imported via
 *     the FAWB CSV {@code /import} endpoint instead of the default SQL
 *     path. Use for tables whose LONGTEXT columns have a {@code json_valid}
 *     (or similarly partial-content-rejecting) CHECK constraint — SQL's
 *     split-INSERT + CONCAT-UPDATE would leave the column in an invalid-
 *     partial-JSON state between chunks and the CHECK would fail. CHECK
 *     constraints are not exposed in the FAWB {@code dataModel.json}, so
 *     this can't be auto-detected. If empty/null, defaults to a small
 *     built-in list that covers the CaseManager schema. Example:
 *     {@code ["CASE_DATUM"]}.
 * @param nestedInsertConfig advanced: optional parent+child pair to route
 *     through a project-specific "bundle" endpoint that accepts JSON with
 *     the child entities nested. See {@link NestedInsertConfig} for full
 *     details. Only needed when CSV {@code /import} can't transport the
 *     child's JSON content and the standard REST create HTML-encodes it.
 *     When set, the parent table's insert changes path and the child table
 *     is inserted as nested data alongside its parent. When null/omitted,
 *     routing is unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectProfile(
        String id,
        String name,
        String sandboxBaseUrl,
        String designBaseUrl,
        String dbServiceName,
        String sqlDbName,
        String sandboxSqlDbName,
        String tokenUrl,
        String tokenClientId,
        String tokenClientSecret,
        String facadeServicePath,
        String facadeEndpoint,
        String facadeQueryParam,
        String rootTableName,
        String rootPkFilterColumn,
        String dataModelJsonPath,
        Map<String, String> facadeKeyOverrides,
        List<String> referenceTables,
        Map<String, ReferenceTableConfig> referenceTableConfigs,
        List<String> csvOnlyTables,
        NestedInsertConfig nestedInsertConfig,
        Instant createdAt,
        Instant lastUsedAt
) {
    public ProjectProfile withIdAndTimestamps(String newId, Instant created, Instant lastUsed) {
        return new ProjectProfile(
                newId, name, sandboxBaseUrl, designBaseUrl,
                dbServiceName, sqlDbName, sandboxSqlDbName,
                tokenUrl, tokenClientId, tokenClientSecret,
                facadeServicePath, facadeEndpoint, facadeQueryParam,
                rootTableName, rootPkFilterColumn,
                dataModelJsonPath, facadeKeyOverrides, referenceTables,
                referenceTableConfigs, csvOnlyTables,
                nestedInsertConfig, created, lastUsed
        );
    }
}
