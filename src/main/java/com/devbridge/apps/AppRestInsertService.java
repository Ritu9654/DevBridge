package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.ProjectProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Inserts rows into a FAWB entity via the per-entity CRUD REST endpoint
 * ({@code POST /services/{dbService}/{Entity}}). Used for tables with any
 * LONGTEXT/CLOB/BLOB column because:
 * <ul>
 *   <li>the request body has no URL-length limit (unlike SQL via {@code executeSQLs} GET);</li>
 *   <li>the full value is delivered in one call, so CHECK constraints
 *       (e.g. {@code JSON_VALID}) see the complete row content, not a partial chunk;</li>
 *   <li>JPA on the FAWB side preserves supplied timestamps (verified) so we don't
 *       lose {@code createdOn}/{@code updatedOn} fidelity.</li>
 * </ul>
 *
 * <p>Rows inserted through REST are still journaled the same way as SQL rows,
 * and the SQL rollback path (DELETE by PK) works on them without modification.
 */
@Service
public class AppRestInsertService {

    private static final Logger log = LoggerFactory.getLogger(AppRestInsertService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final FawbClient client;
    private final ObjectMapper mapper;

    public AppRestInsertService(FawbClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    /**
     * POST one row to the entity's CRUD endpoint. Returns the newly-created row's
     * primary key (as echoed by the server), or throws if the server rejected it.
     *
     * @param sourceRow    the source-env row (column-name keyed)
     * @param table        the target-side table metadata
     * @param pol          column policies from the plan (which cols to strip, remap, regen)
     * @param idMap        cross-table id-map, needed for FK remapping
     * @param profile      target profile (for base URL + bearer token)
     * @param targetBaseUrl the resolved sandbox/design base URL
     * @param dbServiceName the JPA service name (e.g., {@code CaseManager})
     * @return {@code InsertResult} with the assigned PK and the raw response body
     */
    public InsertResult insert(Map<String, Object> sourceRow, DataModel.Table table,
                                AppSqlPlanService.ColumnPolicies pol,
                                Map<String, Map<Object, Object>> idMap,
                                ProjectProfile profile, String targetBaseUrl,
                                String dbServiceName) throws Exception {
        String entityName = table.entityName();
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalStateException("Table '" + table.name() + "' has no entityName in the dataModel; cannot POST.");
        }
        String url = trimTrailingSlash(targetBaseUrl) + "/services/" + dbServiceName + "/" + entityName;

        // Build FK column → target table map for remap lookups
        Map<String, String> fkTargetByColumn = pol.fkByColumnLower.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().targetTable()));

