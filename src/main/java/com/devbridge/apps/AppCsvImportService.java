package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;
import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Inserts rows via FAWB's CSV import endpoint
 * ({@code POST /services/{dbService}/{Entity}/import}). Used for tables with
 * LONGTEXT/CLOB/BLOB/TEXT columns because that endpoint carries the
 * {@code @XssDisable} annotation — the ONLY create-path we've found that
 * doesn't HTML-encode the request body. The regular {@code POST /...} create
 * endpoint HTML-encodes {@code " < > &} in the payload, which breaks any
 * {@code json_valid} CHECK constraint on the way to the DB.
 *
 * <p>Transport: multipart/form-data with a single {@code file} part carrying
 * the CSV bytes, explicit {@code Content-Type: text/csv} on that part (default
 * {@code application/octet-stream} is rejected by FAWB's importer).
 *
 * <p>One HTTP call per table. All rows submitted in one CSV; the server
 * processes them transactionally. On failure the whole batch is rejected;
 * halt-and-rollback semantics unchanged.
 */
@Service
public class AppCsvImportService {

    private static final Logger log = LoggerFactory.getLogger(AppCsvImportService.class);
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FawbClient client;

    public AppCsvImportService(FawbClient client) {
        this.client = client;
    }

    /**
     * Build a CSV from the given rows (applying column policies + FK remap +
     * UUID regen + HTML-repair on large-text) and POST it to the table's
     * {@code /import} endpoint.
     *
     * @return result carrying HTTP status, response body, and — on success —
     *         the client-assigned PKs (one per row, index-aligned with input)
     *         so the caller can populate the id-map.
     */
    /**
     * Max bytes per CSV upload. A single row may exceed this — in that case
     * the row goes alone in its own request. Below this threshold, rows
     * batch together for efficiency. Empirically 470 KB single-field rows
     * caused WaveMaker's CSV importer to misbehave (silent parse truncation
     * that surfaced as "row conversion for column IS_ACTIVE"). One row per
     * request removes the batch-level parse ambiguity even for large rows.
     */
    private static final int MAX_CSV_CHUNK_BYTES = 100 * 1024;

    public Result importRows(DataModel.Table table, List<Map<String, Object>> sourceRows,
                              AppSqlPlanService.ColumnPolicies pol,
                              Map<String, Map<Object, Object>> idMap,
                              ProjectProfile profile, String targetBaseUrl,
                              String dbServiceName) throws Exception {
        if (sourceRows == null || sourceRows.isEmpty()) {
            return new Result(200, "", List.of(), null, null);
        }
        String entityName = table.entityName();
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalStateException(
                    "Table '" + table.name() + "' has no entityName in the dataModel; cannot POST to /import.");
        }

        String primaryPkCol = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);

        // Pick columns to include: every non-stripped column. CSV header uses
        // DB column names (snake_case). See CHANGELOG for the reasoning behind
        // NOT skipping all-null columns (needed by WaveMaker's importer).
        List<DataModel.Column> csvColumns = new ArrayList<>();
        for (DataModel.Column col : safe(table.columns())) {
            if (col == null || col.name() == null) continue;
            String lower = col.name().toLowerCase(Locale.ROOT);
            if (pol.strippedLower.contains(lower)) continue;
            csvColumns.add(col);
        }
        if (csvColumns.isEmpty()) {
            throw new IllegalStateException("No CSV columns to import for " + table.name());
        }

        // Build header once — reused for every chunk.
        StringBuilder headerBuilder = new StringBuilder();
        writeCsvHeader(headerBuilder, csvColumns);
        String header = headerBuilder.toString();

        // Materialise each row's CSV text + its client-assigned PK. Reasoning
        // for materialising up-front:
        // 1. We need per-row byte size to decide chunking.
        // 2. FK remap / UUID regen already mutate idMap during row-write —
        //    doing it once here keeps idMap consistent even if a later
        //    chunk fails.
        List<String> rowCsvs = new ArrayList<>(sourceRows.size());
        List<Object> assignedPks = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> row : sourceRows) {
            StringBuilder rb = new StringBuilder(512);
            Object rowPk = writeCsvRow(rb, row, csvColumns, pol, idMap, table, primaryPkCol);
            rowCsvs.add(rb.toString());
            assignedPks.add(rowPk);
        }

        // Chunk rows so each POSTed CSV stays under MAX_CSV_CHUNK_BYTES. A row
        // that alone exceeds the limit becomes its own chunk — better to send
        // one giant row alone than to bury it inside a batch where a parser
        // ambiguity in the huge row silently shifts subsequent rows' columns.
        String url = trimTrailingSlash(targetBaseUrl) + "/services/" + dbServiceName
                + "/" + entityName + "/import";
        String filenameBase = table.name().toLowerCase(Locale.ROOT);

        List<int[]> chunkRanges = planChunks(rowCsvs, header.length());
        log.info("CSV import for {}: {} row(s) split into {} chunk(s)",
                table.name(), sourceRows.size(), chunkRanges.size());

        int lastStatus = 200;
        String lastBody = "";
        String lastDump = null;
        for (int c = 0; c < chunkRanges.size(); c++) {
            int[] range = chunkRanges.get(c);
            StringBuilder chunkCsv = new StringBuilder(header.length() + 1024);
            chunkCsv.append(header);
            for (int i = range[0]; i < range[1]; i++) chunkCsv.append(rowCsvs.get(i));

            byte[] csvBytes = chunkCsv.toString().getBytes(StandardCharsets.UTF_8);
            String filename = filenameBase + (chunkRanges.size() > 1 ? "-chunk" + (c + 1) : "") + ".csv";
            HttpResponse<String> res = client.postMultipart(url, "file", filename, "text/csv",
                    csvBytes, profile.id());
            lastStatus = res.statusCode();
            lastBody = res.body();

            if (lastStatus < 200 || lastStatus >= 300) {
                lastDump = dumpFailedCsv(csvBytes, profile.id(),
                        table.name() + "-chunk" + (c + 1) + "of" + chunkRanges.size(),
                        url, lastStatus, lastBody);
                log.warn("CSV import chunk {}/{} failed HTTP {} for {} (rows {}-{}). URL: {}. Dump: {}",
                        c + 1, chunkRanges.size(), lastStatus, table.name(),
                        range[0], range[1] - 1, url, lastDump);
                // Rows before this chunk landed in target; caller uses the
                // journal + rollback to clean them up. Return assignedPks
                // truncated to what was actually processed (rows 0..range[0]-1
                // succeeded; rows in this chunk did NOT).
                List<Object> processedPks = assignedPks.subList(0, range[0]);
                return new Result(lastStatus, lastBody, processedPks, url, lastDump);
            }
        }

        // All chunks succeeded.
        return new Result(lastStatus, lastBody, assignedPks, url, lastDump);
    }

    /**
     * Plan chunks: each chunk is a contiguous range [start, end) of row
     * indices whose combined CSV size (header + row texts) is ≤
     * {@link #MAX_CSV_CHUNK_BYTES}. Rows that alone exceed the limit
     * are placed in their own chunk.
     */
    private static List<int[]> planChunks(List<String> rowCsvs, int headerLen) {
        List<int[]> chunks = new ArrayList<>();
        int i = 0;
        while (i < rowCsvs.size()) {
            int start = i;
            int accum = headerLen;
            while (i < rowCsvs.size()) {
                int rowLen = rowCsvs.get(i).length();
                // First row of a chunk always gets included even if oversized —
                // otherwise we'd loop forever on a giant row.
                if (i > start && accum + rowLen > MAX_CSV_CHUNK_BYTES) break;
                accum += rowLen;
                i++;
                if (accum > MAX_CSV_CHUNK_BYTES) break;   // stop after the oversized row
            }
            chunks.add(new int[]{start, i});
        }
        return chunks;
    }

    private static String dumpFailedCsv(byte[] csvBytes, String profileId, String tableName,
                                         String url, int status, String body) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path dir = java.nio.file.Paths.get(home, ".devbridge", "imports",
                    profileId != null ? profileId : "unknown", "failed-csv");
            java.nio.file.Files.createDirectories(dir);
            long ts = System.currentTimeMillis();
            java.nio.file.Path csvFile = dir.resolve("failed-" + tableName + "-" + ts + ".csv");
            java.nio.file.Files.write(csvFile, csvBytes);
            java.nio.file.Path metaFile = dir.resolve("failed-" + tableName + "-" + ts + ".meta.txt");
            String meta = "URL: " + url + "\n"
                    + "STATUS: " + status + "\n"
                    + "BODY:\n" + (body == null ? "(null)" : body) + "\n";
            java.nio.file.Files.writeString(metaFile, meta);
            return csvFile.toAbsolutePath().toString();
        } catch (Exception e) {
            return "(dump-failed: " + e.getMessage() + ")";
        }
    }

    /* ---------- CSV construction ---------- */

    private static void writeCsvHeader(StringBuilder csv, List<DataModel.Column> columns) {
        boolean first = true;
        for (DataModel.Column c : columns) {
            if (!first) csv.append(',');
            csv.append(csvEscape(c.name()));
            first = false;
        }
        csv.append("\r\n");
    }

    /**
     * Emit one CSV row and return the client-assigned PK for id-map bookkeeping.
     */
    private static Object writeCsvRow(StringBuilder csv, Map<String, Object> sourceRow,
                                       List<DataModel.Column> columns,
                                       AppSqlPlanService.ColumnPolicies pol,
                                       Map<String, Map<Object, Object>> idMap,
                                       DataModel.Table table, String primaryPkCol) {
        Object assignedPk = null;
        boolean first = true;
        for (DataModel.Column col : columns) {
            if (!first) csv.append(',');
            String cname = col.name();
            String lower = cname.toLowerCase(Locale.ROOT);
            Object v = lookup(sourceRow, cname);

            // 1. Regen assigned UUID PKs so sandboxes cloned from design don't collide
            if (pol.uuidPkColumnsLower.contains(lower) && isUuidLike(v)) {
                String fresh = UUID.randomUUID().toString();
                csv.append(csvEscape(fresh));
                if (cname.equalsIgnoreCase(primaryPkCol)) assignedPk = fresh;
                first = false;
                continue;
            }

            // 2. FK column → remap via id-map
            ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
            if (remap != null && !remap.isReferenceTarget() && v != null) {
                Object mapped = lookupIdMap(idMap, remap.targetTable(), v);
                if (mapped == null) {
                    throw new IllegalStateException(
                            "Cannot remap FK " + table.name() + "." + cname + " = " + v
                                    + " → " + remap.targetTable() + " (parent not in id-map).");
                }
                csv.append(csvEscape(String.valueOf(mapped)));
                // Shared-PK case: when the PK column is itself an FK (e.g.
                // CASE_CREDIT_APP.id → CASE_HEADER.id), the row's assigned PK
                // is the remapped value, not the source value. Without this,
                // the caller's id-map maps source→source instead of
                // source→mapped, and downstream verification / children see
                // the wrong id.
                if (cname.equalsIgnoreCase(primaryPkCol)) assignedPk = mapped;
                first = false;
                continue;
            }

            // 3. Normal column — apply value formatting (dates → yyyy-MM-dd HH:mm:ss,
            //    booleans → 1/0, large-text → HTML-repair)
            csv.append(csvEscape(formatForCsv(v, col)));
            if (cname.equalsIgnoreCase(primaryPkCol) && assignedPk == null) assignedPk = v;
            first = false;
        }
        csv.append("\r\n");
        return assignedPk;
    }

    /**
     * Format a value for CSV output. Returns null → empty cell. Everything
     * else → stringified with type-specific conventions that MariaDB and
     * WaveMaker's importer expect.
     */
    private static String formatForCsv(Object v, DataModel.Column col) {
        String jt = col.javaType() == null ? "" : col.javaType().toLowerCase(Locale.ROOT);

        // Boolean columns: WaveMaker's CSV importer rejects 1/0 (row-conversion
        // failure) — it wants "true"/"false". Normalise any source form
        // (Boolean, Number, "1"/"0", "true"/"false") to "true"/"false". On null
        // for a non-nullable column, fall back to the column's declared default
        // if any; else leave the cell empty and let the DB default fill in.
        if ("boolean".equals(jt)) {
            if (v instanceof Boolean b) return b ? "true" : "false";
            if (v instanceof Number n) return n.intValue() != 0 ? "true" : "false";
            if (v instanceof String s) {
                if (s.equalsIgnoreCase("true") || s.equals("1")) return "true";
                if (s.equalsIgnoreCase("false") || s.equals("0")) return "false";
            }
            if (v == null && col != null && !col.nullable() && col.columnValue() != null) {
                String dv = col.columnValue().defaultValue();
                if (dv != null && !dv.isBlank()) {
                    if (dv.equalsIgnoreCase("true") || dv.equals("1")) return "true";
                    if (dv.equalsIgnoreCase("false") || dv.equals("0")) return "false";
                }
            }
            return v == null ? "" : String.valueOf(v);
        }

        if (v == null) return "";

        // Epoch-ms → MariaDB DATETIME literal
        if (("datetime".equals(jt) || "timestamp".equals(jt) || "date".equals(jt))
                && v instanceof Number n) {
            return DATETIME_FMT.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(n.longValue()));
        }

        // Large-text JSON columns: repair HTML entities in source, if any,
        // so the target ends up with clean, json_valid content.
        if (SqlBuilder.isLargeTextType(jt) && v instanceof String s) {
            return SqlBuilder.repairHtmlEncodedJson(s);
        }

        // Numbers: emit plain decimal notation. Double.toString flips to
        // scientific notation (e.g. "1.98E9") for large values, which
        // WaveMaker's DECIMAL parser rejects during CSV row conversion.
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) return "";
            return new java.math.BigDecimal(v.toString()).toPlainString();
        }

        return String.valueOf(v);
    }

    /**
     * CSV escaping tuned for WaveMaker's {@code /import} parser.
     *
     * <p>Empirically established via {@code DiagnoseController} (see six-variant
     * probe, 2026-08-31): WaveMaker's parser recognises {@code \"} as an
     * escaped quote — <b>NOT</b> the RFC-4180 {@code ""} doubling. Sending
     * {@code ""} for internal quotes produced {@code json_valid_check}
     * failures (the parser stored an invalid-JSON value). Sending {@code \"}
     * produced clean {@code fk_error} responses (payload was decoded correctly
     * by the parser). Backslash-doubling ({@code \} → {@code \\}) is required
     * so source content containing literal backslashes survives round-trip
     * through the parser's backslash-escape processing.
     *
     * <p><b>Known limitation not fixable here:</b> the same probe showed the
     * parser cannot correctly handle a comma sitting inside a quoted field —
     * even when properly wrapped and escaped, an internal {@code ,} causes
     * subsequent columns to shift and IS_ACTIVE (or whatever the trailing
     * column is) to receive garbage. This affects any value containing
     * multi-key JSON at the top level (e.g. real CASE_DATUM's {@code datum}).
     * That's a limitation of the CSV transport itself for this WaveMaker
     * version; those tables need a different code path.
     *
     * <p>Order matters: escape backslash FIRST so the backslashes we insert
     * when escaping quotes don't get double-escaped, then escape quotes.
     */
    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needsQuoting = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\\') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
        if (!needsQuoting) return s;
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /* ---------- helpers ---------- */

    private static Object lookup(Map<String, Object> row, String colName) {
        if (row == null || colName == null) return null;
        // 1. Exact match
        if (row.containsKey(colName)) return row.get(colName);
        // 2. Case-insensitive exact match (e.g. CREATED_BY vs created_by)
        String lower = colName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        // 3. snake_case → camelCase. FAWB's executeSQLs sometimes echoes
        //    Hibernate-mapped field names (camelCase) for JPA-entity tables,
        //    not raw DB columns (snake_case). Try converting and matching.
        String camel = snakeToCamel(colName);
        if (!camel.equals(colName)) {
            if (row.containsKey(camel)) return row.get(camel);
            String camelLower = camel.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(camelLower)) return e.getValue();
            }
        }
        return null;
    }

    /** Convert {@code snake_case} → {@code snakeCase}. Preserves case of first char. */
    private static String snakeToCamel(String s) {
        if (s == null || s.indexOf('_') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean upperNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') { upperNext = true; continue; }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
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

    /** Outcome of a CSV batch import. */
    public record Result(int status, String body, List<Object> assignedPks, String url, String dumpPath) {
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
