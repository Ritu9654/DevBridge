package com.devbridge.apps;

import com.devbridge.datamodel.DataModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQL literal generation for the FAWB {@code executeSQLs} endpoint.
 * <p>
 * Uses {@code INSERT ... SET} syntax (not {@code INSERT ... VALUES}) because
 * FAWB's SQL wrapper trips over column names that overlap SQL keywords
 * (e.g. {@code value}) even when backticked. The {@code SET} form assigns
 * each column individually and sidesteps that entirely.
 * <p>
 * All identifiers are backticked. Values are inlined (no bind parameters —
 * {@code executeSQLs} takes SQL as a single URL string). String literals are
 * single-quoted with doubled internal quotes; numbers/booleans/nulls are
 * emitted natively.
 */
public final class SqlBuilder {

    private SqlBuilder() {}

    /**
     * {@code INSERT INTO `T` SET `c1` = v1, `c2` = v2, ...}
     *
     * @param table  physical DB table name (backticked automatically)
     * @param values ordered column → value pairs. Insertion order preserved.
     */
    public static String insertSet(String table, Map<String, Object> values) {
        if (table == null || table.isBlank()) throw new IllegalArgumentException("table required");
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("no columns for INSERT");
        StringBuilder sb = new StringBuilder(128);
        sb.append("INSERT INTO ").append(ident(table)).append(" SET ");
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(ident(e.getKey())).append(" = ").append(literal(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    /**
     * {@code SELECT * FROM `T` WHERE `pk` = v} — for a single-row scoped fetch
     * (used for the root row of an import).
     */
    public static String selectByEquals(String table, String column, Object value) {
        return "SELECT * FROM " + ident(table)
                + " WHERE " + ident(column) + " = " + literal(value);
    }

    /**
     * {@code SELECT * FROM `T` WHERE `fk` IN (v1, v2, ...)} — for a scoped
     * fetch of child rows keyed to a set of parent PKs.
     * <p>
     * Returns {@code null} if {@code values} is empty (caller should skip the
     * SELECT entirely — no parents means no children).
     */
    public static String selectByIn(String table, String column, Collection<?> values) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(128);
        sb.append("SELECT * FROM ").append(ident(table))
          .append(" WHERE ").append(ident(column)).append(" IN (");
        boolean first = true;
        for (Object v : values) {
            if (!first) sb.append(", ");
            sb.append(literal(v));
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    /** {@code DELETE FROM `T` WHERE `pk` = v} — reverse of a single INSERT (for rollback / pre-clean). */
    public static String deleteByEquals(String table, String column, Object value) {
        return "DELETE FROM " + ident(table)
                + " WHERE " + ident(column) + " = " + literal(value);
    }

    /** {@code UPDATE `T` SET `col` = value WHERE `pk` = pkValue} — single-column UPDATE. */
    public static String updateSet(String table, String column, Object value,
                                   String pkColumn, Object pkValue) {
        return "UPDATE " + ident(table)
                + " SET " + ident(column) + " = " + literal(value)
                + " WHERE " + ident(pkColumn) + " = " + literal(pkValue);
    }

    /**
     * {@code UPDATE `T` SET `col` = CONCAT(`col`, 'chunk') WHERE `pk` = pkValue}.
     * Used to reassemble a LONGTEXT column value across multiple calls when a
     * single INSERT for the full value would exceed the URL length limit.
     */
    public static String updateConcatSet(String table, String column, Object chunk,
                                          String pkColumn, Object pkValue) {
        return "UPDATE " + ident(table)
                + " SET " + ident(column) + " = CONCAT(" + ident(column) + ", " + literal(chunk) + ")"
                + " WHERE " + ident(pkColumn) + " = " + literal(pkValue);
    }

    /**
     * Like {@link #updateSet} but transports the value as base64 and lets MariaDB
     * decode it via {@code FROM_BASE64}. Base64 alphabet is {@code [A-Za-z0-9+/=]}
     * — none of those (after URL-encoding {@code +/=} to {@code %2B %2F %3D})
     * can be misinterpreted by an aggressive proxy that URL-decodes twice.
     * Values with raw {@code &}, {@code %}, {@code '}, JSON, binary, etc. all
     * round-trip safely this way.
     */
    public static String updateSetBase64(String table, String column, String value,
                                          String pkColumn, Object pkValue) {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "UPDATE " + ident(table)
                + " SET " + ident(column) + " = FROM_BASE64('" + b64 + "')"
                + " WHERE " + ident(pkColumn) + " = " + literal(pkValue);
    }

    /**
     * Like {@link #updateConcatSet} but transports the chunk as base64:
     * {@code SET col = CONCAT(col, FROM_BASE64('...'))}. See
     * {@link #updateSetBase64} for the rationale.
     */
    public static String updateConcatSetBase64(String table, String column, String chunk,
                                                String pkColumn, Object pkValue) {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "UPDATE " + ident(table)
                + " SET " + ident(column) + " = CONCAT(" + ident(column)
                + ", FROM_BASE64('" + b64 + "'))"
                + " WHERE " + ident(pkColumn) + " = " + literal(pkValue);
    }

    /**
     * Combine multiple statements into a single {@code executeSQLs} call, semicolon-separated.
     * Blank / null statements are skipped.
     */
    public static String batch(List<String> statements) {
        if (statements == null || statements.isEmpty()) return "";
        List<String> keep = new ArrayList<>(statements.size());
        for (String s : statements) if (s != null && !s.isBlank()) keep.add(s.trim());
        return String.join("; ", keep);
    }

    /* ---------- identifier + literal quoting ---------- */

    /** Backtick a bare identifier. Doubles any embedded backtick (paranoid — shouldn't occur in real names). */
    public static String ident(String name) {
        if (name == null) throw new IllegalArgumentException("identifier required");
        return "`" + name.replace("`", "``") + "`";
    }

    /**
     * Turn a Java value into a SQL literal safe for inline emission.
     * <ul>
     *   <li>{@link Raw} — emitted verbatim (SQL function call or expression).</li>
     *   <li>{@code null} → {@code NULL}</li>
     *   <li>{@link Boolean} → {@code TRUE} / {@code FALSE}</li>
     *   <li>{@link Number} → native</li>
     *   <li>Everything else → single-quoted string, doubled internal quotes.</li>
     * </ul>
     */
    public static String literal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Raw r) return r.expr();
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (v instanceof Number n) return numberLiteral(n);
        return "'" + v.toString().replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * Emit a numeric literal without scientific notation. {@code Double.toString}
     * flips to {@code 1.787E9} for large values, which MariaDB rejects inside
     * {@code FROM_UNIXTIME(...)} and in some contexts for NUMERIC/DECIMAL
     * columns. {@link BigDecimal#toPlainString()} gives us plain decimal form.
     */
    private static String numberLiteral(Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (!Double.isFinite(d)) return "NULL";
            return new BigDecimal(n.toString()).toPlainString();
        }
        return n.toString();
    }

    /**
     * Sentinel wrapping a raw SQL expression that should be emitted verbatim
     * instead of quoted. Used for MariaDB function calls like
     * {@code FROM_UNIXTIME(1787928269.775)} — needed when a column is DATETIME
     * but the source row carries the value as a Unix-epoch number.
     */
    public record Raw(String expr) {}

    /**
     * Prepare a value for insertion based on its target column's type.
     * <p>
     * <b>Currently the only transformation:</b> FAWB's {@code executeSQLs}
     * serialises DATETIME columns as milliseconds-since-epoch (numbers), which
     * MariaDB can't cast back to DATETIME on the way in. We wrap such values
     * as {@code FROM_UNIXTIME(seconds)} — MariaDB's built-in conversion. Any
     * fractional seconds (from ms precision) are preserved.
     * <p>
     * Non-datetime columns and non-numeric values pass through unchanged.
     */
    /**
     * If {@code value} is a String containing URL-reserved chars ({@code &},
     * {@code %}, {@code +}, {@code #}), wrap it as a {@code FROM_BASE64('...')}
     * {@link Raw} expression. Otherwise pass it through unchanged.
     * <p>
     * <b>Why:</b> the FAWB proxy/servlet stack was observed to URL-decode the
     * {@code dbCommands} query parameter more than once for some request paths.
     * A raw {@code &} inside a string literal, when it round-trips through two
     * decodes, gets interpreted as a query-parameter separator on the second
     * pass and the SQL text is silently truncated at that point. Base64 output
     * uses only {@code [A-Za-z0-9+/=]} — none of which survive a double-decode
     * as reserved chars in a way that breaks the SQL — so this bypasses the
     * fragility entirely.
     */
    public static Object shieldStringValue(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) return value;
        if (!needsBase64Shield(s)) return value;
        String b64 = java.util.Base64.getEncoder().encodeToString(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Raw("FROM_BASE64('" + b64 + "')");
    }

    /** Chars in a string that make direct URL-quoted transport unsafe under double-decode. */
    private static boolean needsBase64Shield(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '%' || c == '+' || c == '#') return true;
        }
        return false;
    }

    public static Object convertForInsert(Object value, DataModel.Column col) {
        if (value == null) return null;
        if (col != null && col.javaType() != null) {
            String t = col.javaType().toLowerCase(Locale.ROOT);
            boolean isTemporal = "datetime".equals(t) || "timestamp".equals(t) || "date".equals(t);
            if (isTemporal && value instanceof Number n) {
                long ms = n.longValue();
                // MariaDB's FROM_UNIXTIME returns NULL for values before the
                // Unix epoch (1970-01-01). This affects date-of-birth columns
                // for anyone born pre-1970. For those, emit a literal
                // 'YYYY-MM-DD HH:MM:SS' string built via TIMESTAMPADD from
                // the epoch — MariaDB accepts negative-offset TIMESTAMPADD.
                if (ms < 0) {
                    long secs = ms / 1000;
                    return new Raw("TIMESTAMPADD(SECOND, " + secs + ", '1970-01-01 00:00:00')");
                }
                long secs = ms / 1000;
                long fracMs = ms % 1000;
                String secLiteral = (fracMs == 0)
                        ? Long.toString(secs)
                        : secs + "." + String.format("%03d", fracMs);
                return new Raw("FROM_UNIXTIME(" + secLiteral + ")");
            }
            // LONGTEXT/CLOB/BLOB values: repair HTML-encoded JSON (source data
            // sometimes has been html-escape'd 1..N times by ancestor apps) so
            // MariaDB's json_valid CHECK on the target accepts it. Then hand
            // off to the REST executor as a raw String — split mode does its
            // own base64 encoding per chunk if needed.
            if (isLargeTextType(t)) {
                if (value instanceof String s) return repairHtmlEncodedJson(s);
                return value;
            }
        }
        // Guard against URL-decoding fragility for any string carrying reserved chars.
        return shieldStringValue(value);
    }

    /** True if a column's javaType is a long-text-family type ({@code text}/{@code clob}/{@code longtext}/{@code blob}). */
    public static boolean isLargeTextType(String javaTypeLower) {
        if (javaTypeLower == null) return false;
        return "text".equals(javaTypeLower) || "clob".equals(javaTypeLower)
                || "longtext".equals(javaTypeLower) || "blob".equals(javaTypeLower);
    }

    /* ---------- HTML-entity repair for JSON columns ---------- */

    private static final java.util.Map<String, String> HTML_ENTITIES = java.util.Map.of(
            "amp", "&",
            "quot", "\"",
            "lt", "<",
            "gt", ">",
            "apos", "'",
            "nbsp", " "
    );

    /**
     * Decode HTML entities in the given string, once. Handles named entities
     * ({@code &amp;}, {@code &quot;}, {@code &lt;}, {@code &gt;}, {@code &apos;},
     * {@code &nbsp;}) and numeric ones ({@code &#38;}, {@code &#x26;}). Unknown
     * entities are left as-is. Returns the input unchanged if there are no
     * recognisable entities.
     */
    public static String htmlDecodeOnce(String s) {
        if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        boolean anyDecoded = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                int semi = s.indexOf(';', i + 1);
                if (semi > i && semi - i <= 8) {
                    String name = s.substring(i + 1, semi);
                    String decoded = decodeEntity(name);
                    if (decoded != null) {
                        out.append(decoded);
                        i = semi + 1;
                        anyDecoded = true;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return anyDecoded ? out.toString() : s;
    }

    private static String decodeEntity(String name) {
        String direct = HTML_ENTITIES.get(name.toLowerCase(Locale.ROOT));
        if (direct != null) return direct;
        if (name.startsWith("#")) {
            try {
                int code = name.startsWith("#x") || name.startsWith("#X")
                        ? Integer.parseInt(name.substring(2), 16)
                        : Integer.parseInt(name.substring(1));
                return String.valueOf((char) code);
            } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * If {@code candidate} isn't already valid JSON, repeatedly HTML-decode it
     * until it becomes valid or no further decoding is possible. Guards against
     * pathological input via a max-iteration cap.
     * <p>
     * <b>Why this exists:</b> some data in FAWB projects has been HTML-encoded
     * multiple times by ancestor applications (each save doing another
     * {@code htmlEscape(existing)}). SELECTs return the encoded form; MariaDB
     * on the target rejects it via {@code json_valid} because the encoding
     * broke JSON structure (e.g. {@code &quot;} appears where {@code "} is
     * expected). Decoding until stable-and-valid restores round-trip fidelity.
     */
    public static String repairHtmlEncodedJson(String candidate) {
        if (candidate == null || candidate.isEmpty()) return candidate;
        if (isJsonParseable(candidate)) return candidate;
        String current = candidate;
        for (int i = 0; i < 50; i++) {
            String next = htmlDecodeOnce(current);
            if (next.equals(current)) return current;   // no more entities to decode
            current = next;
            if (isJsonParseable(current)) return current;
        }
        return current;
    }

    /** Cheap JSON-parseability check using Jackson. */
    private static boolean isJsonParseable(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Always wrap a string value as {@code FROM_BASE64('...')}, regardless of
     * whether it contains URL-reserved chars. Used by the executor when
     * transporting a large-text column inline in a single INSERT — base64
     * output has no chars that trip the FAWB proxy stack, so the full value
     * survives intact and any CHECK constraints (e.g. {@code JSON_VALID}) see
     * the real content, not a placeholder.
     */
    public static Object forceBase64Shield(String value) {
        if (value == null) return null;
        if (value.isEmpty()) return "";
        String b64 = java.util.Base64.getEncoder().encodeToString(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new Raw("FROM_BASE64('" + b64 + "')");
    }
}
