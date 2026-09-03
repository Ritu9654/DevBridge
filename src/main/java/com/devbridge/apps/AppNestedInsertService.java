package com.devbridge.apps;

import com.devbridge.apps.AppSqlPlanService.ColumnPolicies;
import com.devbridge.datamodel.DataModel;
import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.NestedInsertConfig;
import com.devbridge.profile.ProjectProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Insert a parent row plus its child rows in a single JSON POST to a
 * project-specific "bundle" endpoint (configured via
 * {@link NestedInsertConfig} on the profile). Used when the child table has
 * a {@code json_valid} CHECK on a LONGTEXT column that the FAWB CSV
 * {@code /import} parser can't transport and the auto-generated per-entity
 * REST create endpoint corrupts via its XSS filter.
 *
 * <p>The bundle endpoint accepts the parent entity as JSON body, with the
 * child entities as a nested JSON array on a named field (typically
 * {@code data}). Because the child's JSON column is declared
 * {@code ObjectNode} in the target Java entity, Jackson deserialises the
 * datum content straight into an in-memory object graph, bypassing any
 * string-based XSS filter that would otherwise HTML-encode inner quotes.
 *
 * <p><b>PK preservation:</b> we generate fresh UUIDs for the parent and
 * every child, and include them in the payload. The target's
 * {@code @PrePersist} only generates a UUID when the field is null, so our
 * ids are preserved. The id-map is populated with these new UUIDs so
 * downstream child tables' FK remap continues to work.
 *
 * <p><b>Audit fields:</b> the target's {@code @CreationTimestamp} and
 * {@code @GeneratorType(LoggedUserGenerator, INSERT)} will overwrite
 * {@code createdOn}/{@code createdBy} regardless of what we send —
 * accepted trade-off (business fields + ids + JSON content are preserved).
 *
 * <p><b>FK to parent:</b> we omit the child's parent-FK column from its
 * JSON (e.g. {@code case_header_id} on CASE_DATUM). Hibernate's
 * {@code @OneToMany} + {@code @Cascade(ALL)} on the parent's list field
 * sets the FK automatically at save time.
 */
@Service
public class AppNestedInsertService {