        // Track the PK value we're sending (regen'd UUID if applicable) so we can return it
        String primaryPkCol = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);
        Object clientAssignedPk = null;

        ObjectNode payload = mapper.createObjectNode();
        for (DataModel.Column col : safe(table.columns())) {
            String cname = col.name();
            if (cname == null) continue;
            String lower = cname.toLowerCase(Locale.ROOT);
            if (pol.strippedLower.contains(lower)) continue;

            Object v = lookupColumn(sourceRow, cname);
            String jsonKey = col.fieldName() != null && !col.fieldName().isBlank() ? col.fieldName() : cname;

            // 1. Assigned UUID PK → regen at execute time
            if (pol.uuidPkColumnsLower.contains(lower) && isUuidLike(v)) {
                String fresh = java.util.UUID.randomUUID().toString();
                setJson(payload, jsonKey, fresh);
                if (cname.equalsIgnoreCase(primaryPkCol)) clientAssignedPk = fresh;
                continue;
            }

            // 2. FK column → remap via id-map (populated for both walked
            // tables and configured reference tables via natural-key remap).
            // Audit-column FKs (CreatedBy, UpdatedBy, ...) get a sentinel
            // any-row fallback when no id-map entry exists.
            ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
            if (remap != null && v != null) {
                Object mapped = lookupIdMap(idMap, remap.targetTable(), v);
                if (mapped == null && remap.useSentinel()) {
                    mapped = ReferenceRemapService.lookupSentinel(idMap, remap.targetTable());
                }
                if (mapped != null) {
                    setJson(payload, jsonKey, mapped);
                    continue;
                }
                if (!remap.isReferenceTarget()) {
                    throw new IllegalStateException(
                            "Cannot remap FK " + table.name() + "." + cname + " = " + v
                                    + " → " + remap.targetTable() + " (parent likely wasn't inserted).");
                }
                // Reference target with no natural-key remap configured —
                // fall back to pass-through of the source value.
            }

            // 3. Regular value → JSON-appropriate representation
            setJson(payload, jsonKey, formatForRest(v, col));
        }

        // If PK wasn't regen'd (e.g. non-UUID assigned), it's already in payload from column iteration.
        // Capture it for the return value.
        if (clientAssignedPk == null && primaryPkCol != null) {
            JsonNode pkNode = payload.get(pkFieldName(table, primaryPkCol));
            if (pkNode != null && !pkNode.isNull()) {
                clientAssignedPk = pkNode.isNumber() ? pkNode.numberValue() : pkNode.asText();
            }
        }

        // Add relation objects for FKs — JPA convention: caseHeader: {id: "..."}
        // The raw *Id column has already been set from the column iteration above.
        for (DataModel.Relation rel : safe(table.relations())) {
            if (rel == null || rel.fieldName() == null) continue;
            String card = rel.cardinality();
            if (!"ManyToOne".equalsIgnoreCase(card) && !"OneToOne".equalsIgnoreCase(card)) continue;
            if (rel.mappings() == null || rel.mappings().isEmpty()) continue;
            // Use the first mapping's source column to look up the (already remapped) FK value
            String srcColumn = rel.mappings().get(0).sourceColumn();
            if (srcColumn == null) continue;
            String srcColLower = srcColumn.toLowerCase(Locale.ROOT);
            // Find the fieldName of the source column for JSON key lookup
            String srcFieldName = srcColumn;
            for (DataModel.Column c : safe(table.columns())) {
                if (c.name() != null && c.name().toLowerCase(Locale.ROOT).equals(srcColLower)) {
                    if (c.fieldName() != null && !c.fieldName().isBlank()) srcFieldName = c.fieldName();
                    break;
                }
            }
            JsonNode fkVal = payload.get(srcFieldName);
            if (fkVal == null || fkVal.isNull()) continue;
            ObjectNode relObj = mapper.createObjectNode();
            relObj.set("id", fkVal);
            payload.set(rel.fieldName(), relObj);
        }

        // Pre-flight: validate that any large-text value we're about to send is
        // itself parseable as JSON. If OUR side rejects it, we know the source
        // SELECT returned invalid content and we can halt with a specific error.
        // If it passes here but the server rejects it, the issue is downstream
        // (JPA parsing, size limit, or something between HTTP client and MariaDB).
        for (DataModel.Column col : safe(table.columns())) {
            if (col == null || col.javaType() == null) continue;
            if (!SqlBuilder.isLargeTextType(col.javaType().toLowerCase(Locale.ROOT))) continue;
            String jsonKey = col.fieldName() != null && !col.fieldName().isBlank() ? col.fieldName() : col.name();
            JsonNode node = payload.get(jsonKey);
            if (node == null || node.isNull() || !node.isTextual()) continue;
            String text = node.asText();
            if (text.isEmpty()) continue;
            try {
                mapper.readTree(text);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Local pre-check: column '" + col.name() + "' value is not valid JSON — "
                                + e.getClass().getSimpleName() + ": " + e.getMessage()
                                + ". First 200 chars: " + text.substring(0, Math.min(200, text.length())));
            }
        }

        String body = mapper.writeValueAsString(payload);
        HttpResponse<String> res = client.post(url, body, profile.id());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            log.warn("REST POST failed HTTP {} for {}. Payload preview: {}",
                    res.statusCode(), url,
                    body.length() > 600 ? body.substring(0, 600) + "…(+" + (body.length() - 600) + " chars)" : body);
        }
        return new InsertResult(res.statusCode(), res.body(), clientAssignedPk, url, body);
    }

    /**
     * DELETE a row by its target PK via the same REST CRUD endpoint. Used by
     * rollback when the row was inserted via REST — SQL DELETE works too but
     * this path keeps JPA lifecycle consistent for any cascade rules the entity
     * might have. Returns true on 2xx, false otherwise.
     */
    public boolean deleteByPk(String entityName, Object pkValue, ProjectProfile profile,
                              String targetBaseUrl, String dbServiceName) throws Exception {
        String url = trimTrailingSlash(targetBaseUrl) + "/services/" + dbServiceName
                + "/" + entityName + "/" + pkValue;
        HttpResponse<String> res = client.delete(url, profile.id());
        int s = res.statusCode();
        return s >= 200 && s < 300;
    }

    /* ---------- helpers ---------- */

    private static String pkFieldName(DataModel.Table t, String pkColumn) {
        for (DataModel.Column c : safe(t.columns())) {
            if (c.name() != null && c.name().equalsIgnoreCase(pkColumn)) {
                return c.fieldName() != null && !c.fieldName().isBlank() ? c.fieldName() : c.name();
            }
        }
        return pkColumn;
    }

    private static Object formatForRest(Object v, DataModel.Column col) {
        if (v == null) return null;
        String jt = col.javaType() == null ? "" : col.javaType().toLowerCase(Locale.ROOT);
        if (("datetime".equals(jt) || "timestamp".equals(jt) || "date".equals(jt))
                && v instanceof Number n) {
            return ISO_FMT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(n.longValue()));
        }
        // For large-text (potentially JSON) columns: FAWB's executeSQLs sometimes
        // returns their content as base64-encoded strings in the response
        // envelope (deployment-level escaping to survive JSON transport). We
        // detect and decode that here before any downstream JSON pre-check
        // or payload build sees it. Also repair HTML-encoded content so
        // target-side json_valid CHECKs accept it — no-op when clean.
        if (SqlBuilder.isLargeTextType(jt) && v instanceof String s) {
            String decoded = maybeDecodeBase64Json(s);
            return SqlBuilder.repairHtmlEncodedJson(decoded);
        }
        return v;
    }

    /**
     * If {@code s} looks like base64-encoded JSON (doesn't start with {@code {}
     * or {@code [}, uses only base64 chars, and decodes cleanly into text
     * that itself starts with JSON markers), return the decoded string.
     * Otherwise return {@code s} unchanged.
     * <p>
     * We're conservative — only convert when the OUTPUT is clearly JSON.
     * A regular text value that happens to be base64-compatible characters
     * won't be touched because it wouldn't decode to a JSON-starter.
     */
    public static String maybeDecodeBase64Json(String s) {
        if (s == null || s.isEmpty()) return s;
        char first = s.charAt(0);
        if (first == '{' || first == '[' || first == '"') return s;   // already JSON-shaped
        // Base64 chars only? (A-Z a-z 0-9 + / =, plus optional whitespace)
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '='
                    || c == '\n' || c == '\r' || c == ' ';
            if (!ok) return s;
        }
        if (s.length() % 4 != 0) return s;   // valid base64 is length-multiple-of-4
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(s);
            String decoded = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.isEmpty()) return s;
            char d0 = decoded.charAt(0);
            if (d0 == '{' || d0 == '[' || d0 == '"') return decoded;
        } catch (IllegalArgumentException ignore) {
            // Not valid base64 after all.
        }
        return s;
    }

    private void setJson(ObjectNode target, String key, Object value) {
        if (value == null) { target.putNull(key); return; }
        if (value instanceof Boolean b) target.put(key, b);
        else if (value instanceof Integer i) target.put(key, i);
        else if (value instanceof Long l) target.put(key, l);
        else if (value instanceof Double d) target.put(key, d);
        else if (value instanceof Float f) target.put(key, f);
        else if (value instanceof Number n) target.put(key, n.longValue());
        else if (value instanceof String s) target.put(key, s);
        else if (value instanceof Map || value instanceof List) {
            // Structured value — probably a TEXT column whose stored content is
            // valid JSON, which Jackson auto-parsed to Map/List when we called
            // convertValue(JsonNode, Map<String,Object>) during fetch. The Java
            // field on the target entity is a String, so we must re-serialise
            // to JSON text before POSTing.
            try {
                target.put(key, mapper.writeValueAsString(value));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialise structured value for column '" + key + "'", e);
            }
        }
        else target.put(key, value.toString());
    }

    private static Object lookupColumn(Map<String, Object> row, String colName) {
        if (row == null || colName == null) return null;
        if (row.containsKey(colName)) return row.get(colName);
        String lower = colName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        // snake_case → camelCase fallback
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

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    /** Outcome of a single REST insert. */
    public record InsertResult(int status, String body, Object assignedPk, String url, String outgoingPayload) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
