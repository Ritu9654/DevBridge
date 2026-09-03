package com.devbridge.api;

import com.devbridge.fawb.FawbClient;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Diagnostic endpoints. Currently: isolate whether FAWB's CSV {@code /import}
 * for CASE_DATUM breaks on payload size, by sending synthetic single-row CSVs
 * at progressively larger {@code datum} sizes.
 *
 * <p>Runs against an intentionally-missing {@code case_header_id} — so a
 * successful CSV parse will fail with a MariaDB foreign-key error, not a
 * "row conversion" error. That gives us a clean signal:
 * <ul>
 *   <li><b>HTTP 500 + "foreign key constraint fails"</b> → CSV parsed cleanly at
 *       this size; the payload is fine.</li>
 *   <li><b>HTTP 500 + "row conversion for column …"</b> → CSV parser choked
 *       at this size; this is the size threshold we've been fighting.</li>
 * </ul>
 * No cleanup needed since nothing lands in target (FK always blocks).
 */
@RestController
@RequestMapping("/api/diagnose")
public class DiagnoseController {

    private static final Logger log = LoggerFactory.getLogger(DiagnoseController.class);
    private static final DateTimeFormatter DATETIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProfileService profileService;
    private final ActiveProfileHolder activeProfileHolder;
    private final FawbClient fawbClient;

    public DiagnoseController(ProfileService profileService,
                              ActiveProfileHolder activeProfileHolder,
                              FawbClient fawbClient) {
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.fawbClient = fawbClient;
    }

    @PostMapping("/case-datum-size")
    public ResponseEntity<?> diagnoseCaseDatumSize(@RequestBody(required = false) DiagnoseRequest req) {
        String profileId = activeProfileHolder.getActiveProfileId();
        if (profileId == null) return ResponseEntity.badRequest().body(errorBody("No active profile."));
        Optional<ProjectProfile> pOpt = profileService.findById(profileId);
        if (pOpt.isEmpty()) return ResponseEntity.badRequest().body(errorBody("Active profile not found."));
        ProjectProfile profile = pOpt.get();

        String targetEnv = (req != null && req.targetEnv() != null) ? req.targetEnv() : "sandbox";
        int[] sizes = (req != null && req.sizes() != null && !req.sizes().isEmpty())
                ? req.sizes().stream().mapToInt(Integer::intValue).toArray()
                : new int[]{1_000, 10_000, 50_000, 100_000, 200_000, 300_000, 500_000};

        String targetBaseUrl = "sandbox".equalsIgnoreCase(targetEnv)
                ? profile.sandboxBaseUrl() : profile.designBaseUrl();
        if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
            return ResponseEntity.badRequest().body(errorBody("Profile has no " + targetEnv + " base URL."));
        }
        String dbService = profile.dbServiceName() != null && !profile.dbServiceName().isBlank()
                ? profile.dbServiceName() : "CaseManager";
        String url = trimTrailingSlash(targetBaseUrl) + "/services/" + dbService + "/CaseDatum/import";

        Report report = new Report();
        report.targetEnv = targetEnv;
        report.url = url;