    private static final Logger log = LoggerFactory.getLogger(AppNestedInsertService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final FawbClient client;
    private final ObjectMapper mapper;

    public AppNestedInsertService(FawbClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * Insert one parent row bundled with its child rows via the configured
     * endpoint. Returns a {@link Result} carrying HTTP status, response
     * body, the new parent id (regenerated UUID or extracted from response),
     * and the list of new child ids (index-aligned with input childRows).
     *
     * <p>The caller is responsible for FK-remap of the parent's own FK
     * columns via {@code idMap} before invoking (same convention as
     * {@link AppSqlExecutorService}'s SQL path). This method handles:
     * (a) regenerating the parent's UUID PK, (b) building the JSON body,
     * (c) processing each child row (UUID regen, FK remap of non-parent
     * FKs, datum-string-to-ObjectNode conversion), (d) posting.
     */
    public Result insertBundle(
            DataModel.Table parentTable,
            Map<String, Object> parentRow,
            ColumnPolicies parentPol,
            DataModel.Table childTable,
            List<Map<String, Object>> childRows,
            ColumnPolicies childPol,
            Map<String, Map<Object, Object>> idMap,
            ProjectProfile profile,
            String targetBaseUrl,
            NestedInsertConfig config) throws Exception {

        String parentPk = parentPol.pkColumns.isEmpty() ? null : parentPol.pkColumns.get(0);
        String childPk = childPol.pkColumns.isEmpty() ? null : childPol.pkColumns.get(0);
        if (parentPk == null || childPk == null) {
            throw new IllegalStateException(
                    "Nested insert requires primary keys on both parent (" + parentTable.name()
                            + ") and child (" + childTable.name() + ").");
        }

        // Compute the child's FK column back to the parent — we OMIT this
        // from each child's JSON (Hibernate's cascade sets it at save time
        // based on the enclosing @OneToMany).
        String childFkToParentLower = findFkColumnToParent(childPol, parentTable.name());

        // 1. Build parent JSON. Fields keyed by Java field name (camelCase)
        //    from the dataModel, since the endpoint's Jackson deserialiser
        //    matches on JPA field names, not DB column names.
        String newParentId = maybeRegenerateUuid(parentRow, parentPk, parentPol);
        ObjectNode parentJson = buildEntityJson(parentTable, parentRow, parentPol, idMap,
                parentPk, newParentId, /*fkToOmitLower=*/ null, /*isChild=*/ false);

        // 2. Build children JSON array.
        ArrayNode childrenArr = mapper.createArrayNode();
        List<Object> newChildIds = new ArrayList<>(childRows.size());
        for (Map<String, Object> childRow : childRows) {
            String newChildId = maybeRegenerateUuid(childRow, childPk, childPol);
            ObjectNode childJson = buildEntityJson(childTable, childRow, childPol, idMap,
                    childPk, newChildId, childFkToParentLower, /*isChild=*/ true);
            childrenArr.add(childJson);
            newChildIds.add(newChildId);
        }
        parentJson.set(config.nestedFieldName(), childrenArr);

        // 3. Populate id-map for the parent so any OTHER child table
        //    (imported later via the standard path) can remap its FK.
        Object sourceParentPk = lookup(parentRow, parentPk);
        if (sourceParentPk != null) {
            idMap.computeIfAbsent(FkGraph.norm(parentTable.name()), k -> new LinkedHashMap<>())
                    .put(sourceParentPk, newParentId);
        }
        // Populate id-map for children too, in case anything FK-references
        // them (unusual for CASE_DATUM, but generic behavior).
        Map<Object, Object> childMap = idMap.computeIfAbsent(
                FkGraph.norm(childTable.name()), k -> new LinkedHashMap<>());
        for (int i = 0; i < childRows.size(); i++) {
            Object sourceChildPk = lookup(childRows.get(i), childPk);
            if (sourceChildPk != null) childMap.put(sourceChildPk, newChildIds.get(i));
        }

        // 4. POST to configured endpoint.
        String url = buildUrl(targetBaseUrl, config);
        String body = mapper.writeValueAsString(parentJson);
        log.info("Nested insert: POST {} ({} bytes, parent={} children={})",
                url, body.length(), parentTable.name(), childRows.size());
        HttpResponse<String> res = client.post(url, body, profile.id());
        return new Result(res.statusCode(), res.body(), newParentId, newChildIds, url);
    }

    /**
     * If the PK column is a UUID column that ColumnPolicies flagged for regeneration,
     * emit a fresh UUID. Otherwise return the source PK value as-is (as a string).
     * The result is guaranteed non-null when there IS a source PK; callers rely on
     * that for id-map bookkeeping.
     */
    private static String maybeRegenerateUuid(Map<String, Object> row, String pkCol, ColumnPolicies pol) {
        Object src = lookup(row, pkCol);
        boolean shouldRegen = pol.uuidPkColumnsLower.contains(pkCol.toLowerCase(Locale.ROOT))
                && (src == null || isUuidLike(src));
        if (shouldRegen) return UUID.randomUUID().toString();
        return src == null ? UUID.randomUUID().toString() : String.valueOf(src);
    }

    /**
     * Build a JSON object for one entity. Keys are Java field names
     * (camelCase per dataModel {@code fieldName}). Values are:
     * <ul>
     *   <li>FK columns → remapped via id-map (or omitted if the remap
     *       column is the parent-FK)</li>
     *   <li>The primary key → the supplied {@code overridePkValue}</li>
     *   <li>Everything else → source value passed through as-is</li>
     * </ul>
     *
     * <p>For a child ({@code isChild=true}) we also parse any large-text
     * column whose source value is a JSON-shaped string into an ObjectNode,
     * so the field ends up as a nested JSON object in the payload (the
     * whole reason for using this endpoint — the target's {@code ObjectNode}
     * field type wants a JSON value, not a stringified one).
     */
    private ObjectNode buildEntityJson(DataModel.Table table, Map<String, Object> row,
                                        ColumnPolicies pol, Map<String, Map<Object, Object>> idMap,
                                        String pkCol, String overridePkValue,
                                        String fkToOmitLower, boolean isChild) {
        ObjectNode out = mapper.createObjectNode();
        for (DataModel.Column col : safe(table.columns())) {
            if (col == null || col.name() == null) continue;
            String cname = col.name();
            String lower = cname.toLowerCase(Locale.ROOT);
            if (pol.strippedLower.contains(lower)) continue;
            if (fkToOmitLower != null && fkToOmitLower.equals(lower)) continue;   // parent-FK omitted

            String fieldName = col.fieldName() != null && !col.fieldName().isBlank()
                    ? col.fieldName() : cname;   // fallback to DB name if no fieldName

            // PK: use the override (regenerated UUID or preserved source value)
            if (cname.equalsIgnoreCase(pkCol)) {
                out.put(fieldName, overridePkValue);
                continue;
            }

            Object value = lookup(row, cname);

            // FK remap (non-parent): pull the mapped id from idMap
            ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
            if (remap != null && !remap.isReferenceTarget() && value != null) {
                Object mapped = lookupIdMap(idMap, remap.targetTable(), value);
                if (mapped == null) {
                    throw new IllegalStateException(
                            "Cannot remap FK " + table.name() + "." + cname + " = " + value
                                    + " → " + remap.targetTable() + " (parent not in id-map).");
                }
                out.put(fieldName, String.valueOf(mapped));
                continue;
            }

            // Large-text column → try to parse as JSON so it lands as a nested
            // object in the payload (ObjectNode field on target entity).
            String jt = col.javaType() == null ? "" : col.javaType().toLowerCase(Locale.ROOT);
            if (isChild && ("text".equals(jt) || "clob".equals(jt) || "longtext".equals(jt))
                    && value instanceof String s && !s.isBlank()) {
                try {
                    JsonNode parsed = mapper.readTree(s);
                    out.set(fieldName, parsed);
                    continue;
                } catch (Exception ignore) {
                    // Not JSON — fall through to plain string handling.
                }
            }

            setTypedValue(out, fieldName, value, jt);
        }
        return out;
    }

    /**
     * Convert a source value to the right JSON type based on the column's
     * {@code javaType}. Rules:
     * <ul>
     *   <li>{@code datetime} / {@code timestamp}: epoch-ms Long → ISO-8601
     *       {@code "yyyy-MM-ddTHH:mm:ss"}; space-separated string →
     *       T-separated string. Jackson's {@code LocalDateTime} deserializer
     *       rejects raw numeric timestamps (empirically hit on CASE_HEADER's
     *       {@code submittedOn}); it wants an ISO-format string.</li>
     *   <li>{@code date}: epoch-ms Long → ISO date {@code "yyyy-MM-dd"}.</li>
     *   <li>{@code boolean}: normalise to JSON true/false.</li>
     *   <li>Numeric types: as JSON numbers.</li>
     *   <li>Anything else: JSON string.</li>
     * </ul>
     */
    private static void setTypedValue(ObjectNode out, String fieldName, Object value, String javaType) {
        if (value == null) { out.putNull(fieldName); return; }

        if ("datetime".equals(javaType) || "timestamp".equals(javaType)) {
            if (value instanceof Number n) {
                out.put(fieldName, java.time.LocalDateTime
                        .ofInstant(java.time.Instant.ofEpochMilli(n.longValue()), java.time.ZoneOffset.UTC)
                        .toString());
                return;
            }
            if (value instanceof String s) {
                // Common source format: "2026-08-29 13:45:18" → convert to ISO "T" separator.
                if (s.length() >= 19 && s.charAt(10) == ' ') {
                    out.put(fieldName, s.substring(0, 10) + "T" + s.substring(11));
                } else {
                    out.put(fieldName, s);
                }
                return;
            }
        }
        if ("date".equals(javaType)) {
            if (value instanceof Number n) {
                out.put(fieldName, java.time.LocalDate
                        .ofInstant(java.time.Instant.ofEpochMilli(n.longValue()), java.time.ZoneOffset.UTC)
                        .toString());
                return;
            }
            if (value instanceof String s) { out.put(fieldName, s); return; }
        }

        if ("boolean".equals(javaType)) {
            if (value instanceof Boolean b) { out.put(fieldName, b); return; }
            if (value instanceof Number n) { out.put(fieldName, n.intValue() != 0); return; }
            if (value instanceof String s) {
                if (s.equalsIgnoreCase("true") || s.equals("1")) { out.put(fieldName, true); return; }
                if (s.equalsIgnoreCase("false") || s.equals("0")) { out.put(fieldName, false); return; }
            }
        }

        if (value instanceof Boolean b) { out.put(fieldName, b); return; }
        if (value instanceof Integer i) { out.put(fieldName, i); return; }
        if (value instanceof Long l) { out.put(fieldName, l); return; }
        if (value instanceof Double d) { out.put(fieldName, d); return; }
        if (value instanceof Float f) { out.put(fieldName, f); return; }
        if (value instanceof Number n) { out.put(fieldName, n.longValue()); return; }
        out.put(fieldName, String.valueOf(value));
    }

    /**
     * Find the child's FK column that references the parent table (case-insensitive).
     * Returns the column name in lowercase for direct comparison, or null if no such FK.
     */
    private static String findFkColumnToParent(ColumnPolicies childPol, String parentTableName) {
        String parentNorm = FkGraph.norm(parentTableName);
        for (Map.Entry<String, ImportPlan.FkRemap> e : childPol.fkByColumnLower.entrySet()) {
            if (FkGraph.norm(e.getValue().targetTable()).equals(parentNorm)) return e.getKey();
        }
        return null;
    }

    private String buildUrl(String targetBaseUrl, NestedInsertConfig config) {
        StringBuilder url = new StringBuilder(trimTrailingSlash(targetBaseUrl));
        String path = config.endpointUrl();
        if (!path.startsWith("/")) url.append('/');
        url.append(path);
        if (config.queryParams() != null && !config.queryParams().isEmpty()) {
            char sep = url.indexOf("?") >= 0 ? '&' : '?';
            for (Map.Entry<String, String> qp : config.queryParams().entrySet()) {
                if (qp.getKey() == null || qp.getValue() == null) continue;
                url.append(sep)
                        .append(URLEncoder.encode(qp.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(qp.getValue(), StandardCharsets.UTF_8));
                sep = '&';
            }
        }
        return url.toString();
    }

    /* ---------- copied helpers (kept private to avoid coupling) ---------- */

    private static Object lookup(Map<String, Object> row, String colName) {
        if (row == null || colName == null) return null;
        if (row.containsKey(colName)) return row.get(colName);
        String lower = colName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        // snake_case → camelCase fallback (executeSQLs echoes Hibernate field names for some tables)
        if (colName.indexOf('_') >= 0) {
            StringBuilder sb = new StringBuilder(colName.length());
            boolean up = false;
            for (int i = 0; i < colName.length(); i++) {
                char c = colName.charAt(i);
                if (c == '_') { up = true; continue; }
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
            String camel = sb.toString();
            if (row.containsKey(camel)) return row.get(camel);
            String cLower = camel.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(cLower)) return e.getValue();
            }
        }
        return null;
    }

    private static Object lookupIdMap(Map<String, Map<Object, Object>> idMap, String targetTable, Object src) {
        if (targetTable == null || src == null) return null;
        Map<Object, Object> byTable = idMap.get(FkGraph.norm(targetTable));
        if (byTable == null) return null;
        Object v = byTable.get(src);
        if (v != null) return v;
        for (Map.Entry<Object, Object> e : byTable.entrySet()) {
            if (String.valueOf(e.getKey()).equals(String.valueOf(src))) return e.getValue();
        }
        return null;
    }

    private static boolean isUuidLike(Object v) {
        return v instanceof String s && UUID_PATTERN.matcher(s).matches();
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    /** Outcome of one nested-insert HTTP call. */
    public record Result(
            int status,
            String body,
            Object assignedParentId,
            List<Object> assignedChildIds,
            String url
    ) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