        // Run six variants at each size to isolate content AND escape strategy.
        // Three payloads (plain / quotes / nested-backslash) crossed with
        // two escape strategies:
        //   A/B/C: RFC-4180 escape — inner `"` → `""` (current csvEscape)
        //   D/E/F: backslash escape — inner `"` → `\"` (OpenCSV default)
        // Hypothesis under test: WaveMaker's CSV parser expects `\"` for
        // quote escape, not `""`. If D/E/F return fk_error at all sizes
        // while A/B/C fail, hypothesis confirmed and we change csvEscape.
        for (int size : sizes) {
            String rowId = UUID.randomUUID().toString();
            String fakeHeaderId = "00000000-0000-0000-0000-" + String.format("%012d", size);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "A_plain_rfc",
                    buildPlainJson(size), false);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "B_quotes_rfc",
                    buildQuotedJson(size), false);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "C_nested_rfc",
                    buildNestedBackslashJson(size), false);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "D_plain_bs",
                    buildPlainJson(size), true);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "E_quotes_bs",
                    buildQuotedJson(size), true);
            runVariant(report, url, profile, rowId, fakeHeaderId, size, "F_nested_bs",
                    buildNestedBackslashJson(size), true);
        }

        // Summarise per variant
        Map<String, VariantSummary> byVariant = new LinkedHashMap<>();
        for (SizeResult r : report.results) {
            VariantSummary vs = byVariant.computeIfAbsent(r.variant, k -> new VariantSummary());
            vs.variant = r.variant;
            vs.total++;
            switch (r.classification) {
                case "fk_error" -> vs.fkErrors++;
                case "row_conversion_error" -> vs.rowConversionErrors++;
                case "json_valid_check" -> vs.jsonValidCheckFailures++;
                default -> vs.other++;
            }
        }
        report.byVariant = byVariant.values().stream().toList();
        StringBuilder s = new StringBuilder();
        s.append("Ran ").append(report.results.size()).append(" experiments across ")
                .append(byVariant.size()).append(" variants. Per-variant:\n");
        for (VariantSummary vs : byVariant.values()) {
            s.append("  ").append(vs.variant).append(": fk=").append(vs.fkErrors)
                    .append(" row_conv=").append(vs.rowConversionErrors)
                    .append(" json_valid=").append(vs.jsonValidCheckFailures)
                    .append(" other=").append(vs.other).append("\n");
        }
        s.append("Read: fk=CSV parsed cleanly (payload is fine); row_conv=CSV parser broke; ")
                .append("json_valid=CSV parsed but produced invalid JSON in the datum cell.");
        report.summary = s.toString();
        log.info("CASE_DATUM size diagnostic: {}", report.summary);
        return ResponseEntity.ok(report);
    }

    /** Classify FAWB's response body into a small set of categories. */
    private static String classifyResponse(int status, String body) {
        if (status >= 200 && status < 300) return "ok_unexpected";   // shouldn't happen with fake FK
        if (body == null) return "no_body";
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("foreign key constraint fails")) return "fk_error";
        if (lower.contains("row conversion for column")) return "row_conversion_error";
        if (lower.contains("constraint `case_datum.datum` failed") || lower.contains("json_valid")) return "json_valid_check";
        return "other_" + status;
    }

    private void runVariant(Report report, String url, ProjectProfile profile,
                             String baseRowId, String caseHeaderId, int datumSize,
                             String variantName, String datumJson, boolean bsEscape) {
        SizeResult sr = new SizeResult();
        sr.variant = variantName;
        sr.datumBytes = datumJson.length();
        String rowId = UUID.randomUUID().toString();   // fresh id per attempt
        try {
            byte[] csvBytes = buildCsvRow(rowId, caseHeaderId, datumJson, bsEscape);
            sr.csvBytes = csvBytes.length;
            HttpResponse<String> res = fawbClient.postMultipart(url, "file",
                    "diag_" + variantName + "_" + datumSize + ".csv", "text/csv",
                    csvBytes, profile.id());
            sr.httpStatus = res.statusCode();
            sr.responseBody = trunc(res.body(), 400);
            sr.classification = classifyResponse(res.statusCode(), res.body());
        } catch (Exception e) {
            sr.httpStatus = 0;
            sr.responseBody = e.getClass().getSimpleName() + ": " + e.getMessage();
            sr.classification = "exception";
        }
        log.info("Diag {} size={} → status={} class={}", variantName, datumSize, sr.httpStatus, sr.classification);
        report.results.add(sr);
    }

    /* ---------- payload variants ---------- */

    /** Plain padding — no backslashes, no inner-quote chars. Isolates SIZE. */
    private static String buildPlainJson(int targetBytes) {
        int overhead = "{\"pad\":\"\"}".length();
        int padLen = Math.max(1, targetBytes - overhead);
        return "{\"pad\":\"" + "a".repeat(padLen) + "\"}";
    }

    /** JSON with several inner-quote characters (need CSV doubling on the way out). */
    private static String buildQuotedJson(int targetBytes) {
        String prefix = "{\"key\":\"val\",\"pad\":\"";
        String suffix = "\"}";
        int padLen = Math.max(1, targetBytes - prefix.length() - suffix.length());
        return prefix + "a".repeat(padLen) + suffix;
    }

    /** JSON with an inner escaped-JSON string (backslash + quote). Mimics real CASE_DATUM shape. */
    private static String buildNestedBackslashJson(int targetBytes) {
        String prefix = "{\"outer\":\"{\\\"nestedKey\\\":\\\"";
        String suffix = "\\\"}\"}";
        int padLen = Math.max(1, targetBytes - prefix.length() - suffix.length());
        return prefix + "a".repeat(padLen) + suffix;
    }

    /**
     * Build a CASE_DATUM CSV row.
     *
     * @param bsEscape false → RFC 4180 escape (inner {@code "} → {@code ""}),
     *                 matching the current production {@code csvEscape};
     *                 true → OpenCSV-default backslash escape (inner {@code "}
     *                 → {@code \"}). Backslash-doubling ({@code \} → {@code \\})
     *                 is applied in both modes because the source content may
     *                 contain backslashes that must survive round-trip.
     */
    private static byte[] buildCsvRow(String rowId, String caseHeaderId, String datumJson, boolean bsEscape) {
        String now = DATETIME_FMT.withZone(ZoneOffset.UTC).format(Instant.now());
        String header = "id,created_by,created_on,updated_by,updated_on,version,case_header_id,"
                + "case_datum_type,external_reference,storage_key,datum,is_active";
        String bsDoubled = datumJson.replace("\\", "\\\\");
        String quoteEscaped = bsEscape
                ? bsDoubled.replace("\"", "\\\"")
                : bsDoubled.replace("\"", "\"\"");
        String datumEscaped = "\"" + quoteEscaped + "\"";
        String row = rowId + ",devbridge-diag," + now + ",devbridge-diag," + now + ",0,"
                + caseHeaderId + ",Application,DEVBRIDGE-DIAG,," + datumEscaped + ",true";
        return (header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }

    /* ---------- request / response shapes ---------- */

    public record DiagnoseRequest(String targetEnv, List<Integer> sizes) {}

    public static class Report {
        public String targetEnv;
        public String url;
        public final List<SizeResult> results = new ArrayList<>();
        public List<VariantSummary> byVariant;
        public String summary;
    }

    public static class SizeResult {
        public String variant;
        public int datumBytes;
        public int csvBytes;
        public int httpStatus;
        public String classification;
        public String responseBody;
    }

    public static class VariantSummary {
        public String variant;
        public int total;
        public int fkErrors;
        public int rowConversionErrors;
        public int jsonValidCheckFailures;
        public int other;
    }
}
