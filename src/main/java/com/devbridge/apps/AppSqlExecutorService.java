package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.apps.AppSqlPlanService.ColumnPolicies;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.NestedInsertConfig;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Writes an app's rows into the target env by generating INSERT statements and
 * running them via the same {@code executeSQLs} endpoint the SQL runner uses.
 * Two paths depending on the table's PK strategy:
 *
 * <ul>
 *   <li><b>Client-known PK</b> (assigned, including assigned-UUID we regenerate):
 *   we know the new PK before the INSERT, so we can batch many rows per HTTP call
 *   and populate the id-map immediately. Big speedup on wide apps.</li>
 *   <li><b>Server-assigned PK</b> (identity, sequence): routed to the per-entity
 *   REST CRUD endpoint ({@code POST /services/{db}/{Entity}}). FAWB's
 *   {@code executeSQLs} returns {@code "success"} for every statement including
 *   {@code SELECT LAST_INSERT_ID()}, so we cannot recover the id through the SQL
 *   endpoint at all — REST returns the created row with its id in the body.</li>
 * </ul>
 *
 * <p>Safety invariants:
 * <ol>
 *   <li>Halt on first HTTP error, SQL error, or duplicate PK we can't resolve.</li>
 *   <li>Journal after every batch — an interrupted run leaves a truthful record.</li>
 *   <li>Reference tables never touched (skipped by the FK-graph walk too).</li>
 *   <li>Pre-clean by DELETE if an assigned-PK row's source PK already exists in target.</li>
 * </ol>
 */
@Service
public class AppSqlExecutorService {

    private static final Logger log = LoggerFactory.getLogger(AppSqlExecutorService.class);
    /** Hard upper bound on rows per batch — dynamic batching uses URL length as the real limit. */
    private static final int MAX_BATCH_SIZE = 100;
    /**
     * Cap on the URL-encoded SQL length per HTTP call. {@code executeSQLs} puts
     * the whole SQL in a {@code ?dbCommands=} GET query string; nginx's
     * {@code large_client_header_buffers} default is 8 KB and includes the whole
     * request line, so we budget 6500 bytes for the SQL plus ~1500 for the base
     * URL, method, headers, etc. Measured by actually URL-encoding candidate
     * statements — no more guesswork about encoding overhead.
     */
    private static final int MAX_ENCODED_SQL_LENGTH = 6000;
    /**
     * Raw-char chunk size when a row's INSERT exceeds URL budget and we split
     * LONGTEXT columns into CONCAT-append UPDATEs. Conservative — JSON content
     * (quotes, braces) URL-encodes to ~2.5x length, so 1500 raw ≈ 4500 encoded,
     * comfortably below {@link #MAX_ENCODED_SQL_LENGTH} even with prefix overhead.
     */
    private static final int LARGE_TEXT_CHUNK_CHARS = 1500;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final SqlService sqlService;
    private final ObjectMapper mapper;
    private final ImportJournalStore journalStore;
    private final AppRestInsertService restInserter;
    private final AppCsvImportService csvImporter;
    private final SchemaMetadataService schemaMetadata;
    private final AppNestedInsertService nestedInserter;
    private final ReferenceRemapService referenceRemap;
    private final ReferenceConfigDetector referenceConfigDetector;

    public AppSqlExecutorService(SqlService sqlService, ObjectMapper mapper,
                                 ImportJournalStore journalStore,
                                 AppRestInsertService restInserter,
                                 AppCsvImportService csvImporter,
                                 SchemaMetadataService schemaMetadata,
                                 AppNestedInsertService nestedInserter,
                                 ReferenceRemapService referenceRemap,
                                 ReferenceConfigDetector referenceConfigDetector) {
        this.sqlService = sqlService;
        this.mapper = mapper;
        this.journalStore = journalStore;
        this.restInserter = restInserter;
        this.csvImporter = csvImporter;
        this.schemaMetadata = schemaMetadata;
        this.nestedInserter = nestedInserter;
        this.referenceRemap = referenceRemap;
        this.referenceConfigDetector = referenceConfigDetector;
    }

    public ImportJournal execute(FetchResult fetch, ProjectProfile profile,
                                 String sourceEnv, String targetEnv, String appId) {
        String jobId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        String targetBaseUrl = resolveBaseUrl(profile, targetEnv);
        if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
            throw new IllegalStateException("Profile has no " + targetEnv + " base URL. Cannot execute.");
        }

        ImportJournal journal = new ImportJournal(
                jobId, profile.id(), appId, sourceEnv, targetEnv, targetBaseUrl,
                startedAt, null, "running", null, fetch.totalRowCount(), List.of());
        journalStore.write(journal);

        FkGraph graph = fetch.graph();
        Set<String> refSet = normaliseReferences(profile.referenceTables());
        FkGraph.TopoResult topo = graph.topoSort(new ArrayList<>(fetch.rowsByTable().keySet()));

        // Auto-detect tables that MUST use the CSV /import path. CHECK
        // constraints (like json_valid on a LONGTEXT column) would reject the
        // partial-content states our SQL split-CONCAT-UPDATE writes between
        // chunks, so those tables need the single-shot CSV path. We can't
        // read CHECK constraints from the FAWB dataModel — query the target
        // DB's information_schema directly. Falls back to profile config /
        // built-in default if the query is unavailable.
        Set<String> csvOnlyTables = resolveCsvOnlyTables(profile, targetEnv, graph);
        log.info("Import job {}: CSV-only tables for this run = {}", jobId, csvOnlyTables);

        // Nested-insert config: if set, one specific parent+child pair goes
        // through a project-specific JSON endpoint (see NestedInsertConfig
        // Javadoc). The child table is skipped from standalone processing —
        // its rows are bundled with the parent's insert.
        NestedInsertConfig nestedCfg = profile.nestedInsertConfig();
        boolean useNested = nestedCfg != null && nestedCfg.isUsable();
        String nestedParentUpper = useNested ? nestedCfg.parentTable().toUpperCase(Locale.ROOT) : null;
        String nestedChildUpper = useNested ? nestedCfg.childTable().toUpperCase(Locale.ROOT) : null;
        if (useNested) {
            log.info("Import job {}: nested-insert enabled — bundling {} into {} via {}",
                    jobId, nestedChildUpper, nestedParentUpper, nestedCfg.endpointUrl());
        }

        // id-map: sourceTable (uppercase) → sourcePk → newPk
        Map<String, Map<Object, Object>> idMap = new LinkedHashMap<>();
        // Tables whose rows we skipped (FAWB refused writes, entity restrictions,
        // etc.). Used to cascade-skip children whose FK remap points at a
        // skipped parent — instead of halting with "cannot remap FK", we log
        // and continue. Names are uppercase-normalised.
        Set<String> skippedTables = new HashSet<>();
        StepCounter counter = new StepCounter();
        // JPA service name used in REST CRUD URLs: /services/{dbService}/{Entity}
        String dbService = profile.dbServiceName() != null && !profile.dbServiceName().isBlank()
                ? profile.dbServiceName()
                : "Core";

        // Pre-populate the id-map for reference tables. Configs come from two
        // sources — auto-detection (unique-index scan against the target DB)
        // and the user's profile override — merged with the user winning
        // per-table. Then the remapper resolves each source id to target id
        // via natural-key matching; from the app-table loop's perspective it
        // then looks like any other FK remap through the idMap.
        Map<String, com.devbridge.profile.ReferenceTableConfig> detectedConfigs =
                referenceConfigDetector.detectFor(fetch, profile, targetEnv);
        Map<String, com.devbridge.profile.ReferenceTableConfig> effectiveConfigs =
                ReferenceConfigDetector.merge(detectedConfigs, profile.referenceTableConfigs());
        log.info("Reference configs for job {}: auto-detected {} table(s), user override {} table(s), effective {} table(s)",
                jobId, detectedConfigs.size(),
                profile.referenceTableConfigs() == null ? 0 : profile.referenceTableConfigs().size(),
                effectiveConfigs.size());
        try {
            referenceRemap.prepopulate(fetch, profile, sourceEnv, targetEnv, effectiveConfigs, idMap);
        } catch (ReferenceRemapService.RemapException e) {
            log.warn("Reference remap failed for job {}: {}", jobId, e.getMessage());
            journal = journal.withStatus("failed",
                    "Reference remap: " + e.getMessage(), Instant.now());
            journalStore.write(journal);
            return journal;
        } catch (Exception e) {
            log.warn("Reference remap threw unexpected error for job {}", jobId, e);
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "(no detail)");
            journal = journal.withStatus("failed",
                    "Reference remap: " + msg, Instant.now());
            journalStore.write(journal);
            return journal;
        }

        try {
            for (String tableName : topo.ordered()) {
                if (refSet.contains(FkGraph.norm(tableName))) continue;
                // Skip the nested child table — its rows are handled with its parent below.
                if (useNested && FkGraph.norm(tableName).equals(nestedChildUpper)) {
                    log.info("Import job {}: skipping {} — bundled with {}",
                            jobId, tableName, nestedParentUpper);
                    continue;
                }
                List<Map<String, Object>> rows = fetch.rowsByTable().get(tableName);
                if (rows == null || rows.isEmpty()) continue;
                DataModel.Table table = graph.table(tableName).orElseThrow(() ->
                        new HaltException("Table '" + tableName + "' missing from dataModel."));
                ColumnPolicies policies = ColumnPolicies.compute(table, graph, refSet);

                // Nested-insert parent path: bundle this table's row + its
                // configured child's rows into one POST per parent-row.
                if (useNested && FkGraph.norm(tableName).equals(nestedParentUpper)) {
                    journal = executeNestedBundle(table, rows, policies, graph, refSet,
                            nestedCfg, fetch, idMap, journal, profile, targetEnv, targetBaseUrl, counter);
                    continue;
                }

                // Additionally route assigned-PK tables via REST when any of
                // their downstream children go via REST (identity/sequence PK).
                // Reason: FAWB's REST create endpoints run inside Hibernate's
                // JPA session. If a child's REST insert references a parent
                // that was inserted via raw SQL (bypassing the session), JPA
                // throws TransientObjectException even though the row exists.
                // Routing the parent via REST too keeps JPA's session cache
                // consistent for downstream inserts. Seen with PERSON (assigned)
                // → Employment (identity, references personId).
                boolean routeViaRestForJpaCache = "assigned".equalsIgnoreCase(policies.pkStrategy)
                        && hasIdentityChildInFetch(tableName, graph, fetch);
                if ("identity".equalsIgnoreCase(policies.pkStrategy)
                        || "sequence".equalsIgnoreCase(policies.pkStrategy)
                        || routeViaRestForJpaCache) {
                    if (routeViaRestForJpaCache) {
                        log.info("Import job {}: routing assigned-PK table {} via REST — its walked children include identity tables that need JPA session cache to see this row.",
                                jobId, tableName);
                    }
                    // Server-assigned PKs: route through per-entity REST CRUD
                    // (POST /services/{db}/{Entity}). FAWB's executeSQLs returns
                    // "success" for SELECT LAST_INSERT_ID(), so chained INSERT+SELECT
                    // cannot recover the id. REST returns the created row with its
                    // server-assigned id in the response body — read from body.id.
                    journal = executeTableViaRest(table, rows, policies, idMap, journal, profile,
                            targetEnv, targetBaseUrl, dbService, counter, skippedTables);
                } else if (tableNeedsCsvImportPath(table, csvOnlyTables)) {
                    // Only route to CSV /import when we KNOW a table needs it —
                    // i.e. has a json_valid CHECK constraint on a LONGTEXT
                    // column that both (a) requires bytes-clean transport (SQL
                    // via URL is base64-shielded and works, but split-CONCAT-
                    // UPDATE for oversized rows leaves partial invalid-JSON
                    // between chunks — CHECK constraint rejects intermediate
                    // state), and (b) the CSV /import endpoint bypasses the
                    // XSS filter which corrupts JSON on the REST create path.
                    //
                    // CHECK constraints aren't exposed in the FAWB dataModel
                    // JSON — we can't detect them programmatically. So we
                    // maintain a small explicit list. CASE_DATUM.datum is the
                    // only known case in the CaseManager schema; other LONGTEXT
                    // columns (e.g. AFFORDABILITY_ASSESSMENTS.financials) are
                    // free-form text and take the SQL path fine.
                    //
                    // Why not route ALL LONGTEXT through CSV as a "safe
                    // default"? Because the /import endpoint has a structural
                    // visibility gap: children imported through /import can't
                    // see parents that were committed via other endpoints,
                    // even with retries and 5s+ delay. Every table on CSV is
                    // one more chance to hit the gap. Keep the list minimal.
                    journal = executeTableViaCsvImport(table, rows, policies, idMap, journal, profile,
                            targetEnv, targetBaseUrl, dbService, counter);
                } else {
                    journal = executeAssignedTable(table, rows, policies, idMap, journal, profile,
                            targetEnv, targetBaseUrl, counter);
                }
            }
            journal = journal.withStatus("success",
                    "All " + counter.get() + " row(s) written across "
                            + fetch.rowsByTable().size() + " table(s).",
                    Instant.now());
        } catch (HaltException e) {
            log.warn("Import halted for job {}: {}", jobId, e.getMessage());
            // Prefer the exception's journal snapshot — it holds any entries
            // (including silent-commit "created-then-error" markers) that were
            // added inside helpers before they threw. Our own `journal` variable
            // wouldn't see those, since ImportJournal is immutable and callee
            // reassignments don't propagate through a throw.
            if (e.journalSnapshot != null) journal = e.journalSnapshot;
            journal = rollbackAndAnnotate(journal, profile, targetEnv, graph, e.getMessage());
        } catch (Exception e) {
            // Unexpected failure (NPE, IO, JSON parse, etc.) — same rollback path.
            // The journal already contains every row that landed on target;
            // walking it in reverse is safe regardless of what threw.
            log.warn("Import failed unexpectedly for job {}", jobId, e);
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "(no detail)");
            journal = rollbackAndAnnotate(journal, profile, targetEnv, graph, msg);
        }

        Path journalPath = journalStore.write(journal);
        log.info("Import job {} finished with status={}, journal at {}",
                jobId, journal.status(), journalPath);
        return journal;
    }

    /* ---------- assigned PK: batch INSERT ---------- */

    private ImportJournal executeAssignedTable(DataModel.Table table, List<Map<String, Object>> rows,
                                                ColumnPolicies pol,
                                                Map<String, Map<Object, Object>> idMap,
                                                ImportJournal journal, ProjectProfile profile,
                                                String targetEnv, String targetBaseUrl,
                                                StepCounter counter) throws Exception {
        String tableName = table.name();
        String tableKey = FkGraph.norm(tableName);
        String primaryPk = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);

        // 1. Pre-compute per-row: source PK, new PK, INSERT SET map.
        //    All done up-front so we can pre-clean target-side collisions and know every new PK
        //    before children look up in idMap.
        List<PreparedRow> prepared = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object sourcePk = primaryPk != null ? lookup(row, primaryPk) : null;
            Map<String, Object> setPairs = new LinkedHashMap<>();
            Object newPk = sourcePk;
            for (DataModel.Column col : safe(table.columns())) {
                String cname = col.name();
                if (cname == null) continue;
                String lower = cname.toLowerCase(Locale.ROOT);
                if (pol.strippedLower.contains(lower)) continue;

                Object v = lookup(row, cname);
                // Regenerate assigned UUID PKs so cloned sandboxes don't collide with existing rows
                if (pol.uuidPkColumnsLower.contains(lower) && isUuidLike(v)) {
                    String fresh = UUID.randomUUID().toString();
                    setPairs.put(cname, fresh);
                    if (cname.equalsIgnoreCase(primaryPk)) newPk = fresh;
                    continue;
                }
                ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
                if (remap != null && v != null) {
                    Object mapped = lookupIdMap(idMap, remap.targetTable(), v);
                    if (mapped == null && remap.useSentinel()) {
                        mapped = ReferenceRemapService.lookupSentinel(idMap, remap.targetTable());
                    }
                    if (mapped == null) {
                        if (!remap.isReferenceTarget()) {
                            throw new HaltException(
                                    "Cannot remap FK " + tableName + "." + cname + " = " + v
                                    + " → " + remap.targetTable() + ".\n"
                                    + "  What this means: this row references " + remap.targetTable()
                                    + "'s row with source-side id=" + v + ", but the id-map has no entry for it.\n"
                                    + "  Why: the walker didn't fetch/insert " + remap.targetTable()
                                    + " OR the walker fetched it but its insert previously failed silently.\n"
                                    + "  Likely fix: (a) if " + remap.targetTable() + " is app-scoped, ensure it's NOT listed in profile.referenceTables so the walker fetches it; "
                                    + "(b) if it's shared-lookup data, add it to profile.referenceTableConfigs with a natural key; "
                                    + "(c) check earlier journal entries for a failure on " + remap.targetTable() + " that halted its insert.",
                                    journal);
                        }
                        // Reference target with no natural-key remap configured
                        // — fall back to pass-through of the source value.
                        setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
                        continue;
                    }
                    setPairs.put(cname, SqlBuilder.convertForInsert(mapped, col));
                    // Shared-PK case (e.g. CASE_CREDIT_APP.id IS FK to CASE_HEADER.id):
                    // the PK column is being remapped, so our new PK is the remapped
                    // value, not the source PK. Without this, verification and the
                    // id-map track the source PK — INSERT stores mapped, but SELECT
                    // and children look for source. This is the fix for the
                    // "post-insert verification found 0 rows" bug on CASE_CREDIT_APP.
                    if (cname.equalsIgnoreCase(primaryPk)) newPk = mapped;
                    continue;
                }
                setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
            }
            prepared.add(new PreparedRow(sourcePk, newPk, setPairs));
        }

        // 2. Populate id-map so children can already see all new PKs (parents batch-inserted below)
        if (primaryPk != null) {
            Map<Object, Object> forTable = idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>());
            for (PreparedRow pr : prepared) {
                if (pr.sourcePk != null && pr.newPk != null) forTable.put(pr.sourcePk, pr.newPk);
            }
        }

        // 3. Batch INSERTs. For passed-through PK values (sourcePk == newPk) we
        //    prepend a DELETE-by-PK to survive re-imports without collision.
        boolean passthroughPk = !pol.uuidPkColumnsLower.isEmpty() ? false : true;
        // If the PK isn't UUID-regenerated AND generator is "assigned" AND PK column not stripped,
        // the source value goes through unchanged → possible collision.
        boolean pkStripped = primaryPk != null && pol.strippedLower.contains(primaryPk.toLowerCase(Locale.ROOT));
        boolean needsPreClean = "assigned".equalsIgnoreCase(pol.pkStrategy)
                && primaryPk != null && !pkStripped && pol.uuidPkColumnsLower.isEmpty();

        // Post-insert verification: disabled by default because the extra
        // SELECT COUNT(*) per batch doubles HTTP round-trips for assigned-PK tables
        // and dominates import time. Re-enable here if you see silent FAWB phantom
        // successes ("created" journal entries but row not in target).
        final boolean verifyAfterInsert = false;

        int i = 0;
        while (i < prepared.size()) {
            List<String> statements = new ArrayList<>();
            List<PreparedRow> chunk = new ArrayList<>();
            int runningEncoded = 0;
            boolean handledOversized = false;

            while (i < prepared.size() && chunk.size() < MAX_BATCH_SIZE) {
                PreparedRow pr = prepared.get(i);
                // For single-INSERT sizing/emission, base64-shield every large-text
                // value first — that's how they must be transported (URL-safe) AND
                // it lets CHECK constraints see the real content in ONE call. If
                // the row still fits under budget after shielding, we send it as
                // a single INSERT; only truly oversized rows fall to split mode.
                Map<String, Object> shieldedPairs = shieldLargeTextForInline(pr.setPairs(), table);
                List<String> rowStmts = new ArrayList<>(2);
                if (needsPreClean && pr.newPk != null) {
                    rowStmts.add(SqlBuilder.deleteByEquals(tableName, primaryPk, pr.newPk));
                }
                rowStmts.add(SqlBuilder.insertSet(tableName, shieldedPairs));
                int rowEncoded = 0;
                for (String s : rowStmts) rowEncoded += urlEncodedLength(s) + urlEncodedLength("; ");

                if (rowEncoded > MAX_ENCODED_SQL_LENGTH) {
                    // Even with base64 shielding this row won't fit — must split.
                    // Flush current batch first (via break with chunk non-empty).
                    if (chunk.isEmpty()) {
                        journal = executeOversizedRow(pr, table, primaryPk, needsPreClean,
                                journal, profile, targetEnv, counter);
                        i++;
                        handledOversized = true;
                    }
                    break;
                }

                if (!chunk.isEmpty() && runningEncoded + rowEncoded > MAX_ENCODED_SQL_LENGTH) break;
                statements.addAll(rowStmts);
                chunk.add(pr);
                runningEncoded += rowEncoded;
                i++;
            }
            if (handledOversized) continue;

            if (!statements.isEmpty()) {
                String sql = SqlBuilder.batch(statements);
                ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
                journal = recordChunk(chunk, res, table, tableName, journal, counter, targetBaseUrl, needsPreClean, profile, targetEnv);

                if (verifyAfterInsert && primaryPk != null) {
                    verifyChunkLanded(chunk, table, primaryPk, profile, targetEnv);
                }
            }
        }
        return journal;
    }

    /**
     * Run a {@code SELECT COUNT(*) WHERE pk IN (...)} to confirm the rows
     * we just inserted are actually in target. If the count is less than
     * expected, the INSERT reported success but the row(s) didn't commit —
     * halt with a clear error so the user sees the real problem instead of
     * a downstream FK failure with no cause.
     */
    private void verifyChunkLanded(List<PreparedRow> chunk, DataModel.Table table,
                                   String primaryPk, ProjectProfile profile, String targetEnv) throws Exception {
        List<Object> newPks = new ArrayList<>();
        for (PreparedRow pr : chunk) if (pr.newPk != null) newPks.add(pr.newPk);
        if (newPks.isEmpty()) return;
        String sql = "SELECT COUNT(*) AS n FROM " + SqlBuilder.ident(table.name())
                + " WHERE " + SqlBuilder.ident(primaryPk) + " IN (";
        StringBuilder inList = new StringBuilder();
        boolean first = true;
        for (Object pk : newPks) {
            if (!first) inList.append(", ");
            inList.append(SqlBuilder.literal(pk));
            first = false;
        }
        sql += inList + ")";
        ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
        if (res.status() < 200 || res.status() >= 300) {
            log.warn("Post-insert verification SELECT failed HTTP {} for {} — skipping verification.",
                    res.status(), table.name());
            return;
        }
        // Parse: response is envelope-array with one envelope containing {"n":N}
        try {
            JsonNode arr = mapper.readTree(res.body());
            if (arr.isArray() && arr.size() > 0) {
                JsonNode env = arr.get(0);
                JsonNode resp = env.get("response");
                if (resp != null && resp.isTextual()) {
                    JsonNode parsed = mapper.readTree(resp.asText());
                    int count = parsed.path("n").asInt(-1);
                    if (count >= 0 && count < newPks.size()) {
                        throw new HaltException(
                                "Post-insert verification FAILED for " + table.name()
                                        + ": expected " + newPks.size() + " row(s) with our PKs to exist, but SELECT COUNT found only "
                                        + count + ". The INSERT reported success but did not persist. "
                                        + "Check server logs, connection pooling, or transaction isolation settings.");
                    }
                }
            }
        } catch (HaltException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Post-insert verification parse error for {}: {}", table.name(), e.getMessage());
        }
    }

    /* ---------- split INSERT + CONCAT UPDATE for oversized rows ---------- */

    /**
     * Handle a row whose INSERT alone exceeds URL budget. Strategy:
     * <ol>
     *   <li>Identify large text columns (javaType {@code text} / {@code clob} /
     *       {@code longtext}) with non-empty string values.</li>
     *   <li>Emit a small INSERT with those columns set to {@code ''}.</li>
     *   <li>For each large column, chunk its value at {@link #LARGE_TEXT_CHUNK_CHARS}
     *       raw chars and emit {@code UPDATE ... SET col = 'chunk1' WHERE pk = X}
     *       for the first chunk, then {@code UPDATE ... SET col = CONCAT(col, 'chunkN')}
     *       for subsequent chunks.</li>
     * </ol>
     * If any step fails, halt (caller triggers rollback which will DELETE this row).
     * Journal records a single {@code created} entry for the row on success, or a
     * {@code failed} entry on any halt.
     */
    private ImportJournal executeOversizedRow(PreparedRow pr, DataModel.Table table,
                                              String primaryPk, boolean needsPreClean,
                                              ImportJournal journal, ProjectProfile profile,
                                              String targetEnv, StepCounter counter) throws Exception {
        String tableName = table.name();
        int order = counter.next();
        if (primaryPk == null || pr.newPk == null) {
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "SQL split-insert", pr.sourcePk, null, 400, "failed",
                    "Cannot split-insert without a known PK — table has no assignable primary key.",
                    Instant.now()));
            journalStore.write(journal);
            throw new HaltException("Oversized row on " + tableName + " has no PK to target CONCAT UPDATEs.");
        }

        // Split columns into small vs large text
        Map<String, DataModel.Column> byName = new LinkedHashMap<>();
        for (DataModel.Column c : safe(table.columns())) {
            if (c.name() != null) byName.put(c.name().toLowerCase(Locale.ROOT), c);
        }
        Map<String, Object> smallCols = new LinkedHashMap<>();
        Map<String, String> largeTextCols = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : pr.setPairs.entrySet()) {
            String cname = e.getKey();
            Object v = e.getValue();
            DataModel.Column col = byName.get(cname.toLowerCase(Locale.ROOT));
            if (isLargeTextColumn(col) && v instanceof String s && !s.isEmpty()) {
                largeTextCols.put(cname, s);
                // NULL initial (if column allows) satisfies most CHECK constraints
                // like JSON_VALID; '' fallback for NOT NULL columns without a
                // sensible default. First CONCAT UPDATE below uses plain SET, so
                // NULL is fully replaced by the first chunk — no CONCAT(NULL, …) trap.
                smallCols.put(cname, (col != null && col.nullable()) ? null : "");
            } else {
                smallCols.put(cname, v);
            }
        }

        // 1. Small INSERT (+ optional pre-clean DELETE)
        List<String> insertStmts = new ArrayList<>(2);
        if (needsPreClean) insertStmts.add(SqlBuilder.deleteByEquals(tableName, primaryPk, pr.newPk));
        insertStmts.add(SqlBuilder.insertSet(tableName, smallCols));
        String insertSql = SqlBuilder.batch(insertStmts);
        ExecuteResult res = sqlService.execute(profile, targetEnv, insertSql);
        String insertErr = firstStatementError(res);
        if (insertErr != null) {
            // Silent-commit check: if the row actually landed in target despite
            // FAWB reporting a failure (JPA post-write listener throwing, etc.),
            // journal it with the real targetPk so rollback can DELETE it and
            // we don't accumulate orphans.
            Object landedPk = verifyRowLanded(profile, targetEnv, tableName, primaryPk, pr.newPk);
            if (landedPk != null) {
                log.warn("Silent commit detected in oversized path: row {}={} landed in target despite FAWB error '{}'. Continuing — downstream FKs can reference it.",
                        primaryPk, pr.newPk, trunc(insertErr));
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "SQL split-insert", pr.sourcePk, landedPk, 200, "created-then-error",
                        "DB inserted the row but FAWB reported error (continued past — downstream tables reference this row's id normally). Original error: "
                                + trunc(insertErr), Instant.now()));
                journalStore.write(journal);
                dumpFailedResponseBody(res.body(), journal.profileId(), tableName, order);
                // No throw — the row is in target, id-map already has it, and
                // the caller loop can proceed to subsequent tables. If a later
                // step halts, rollback still finds this entry via targetPk.
                return journal;
            }
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "SQL split-insert", pr.sourcePk, null, res.status(), "failed",
                    "Split-INSERT failed — " + insertErr, Instant.now()));
            journalStore.write(journal);
            dumpFailedResponseBody(res.body(), journal.profileId(), tableName, order);
            throw new HaltException("Split-INSERT failed for " + tableName + ": " + insertErr, journal);
        }

        // 2. CONCAT UPDATEs per large column, transported as base64 to sidestep
        //    URL-decoding fragility in the FAWB stack (raw '&' in chunk content
        //    was being interpreted as a query-param separator, truncating SQL).
        for (Map.Entry<String, String> col : largeTextCols.entrySet()) {
            String colName = col.getKey();
            String fullValue = col.getValue();
            List<String> chunks = chunkString(fullValue, LARGE_TEXT_CHUNK_CHARS);
            for (int idx = 0; idx < chunks.size(); idx++) {
                String chunkValue = chunks.get(idx);
                String sql = (idx == 0)
                        ? SqlBuilder.updateSetBase64(tableName, colName, chunkValue, primaryPk, pr.newPk)
                        : SqlBuilder.updateConcatSetBase64(tableName, colName, chunkValue, primaryPk, pr.newPk);
                ExecuteResult ur = sqlService.execute(profile, targetEnv, sql);
                String updateErr = firstStatementError(ur);
                if (updateErr != null) {
                    journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                            "SQL split-update", pr.sourcePk, pr.newPk, ur.status(), "failed",
                            "UPDATE " + colName + " chunk " + (idx + 1) + "/" + chunks.size()
                                    + " failed — " + updateErr,
                            Instant.now()));
                    journalStore.write(journal);
                    throw new HaltException("Split-UPDATE failed on " + tableName + "." + colName
                            + " chunk " + (idx + 1) + "/" + chunks.size() + ": " + updateErr);
                }
            }
        }

        // 3. Journal a single success entry — rollback will DELETE by targetId
        String msg = largeTextCols.isEmpty()
                ? "OK (split-mode)"
                : "OK (split — INSERT + " + largeTextCols.size() + " column(s) chunked as CONCAT UPDATEs)";
        journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                "SQL split-insert", pr.sourcePk, pr.newPk, 200, "created", msg, Instant.now()));
        journalStore.write(journal);
        return journal;
    }

    /** True if a column is FAWB-declared as a long text type — {@code text} / {@code clob} / {@code longtext}. */
    private static boolean isLargeTextColumn(DataModel.Column col) {
        if (col == null) return false;
        return SqlBuilder.isLargeTextType(col.javaType() == null ? null : col.javaType().toLowerCase(Locale.ROOT));
    }

    /**
     * Insert each parent row bundled with its own child rows via the
     * project-configured JSON endpoint (see {@link NestedInsertConfig}).
     * One HTTP POST per parent. Child rows are grouped by their FK-to-parent
     * value, so each POST carries just the children of the parent it's
     * creating. Journal records both parent and each child; on any failure
     * we halt and the caller triggers rollback.
     *
     * <p>Rollback still uses SQL DELETE-by-PK on both tables in reverse
     * journal order — Hibernate cascade is nice on write but doesn't help
     * on cleanup, so we manage it explicitly via the journal.
     */
    private ImportJournal executeNestedBundle(DataModel.Table parentTable,
                                              List<Map<String, Object>> parentRows,
                                              ColumnPolicies parentPol,
                                              FkGraph graph, Set<String> refSet,
                                              NestedInsertConfig config, FetchResult fetch,
                                              Map<String, Map<Object, Object>> idMap,
                                              ImportJournal journal, ProjectProfile profile,
                                              String targetEnv, String targetBaseUrl,
                                              StepCounter counter) throws Exception {
        String childName = config.childTable();
        DataModel.Table childTable = graph.table(childName).orElseThrow(() ->
                new HaltException("Nested-insert child table '" + childName + "' missing from dataModel."));
        ColumnPolicies childPol = ColumnPolicies.compute(childTable, graph, refSet);

        // Find the child's FK column that points to the parent.
        String childFkLower = null;
        String parentNorm = FkGraph.norm(parentTable.name());
        for (Map.Entry<String, ImportPlan.FkRemap> e : childPol.fkByColumnLower.entrySet()) {
            if (FkGraph.norm(e.getValue().targetTable()).equals(parentNorm)) {
                childFkLower = e.getKey();
                break;
            }
        }
        if (childFkLower == null) {
            throw new HaltException("Nested-insert config specifies " + parentTable.name()
                    + " → " + childName + " but no FK from " + childName
                    + " back to " + parentTable.name() + " was found in the dataModel.");
        }

        List<Map<String, Object>> allChildRows = fetch.rowsByTable().getOrDefault(childName, List.of());
        String parentPkCol = parentPol.pkColumns.get(0);
        String childPkCol = childPol.pkColumns.get(0);

        // Group children by parent's source PK. lookup() handles snake/camel case.
        Map<Object, List<Map<String, Object>>> childrenByParent = new HashMap<>();
        for (Map<String, Object> childRow : allChildRows) {
            Object fkVal = lookup(childRow, childFkLower);
            if (fkVal == null) continue;   // orphan; skip
            childrenByParent.computeIfAbsent(String.valueOf(fkVal), k -> new ArrayList<>()).add(childRow);
        }

        for (Map<String, Object> parentRow : parentRows) {
            Object parentSourcePk = lookup(parentRow, parentPkCol);
            List<Map<String, Object>> theseChildren = parentSourcePk == null
                    ? List.of()
                    : childrenByParent.getOrDefault(String.valueOf(parentSourcePk), List.of());

            AppNestedInsertService.Result res;
            try {
                res = nestedInserter.insertBundle(
                        parentTable, parentRow, parentPol,
                        childTable, theseChildren, childPol,
                        idMap, profile, targetBaseUrl, config);
            } catch (Exception e) {
                int order = counter.next();
                journal = journal.withEntry(new ImportJournal.Entry(order, parentTable.entityName(),
                        parentTable.name(), "Nested POST", parentSourcePk, null, 0, "failed",
                        "Nested insert threw: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Instant.now()));
                journalStore.write(journal);
                throw new HaltException("Nested insert failed for " + parentTable.name()
                        + ": " + e.getMessage());
            }

            int parentOrder = counter.next();
            String parentMsg = res.ok()
                    ? "OK (nested — " + theseChildren.size() + " " + childTable.name() + " child(ren))"
                    : "HTTP " + res.status() + " — " + trunc(res.body()) + " || URL: " + res.url();
            journal = journal.withEntry(new ImportJournal.Entry(parentOrder, parentTable.entityName(),
                    parentTable.name(), "Nested POST", parentSourcePk,
                    res.ok() ? res.assignedParentId() : null,
                    res.status(), res.ok() ? "created" : "failed",
                    parentMsg, Instant.now()));

            List<Object> childIds = res.assignedChildIds();
            for (int i = 0; i < theseChildren.size(); i++) {
                int childOrder = counter.next();
                Object childSourcePk = lookup(theseChildren.get(i), childPkCol);
                Object childNewId = res.ok() && childIds != null && i < childIds.size()
                        ? childIds.get(i) : null;
                journal = journal.withEntry(new ImportJournal.Entry(childOrder, childTable.entityName(),
                        childTable.name(), "Nested POST", childSourcePk, childNewId,
                        res.status(), res.ok() ? "created" : "failed",
                        res.ok() ? "OK (nested under " + parentTable.name() + ")"
                                : "Failed alongside parent",
                        Instant.now()));
            }
            journalStore.write(journal);

            if (!res.ok()) {
                throw new HaltException("Nested insert failed HTTP " + res.status()
                        + " for " + parentTable.name() + " (sourcePk=" + parentSourcePk + ") — "
                        + trunc(res.body()) + " || URL: " + res.url());
            }
        }
        return journal;
    }

    /**
     * Insert every row of {@code table} via FAWB's CSV import endpoint.
     * One HTTP call per table. All rows go into one CSV file; the server
     * processes them transactionally. Journal records success (or failure)
     * for every row; on any error we halt and the caller triggers rollback.
     */
    private ImportJournal executeTableViaCsvImport(DataModel.Table table, List<Map<String, Object>> rows,
                                                    AppSqlPlanService.ColumnPolicies pol,
                                                    Map<String, Map<Object, Object>> idMap,
                                                    ImportJournal journal, ProjectProfile profile,
                                                    String targetEnv, String targetBaseUrl,
                                                    String dbService, StepCounter counter) throws Exception {
        String tableName = table.name();
        String tableKey = FkGraph.norm(tableName);
        String primaryPk = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);

        // FAWB's /import endpoint occasionally cannot see rows that were just
        // committed via another endpoint (e.g. a parent CSV import that this
        // one FK-depends on). Same DB, same service, but the connection this
        // /import lands on is on a stale snapshot. Executing an `executeSQLs`
        // SELECT COUNT sees the row; the /import doesn't. Empirically resolves
        // itself in a second or two. Retry once with delay on FK-constraint
        // failures; anything else fails fast.
        AppCsvImportService.Result res = null;
        String[] retryDelayLabels = {"initial", "retry after 1500ms", "retry after 4000ms"};
        long[] retryDelaysMs = {0, 1500, 4000};
        String lastFkParent = null;
        for (int attempt = 0; attempt < retryDelaysMs.length; attempt++) {
            if (retryDelaysMs[attempt] > 0) {
                log.warn("CSV import for {} hit FK error (parent={}) — sleeping {}ms then retrying ({})",
                        tableName, lastFkParent, retryDelaysMs[attempt], retryDelayLabels[attempt]);
                Thread.sleep(retryDelaysMs[attempt]);
            }
            try {
                res = csvImporter.importRows(table, rows, pol, idMap, profile, targetBaseUrl, dbService);
            } catch (Exception e) {
                int order = counter.next();
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "CSV import", null, null, 0, "failed",
                        "CSV import error: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        Instant.now()));
                journalStore.write(journal);
                throw new HaltException("CSV import failed for " + tableName + ": " + e.getMessage());
            }
            if (res.ok()) break;
            lastFkParent = extractFkParentTable(res.body());
            if (lastFkParent == null) break;   // Not an FK error — no point retrying
        }

        // Journal any rows that DID land in target (assignedPks size may be
        // less than rows.size() when the CSV import was chunked and a middle
        // chunk failed — earlier chunks are still in target and need to be
        // rolled back later). Do this BEFORE checking res.ok() so rollback
        // sees them.
        List<Object> assignedPks = res.assignedPks();
        int landedCount = assignedPks == null ? 0 : assignedPks.size();
        List<Object> pksToVerify = new ArrayList<>();
        for (int i = 0; i < landedCount; i++) {
            int order = counter.next();
            Object sourcePk = primaryPk != null ? lookupRowValue(rows.get(i), primaryPk) : null;
            Object newPk = assignedPks.get(i);
            if (sourcePk != null && newPk != null && primaryPk != null) {
                idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>()).put(sourcePk, newPk);
                pksToVerify.add(newPk);
            }
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "CSV import", sourcePk, newPk, res.status(), "created",
                    "OK (via CSV" + (landedCount < rows.size() ? " chunk " + (i + 1) + "/" + rows.size() : "") + ")",
                    Instant.now()));
        }
        journalStore.write(journal);

        if (!res.ok()) {
            int order = counter.next();
            String dumpNote = res.dumpPath() != null ? " || CSV dumped to: " + res.dumpPath() : "";
            String urlNote = res.url() != null ? " || URL: " + res.url() : "";
            String chunkNote = landedCount > 0
                    ? " || " + landedCount + " row(s) landed in earlier chunk(s) — rollback will delete them"
                    : "";
            String retryNote = lastFkParent != null
                    ? " || Retried " + (retryDelaysMs.length - 1) + "x on FK error against parent "
                        + lastFkParent + "; still failing."
                    : "";
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "CSV import", null, null, res.status(), "failed",
                    "HTTP " + res.status() + " on CSV import — " + trunc(res.body())
                            + urlNote + retryNote + chunkNote + dumpNote,
                    Instant.now()));
            journalStore.write(journal);
            throw new HaltException("CSV import failed HTTP " + res.status() + " for " + tableName
                    + " — " + trunc(res.body()) + urlNote + retryNote + chunkNote + dumpNote);
        }

        // Post-import verification: confirm the PKs we THINK we assigned
        // actually exist in target. FAWB's CSV importer may (or may not) honour
        // client-provided id columns — if it generates its own UUIDs, our
        // id-map is stale and child FKs will fail with "row not found" even
        // though the parent was inserted. Doing SELECT COUNT via executeSQLs
        // (which we know reads real committed state) catches this.
        if (primaryPk != null && !pksToVerify.isEmpty()) {
            verifyCsvChunkLanded(table, primaryPk, pksToVerify, profile, targetEnv);
        }
        return journal;
    }

    /**
     * SELECT COUNT via executeSQLs to confirm the CSV import actually stored
     * the PKs we tracked in id-map. If count < expected, FAWB has either
     * replaced our ids with server-assigned ones, or the write didn't commit
     * where a subsequent read can see it. Either way — halt with a clear
     * message; downstream FK checks would fail anyway with a less obvious
     * error.
     */
    private void verifyCsvChunkLanded(DataModel.Table table, String primaryPk,
                                       List<Object> newPks, ProjectProfile profile, String targetEnv) throws Exception {
        StringBuilder inList = new StringBuilder();
        boolean first = true;
        for (Object pk : newPks) {
            if (!first) inList.append(", ");
            inList.append(SqlBuilder.literal(pk));
            first = false;
        }
        String sql = "SELECT COUNT(*) AS n FROM " + SqlBuilder.ident(table.name())
                + " WHERE " + SqlBuilder.ident(primaryPk) + " IN (" + inList + ")";
        ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
        if (res.status() < 200 || res.status() >= 300) {
            log.warn("Post-CSV verification SELECT failed HTTP {} for {} — skipping verification.",
                    res.status(), table.name());
            return;
        }
        try {
            JsonNode arr = mapper.readTree(res.body());
            if (arr.isArray() && arr.size() > 0) {
                JsonNode env = arr.get(0);
                JsonNode resp = env.get("response");
                if (resp != null && resp.isTextual()) {
                    JsonNode parsed = mapper.readTree(resp.asText());
                    int count = parsed.path("n").asInt(-1);
                    if (count >= 0 && count < newPks.size()) {
                        throw new HaltException(
                                "Post-CSV verification FAILED for " + table.name()
                                        + ": expected " + newPks.size() + " row(s) with our PKs, "
                                        + "but SELECT COUNT found only " + count + ". "
                                        + "FAWB's /import endpoint likely IGNORED the id column and "
                                        + "generated its own PKs — our id-map is now stale, so child "
                                        + "FKs will fail. Fix: for this table, use a path that returns "
                                        + "the assigned PK (SQL INSERT + LAST_INSERT_ID, or REST create "
                                        + "which echoes the created entity).");
                    }
                }
            }
        } catch (HaltException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Post-CSV verification parse error for {}: {}", table.name(), e.getMessage());
        }
    }

    /** True if any column on this table is a large-text type. Table-level routing decision. */
    private static boolean tableHasLargeTextColumn(DataModel.Table table) {
        if (table == null || table.columns() == null) return false;
        for (DataModel.Column c : table.columns()) {
            if (isLargeTextColumn(c)) return true;
        }
        return false;
    }

    /**
     * Built-in last-resort fallback if BOTH auto-detection fails AND the
     * profile has no override. Covers the CaseManager schema this tool was
     * initially developed against — kept only so a first-run against
     * CaseManager works even if information_schema is blocked.
     */
    private static final Set<String> DEFAULT_CSV_ONLY_TABLES = Set.of("CASE_DATUM");

    /**
     * Priority order:
     * <ol>
     *   <li>Auto-detected from {@code information_schema.TABLE_CONSTRAINTS} (schema-driven, no user config)</li>
     *   <li>{@code profile.csvOnlyTables} if set (manual override)</li>
     *   <li>Built-in default (CaseManager compatibility)</li>
     * </ol>
     */
    private Set<String> resolveCsvOnlyTables(ProjectProfile profile, String targetEnv, FkGraph graph) {
        Set<String> detected = schemaMetadata.detectCsvOnlyTables(profile, targetEnv, graph);
        if (detected != null) return detected;   // authoritative — even if empty
        // Auto-detection unavailable (e.g. information_schema blocked). Use
        // profile override if set — including empty list, which is a valid
        // "no tables need CSV" answer from the user. Only fall to the
        // built-in CaseManager default if the profile also has nothing.
        if (profile != null && profile.csvOnlyTables() != null) {
            Set<String> fromProfile = new HashSet<>();
            for (String s : profile.csvOnlyTables()) {
                if (s != null && !s.isBlank()) fromProfile.add(s.trim().toUpperCase(Locale.ROOT));
            }
            return fromProfile;
        }
        return DEFAULT_CSV_ONLY_TABLES;
    }

    /**
     * True if any table in the fetched app tree has a walked (in-fetch) child
     * relationship where the child uses identity/sequence PK strategy.
     * Used to decide whether an assigned-PK parent must go through REST too,
     * so FAWB's Hibernate session has it cached before the child's REST
     * insert runs. Without this, we get TransientObjectException on the
     * child insert (JPA can't see the parent that was inserted via raw SQL).
     */
    private static boolean hasIdentityChildInFetch(String parentTableName, FkGraph graph,
                                                    FetchResult fetch) {
        for (FkGraph.Edge edge : graph.children(parentTableName)) {
            String childName = FkGraph.norm(edge.childTable());
            if (childName.equals(FkGraph.norm(parentTableName))) continue;   // self-ref
            if (!fetch.rowsByTable().containsKey(childName)) continue;   // not walked
            DataModel.Table child = graph.table(childName).orElse(null);
            if (child == null || child.primaryKey() == null || child.primaryKey().generator() == null) continue;
            String childStrategy = child.primaryKey().generator().generatorType();
            if ("identity".equalsIgnoreCase(childStrategy) || "sequence".equalsIgnoreCase(childStrategy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tableNeedsCsvImportPath(DataModel.Table table, Set<String> csvOnlyTables) {
        if (table == null || table.name() == null || csvOnlyTables == null) return false;
        return csvOnlyTables.contains(table.name().toUpperCase(Locale.ROOT));
    }

    /**
     * Insert every row of {@code table} via the REST CRUD endpoint
     * ({@code POST /services/{db}/{Entity}}). One HTTP call per row.
     * FK remap + assigned-UUID regen use the same policies as SQL path.
     * On failure, halts — caller triggers rollback which DELETEs any
     * successfully-inserted rows (SQL DELETE works fine for REST-inserted rows).
     */
    private ImportJournal executeTableViaRest(DataModel.Table table, List<Map<String, Object>> rows,
                                              AppSqlPlanService.ColumnPolicies pol,
                                              Map<String, Map<Object, Object>> idMap,
                                              ImportJournal journal, ProjectProfile profile,
                                              String targetEnv, String targetBaseUrl,
                                              String dbService, StepCounter counter,
                                              Set<String> skippedTables) throws Exception {
        String tableName = table.name();
        String tableKey = FkGraph.norm(tableName);
        String primaryPk = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);

        // Cascade-skip: if this table's FKs point to a previously-skipped
        // parent table, our tool can't resolve the reference (parent's
        // id-map is empty). Rather than halt with "cannot remap FK", skip
        // the whole table cleanly and let the demo/import proceed on
        // unrelated branches.
        for (ImportPlan.FkRemap remap : pol.fkByColumnLower.values()) {
            if (skippedTables.contains(FkGraph.norm(remap.targetTable()))) {
                log.warn("Cascade-skipping {}: it FKs to '{}' which was skipped earlier. "
                        + "None of this table's rows can be inserted meaningfully.",
                        tableName, remap.targetTable());
                for (Map<String, Object> row : rows) {
                    int order = counter.next();
                    Object srcPk = primaryPk != null ? lookupRowValue(row, primaryPk) : null;
                    journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                            "REST POST", srcPk, null, 0, "skipped",
                            "Cascade-skipped: this table's FK to '" + remap.targetTable()
                                    + "' can't be remapped because '" + remap.targetTable()
                                    + "' was itself skipped earlier (FAWB refused writes). No rows inserted; continuing.",
                            Instant.now()));
                }
                journalStore.write(journal);
                skippedTables.add(tableKey);
                return journal;
            }
        }

        // ---- PERF: parallel-prefetch REST POSTs ----
        // FAWB's per-entity CRUD endpoint has no batch support, so we can't
        // combine INSERTs into a single call. But independent rows within
        // one table can be POSTed concurrently — cuts wall-time by the
        // concurrency factor. We keep the existing per-row processing loop
        // sequential (safest for id-map updates + journal ordering + all the
        // error-recovery branching) and just pre-fetch HTTP results into
        // futures. When the loop below needs a row's result, it either
        // grabs the pre-fetched future (parallel path) or calls
        // restInserter.insert() directly (serial fallback).
        //
        // Serial fallback triggers when:
        //   - table has FKs back to itself (later rows may need earlier rows'
        //     new PKs in idMap — parallel prefetch can't guarantee ordering)
        //   - only 0 or 1 row to insert (no gain from parallelism)
        boolean selfReferencing = false;
        for (ImportPlan.FkRemap remap : pol.fkByColumnLower.values()) {
            if (FkGraph.norm(remap.targetTable()).equals(tableKey)) {
                selfReferencing = true; break;
            }
        }

        // Pre-compute deterministic per-row values BEFORE submitting workers
        // so workers can call trySqlFallbackInsert with the right order value
        // (matching what the sequential collector will use). counter.next()
        // must run sequentially to preserve numbering across tables.
        final java.util.List<Integer> orderList = new java.util.ArrayList<>(rows.size());
        final java.util.List<Object> sourcePkList = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            orderList.add(counter.next());
            sourcePkList.add(primaryPk != null ? lookupRowValue(row, primaryPk) : null);
        }

        java.util.concurrent.ExecutorService restPool = null;
        java.util.List<java.util.concurrent.Future<RestAttempt>> restFutures = null;
        if (!selfReferencing && rows.size() > 1) {
            // Pre-create this table's id-map slot so workers reading idMap
            // (for FK remaps of parent tables) don't race with the sequential
            // collector adding entries to this same table's inner map.
            idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>());
            int nThreads = Math.min(6, rows.size());
            // Daemon threads: if the executor loop throws before we can call
            // shutdownNow(), the JVM still exits cleanly.
            final java.util.concurrent.atomic.AtomicInteger tid = new java.util.concurrent.atomic.AtomicInteger();
            restPool = java.util.concurrent.Executors.newFixedThreadPool(nThreads, r -> {
                Thread t = new Thread(r, "devbridge-import-rest-" + tid.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
            restFutures = new java.util.ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                final Map<String, Object> r = rows.get(i);
                final int rowOrder = orderList.get(i);
                final Object rowSrcPk = sourcePkList.get(i);
                restFutures.add(restPool.submit(() -> {
                    // Try REST first (fast — the network is the only bottleneck)
                    AppRestInsertService.InsertResult restRes = null;
                    Exception restErr = null;
                    try {
                        restRes = restInserter.insert(r, table, pol, idMap,
                                profile, targetBaseUrl, dbService);
                    } catch (Exception ex) {
                        restErr = ex;
                    }
                    // If REST didn't succeed, eagerly run SQL fallback IN THIS
                    // WORKER THREAD. Critical for tables where every row falls
                    // through (AUDITDATACHANGE with methodAccessDenied,
                    // ACTIVITY_PAYLOAD with content-type / oversized): otherwise
                    // the sequential collector would fire N slow SQL fallbacks
                    // one after the other, wiping out the parallel-REST gains.
                    SqlFallbackResult sqlRes = null;
                    if (restErr != null || (restRes != null && !restRes.ok())) {
                        try {
                            sqlRes = trySqlFallbackInsert(r, table, pol, idMap,
                                    profile, targetEnv, null, rowOrder, rowSrcPk, primaryPk);
                        } catch (Exception ignore) {
                            // Fallback threw — collector will observe null sqlRes
                            // and either try again in the serial path or report failure
                        }
                    }
                    return new RestAttempt(restRes, restErr, sqlRes);
                }));
            }
            restPool.shutdown();
            log.info("REST+SQL parallel prefetch: {} rows across {} thread(s) for {}",
                    rows.size(), nThreads, tableName);
        }
        try {
        for (int rowIdx = 0; rowIdx < rows.size(); rowIdx++) {
            Map<String, Object> row = rows.get(rowIdx);
            int order = orderList.get(rowIdx);
            Object sourcePk = sourcePkList.get(rowIdx);
            // Pre-fetched attempt from the worker (if parallel path was taken).
            // Includes an eagerly-computed SQL fallback result when REST failed.
            RestAttempt currentAttempt = (restFutures != null) ? restFutures.get(rowIdx).get() : null;
            AppRestInsertService.InsertResult res;
            try {
                if (currentAttempt != null) {
                    if (currentAttempt.exception != null) throw currentAttempt.exception;
                    res = currentAttempt.result;
                } else {
                    res = restInserter.insert(row, table, pol, idMap, profile, targetBaseUrl, dbService);
                }
            } catch (Exception e) {
                // Transport-level error (network, chunked-encoding parse, timeout,
                // etc.) — no response body available. Attempt SQL fallback: the
                // raw executeSQLs endpoint uses a different transport shape and
                // often succeeds where REST's chunked response reader chokes.
                // Common trigger: ACTIVITY_PAYLOAD with a huge BLOB where FAWB's
                // response streaming breaks midway.
                String errClass = e.getClass().getSimpleName();
                String errMsg = e.getMessage() == null ? "(no detail)" : e.getMessage();
                log.warn("REST insert transport error on {} srcPk={}: {} — attempting SQL fallback.",
                        tableName, sourcePk, errClass + ": " + errMsg);
                // Reuse the worker's pre-computed SQL fallback result if it
                // already ran (parallel path). Otherwise (serial fallback
                // path, e.g., self-referencing tables), do it inline.
                SqlFallbackResult sqlResult = (currentAttempt != null && currentAttempt.sqlResult != null)
                        ? currentAttempt.sqlResult
                        : trySqlFallbackInsert(row, table, pol, idMap, profile, targetEnv,
                                counter, order, sourcePk, primaryPk);
                if (sqlResult != null && sqlResult.succeeded) {
                    log.warn("REST transport error on {} recovered via SQL fallback.", tableName);
                    if (sqlResult.newPk != null && sourcePk != null) {
                        idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>())
                                .put(sourcePk, sqlResult.newPk);
                    }
                    journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                            "SQL fallback", sourcePk, sqlResult.newPk, 200, "created",
                            "Inserted via SQL fallback (REST transport error — oversized payload or chunked response). Row is in target.",
                            Instant.now()));
                    journalStore.write(journal);
                    continue;
                }
                // Both failed — skip this row (or halt for a leaf/critical
                // table? For now: skip + cascade so we don't halt the whole
                // run on one transport hiccup or an oversized-payload row).
                String fallbackDetail = sqlResult == null
                        ? "not attempted (FK unresolvable)"
                        : sqlResult.errorDetail;
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "REST POST", sourcePk, null, 0, "skipped",
                        "REST insert transport error: " + errClass + ": " + errMsg
                                + ". SQL fallback also failed: " + trunc(fallbackDetail)
                                + ". Row skipped; if downstream tables FK to this row they will cascade-skip.",
                        Instant.now()));
                journalStore.write(journal);
                skippedTables.add(tableKey);
                continue;
            }
            if (!res.ok()) {
                // Auto-skip on FAWB "methodAccessDenied" — the REST endpoint
                // refuses the write entirely. This is typical for audit-log
                // tables (AuditDataChange etc.) where the create method is
                // reserved for internal FAWB processes. Since these tables
                // are leaves (nothing FKs to them) and their content is
                // meaningless across envs, we log a WARN, journal the row as
                // "skipped", and continue. If a downstream table happens to
                // FK to a skipped row, that later insert will fail with a
                // specific FK error — we halt cleanly then.
                String bodyLower = res.body() == null ? "" : res.body().toLowerCase(Locale.ROOT);
                // FAWB has several error patterns that mean "this endpoint
                // won't accept this write" — permissions, unsupported content
                // type, unexpected payload shape, or entity-level constraints
                // that our tool can't satisfy from outside. All indicate the
                // target table is restricted or the create-method rejects
                // external inputs. Auto-skip for the demo path; user can
                // manually seed / fix if needed.
                String skipReason = null;
                if (bodyLower.contains("methodaccessdenied")
                        || bodyLower.contains("access denied for this method")) {
                    skipReason = "methodAccessDenied (create method restricted)";
                } else if (bodyLower.contains("content-type") && bodyLower.contains("is not supported")) {
                    skipReason = "Content-Type not supported (payload too large for JSON, or endpoint doesn't accept JSON)";
                } else if (bodyLower.contains("no endpoint post")) {
                    // Backstop: FAWB reports "No endpoint POST /services/…/EntityName"
                    // when the create route isn't registered at all.
                    skipReason = "No POST endpoint registered on FAWB for this entity";
                } else if (bodyLower.contains("ids for this class must be manually assigned")) {
                    // Assigned-PK entities whose @Id field is @JsonIgnore or similarly
                    // read-only in FAWB's REST layer. We can't POST them because our
                    // JSON id is silently discarded, and Hibernate then can't assign
                    // one. Seen on PersonFinancial (shared PK+FK to PERSON via OneToOne).
                    skipReason = "Assigned-PK entity rejects client-supplied id via JSON (FAWB REST layer discards it); "
                            + "either PUT-by-id endpoint or manual seed required";
                } else if (bodyLower.contains("transientobjectexception")
                        || bodyLower.contains("unsaved transient instance")) {
                    // FAWB's REST layer creates a transient entity from the {id: X}
                    // relation object in our JSON instead of loading the referenced
                    // row from DB, and Hibernate refuses to flush an entity that
                    // references a transient parent. This happens even when the
                    // parent row genuinely exists in target — FAWB's per-request
                    // Hibernate session doesn't lazily fetch from DB for these
                    // reference resolutions. We can't fix this from outside.
                    // Extract the specific transient class name from the error
                    // for the message so users know exactly which relation failed.
                    String transientCls = "";
                    int idx = bodyLower.indexOf("transient instance");
                    if (idx > 0 && idx + 40 < bodyLower.length()) {
                        String snippet = res.body().substring(idx + "transient instance".length(),
                                Math.min(res.body().length(), idx + 200));
                        transientCls = snippet.replaceAll(".*?([a-zA-Z0-9_.]+\\.[A-Z][a-zA-Z0-9_]+).*", " (referenced entity: $1)").split("[\\r\\n\"]")[0].trim();
                        if (transientCls.length() > 100) transientCls = "";
                    }
                    skipReason = "Hibernate transient-instance rejection" + transientCls
                            + " — FAWB's per-request session treats the referenced entity as unsaved even when it exists in DB; "
                            + "this table's rows can't be inserted via REST from an external caller";
                }
                if (skipReason != null) {
                    // Try raw-SQL fallback FIRST. REST goes through FAWB's
                    // Hibernate/JPA layer which sometimes refuses external
                    // writes (methodAccessDenied, TransientObjectException,
                    // shared PK+FK entities where @Id is @JsonIgnore, etc.).
                    // executeSQLs bypasses all of that — DB-level INSERT
                    // subject only to actual DB constraints. Works for
                    // assigned-PK tables where we know the target PK.
                    // Reuse the worker's pre-computed SQL fallback result if
                    // the parallel path already ran it — huge time saver for
                    // tables where every row goes through this branch
                    // (AUDITDATACHANGE with methodAccessDenied, etc.).
                    SqlFallbackResult sqlResult = (currentAttempt != null && currentAttempt.sqlResult != null)
                            ? currentAttempt.sqlResult
                            : trySqlFallbackInsert(row, table, pol, idMap, profile, targetEnv,
                                    counter, order, sourcePk, primaryPk);
                    if (sqlResult != null && sqlResult.succeeded) {
                        log.warn("REST refused {} ({}), but SQL fallback succeeded — row inserted via raw executeSQLs. Downstream FKs to this row will work normally.",
                                tableName, skipReason);
                        if (sqlResult.newPk != null && sourcePk != null) {
                            idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>())
                                    .put(sourcePk, sqlResult.newPk);
                        }
                        journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                                "SQL fallback", sourcePk, sqlResult.newPk, 200, "created",
                                "Inserted via SQL fallback (" + shortRestRefusalReason(skipReason) + "). Row is in target.",
                                Instant.now()));
                        journalStore.write(journal);
                        continue;
                    }
                    // SQL fallback also failed (or wasn't attempted, e.g. FK
                    // unresolvable). Fall back to skip + cascade-skip children.
                    String sqlFallbackNote = sqlResult == null
                            ? " SQL fallback not attempted (FK to a skipped parent or missing PK)."
                            : " SQL fallback also failed: " + trunc(sqlResult.errorDetail);
                    log.warn("Skipping row {}={} on {}: FAWB refused write ({}).{} Downstream FKs to this row will cascade-skip.",
                            primaryPk, sourcePk, tableName, skipReason, sqlFallbackNote);
                    journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                            "REST POST", sourcePk, null, res.status(), "skipped",
                            "FAWB refused write to " + tableName + " — " + skipReason
                                    + "." + sqlFallbackNote
                                    + " Row not inserted; continuing. Downstream children FKing to this row will be cascade-skipped.",
                            Instant.now()));
                    journalStore.write(journal);
                    skippedTables.add(tableKey);
                    continue;
                }
                // Dump the FULL outgoing payload to disk so we can inspect it and
                // replay it manually via Postman. Message shows the file path.
                String dumpPath = dumpFailedPayload(res.outgoingPayload(), profile.id(),
                        tableName, order);
                String payloadPreview = res.outgoingPayload() == null ? "(no payload captured)"
                        : (res.outgoingPayload().length() > 800
                                ? res.outgoingPayload().substring(0, 800) + "…(+" + (res.outgoingPayload().length() - 800) + " chars)"
                                : res.outgoingPayload());
                String msg = "HTTP " + res.status() + " — " + trunc(res.body())
                        + " || Full payload dumped to: " + dumpPath
                        + " || Preview: " + payloadPreview;
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "REST POST", sourcePk, null, res.status(), "failed",
                        msg, Instant.now()));
                journalStore.write(journal);
                throw new HaltException("REST INSERT failed HTTP " + res.status()
                        + " for " + tableName + " — " + trunc(res.body())
                        + " || Full payload dumped to: " + dumpPath, journal);
            }
            // Success — extract new PK (server echoes it back) and update id-map
            Object newPk = res.assignedPk();
            if (newPk == null) {
                // Fallback: try to parse the response body for an "id" field
                try {
                    JsonNode body = res.body() == null || res.body().isBlank()
                            ? null : mapper.readTree(res.body());
                    if (body != null && body.has("id") && !body.get("id").isNull()) {
                        JsonNode idNode = body.get("id");
                        newPk = idNode.isNumber() ? idNode.numberValue() : idNode.asText();
                    }
                } catch (Exception ignore) { /* leave null */ }
            }
            if (newPk == null) {
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "REST POST", sourcePk, null, res.status(), "failed",
                        "REST POST succeeded but no PK returned in response body",
                        Instant.now()));
                journalStore.write(journal);
                throw new HaltException("REST INSERT for " + tableName
                        + " returned no PK — cannot populate id-map for children.");
            }
            if (sourcePk != null) {
                idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>()).put(sourcePk, newPk);
            }
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "REST POST", sourcePk, newPk, res.status(), "created", "OK (REST)",
                    Instant.now()));
            journalStore.write(journal);
        }
        } finally {
            // Cleanup runs on every exit path (normal completion, HaltException,
            // or any other throw). shutdownNow() interrupts any workers still
            // running so we don't leak threads.
            if (restPool != null) restPool.shutdownNow();
        }
        return journal;
    }

    /**
     * Captured outcome of one background REST + SQL-fallback attempt. If REST
     * succeeded, {@code result.ok()} is true and {@code sqlResult} is null.
     * If REST failed (exception or non-2xx), the worker also ran
     * {@link #trySqlFallbackInsert} in parallel and stashed the outcome in
     * {@code sqlResult} so the sequential collector can reuse it.
     */
    private record RestAttempt(
            AppRestInsertService.InsertResult result,
            Exception exception,
            SqlFallbackResult sqlResult
    ) {}

    /**
     * Write the exact outgoing payload to disk so we can replay it manually via
     * Postman when a REST POST fails. Path is
     * {@code ~/.devbridge/imports/<profileId>/failed-<table>-<order>-<jobId>.json}.
     * Returns the absolute path (or "(dump-failed)" on IO error — never throws).
     */
    private String dumpFailedPayload(String payload, String profileId, String tableName, int order) {
        if (payload == null) return "(no payload)";
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path dir = java.nio.file.Paths.get(home, ".devbridge", "imports",
                    profileId != null ? profileId : "unknown", "failed-payloads");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("failed-" + tableName + "-" + order + "-"
                    + System.currentTimeMillis() + ".json");
            java.nio.file.Files.writeString(file, payload);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Failed to dump payload to disk: {}", e.getMessage());
            return "(dump-failed: " + e.getMessage() + ")";
        }
    }

    /** Row-value lookup with case-insensitive fallback for column name. Duplicated from parent helper for private-scope reuse. */
    private static Object lookupRowValue(Map<String, Object> row, String col) {
        return lookup(row, col);
    }

    /**
     * Base64-shield every large-text-column value in {@code setPairs} for use
     * in a single-INSERT emission. Non-large-text values pass through
     * unchanged (any URL-fragile shielding on them has already been applied
     * by {@link SqlBuilder#convertForInsert}).
     */
    private static Map<String, Object> shieldLargeTextForInline(Map<String, Object> setPairs,
                                                                DataModel.Table table) {
        Map<String, DataModel.Column> byName = new HashMap<>();
        for (DataModel.Column c : safe(table.columns())) {
            if (c.name() != null) byName.put(c.name().toLowerCase(Locale.ROOT), c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : setPairs.entrySet()) {
            DataModel.Column col = byName.get(e.getKey().toLowerCase(Locale.ROOT));
            Object v = e.getValue();
            if (isLargeTextColumn(col) && v instanceof String s && !s.isEmpty()) {
                out.put(e.getKey(), SqlBuilder.forceBase64Shield(s));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** Split a string into raw-char chunks of at most {@code maxChars} each. */
    private static List<String> chunkString(String s, int maxChars) {
        if (s == null || s.isEmpty()) return List.of();
        if (s.length() <= maxChars) return List.of(s);
        List<String> out = new ArrayList<>((s.length() / maxChars) + 1);
        for (int i = 0; i < s.length(); i += maxChars) {
            out.add(s.substring(i, Math.min(i + maxChars, s.length())));
        }
        return out;
    }

    /**
     * Inspect a response body. If HTTP is non-2xx or ANY envelope's response text
     * is not "success", return a human-readable error. Otherwise null (all good).
     * Batched pre-cleans and INSERTs land as separate envelopes; this catches
     * either failing.
     */
    private String firstStatementError(ExecuteResult res) throws Exception {
        int status = res.status();
        if (status < 200 || status >= 300) {
            return "HTTP " + status + " — " + extractDbError(res.body());
        }
        String body = res.body();
        if (body == null || body.isBlank()) return null;
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray()) return null;
        for (JsonNode env : arr) {
            if (env == null || !env.isObject()) continue;
            JsonNode resp = env.get("response");
            if (resp != null && resp.isTextual()) {
                String s = resp.asText();
                if (!"success".equalsIgnoreCase(s)) return extractDbError(s);
            }
        }
        return null;
    }

    /**
     * URL-encoded byte length of a string, using the same encoding as
     * {@code SqlService}: {@link URLEncoder#encode} plus {@code +} → {@code %20}
     * (Java's URLEncoder is form-data style; strict query-string uses {@code %20}).
     * Without this the actual on-wire length is undercounted by 2 bytes per
     * space, and batches over-fill.
     */
    private static int urlEncodedLength(String s) {
        if (s == null || s.isEmpty()) return 0;
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").length();
    }

    /* ---------- identity PK: one-INSERT-per-call + LAST_INSERT_ID ---------- */

    private ImportJournal executeIdentityTable(DataModel.Table table, List<Map<String, Object>> rows,
                                                ColumnPolicies pol,
                                                Map<String, Map<Object, Object>> idMap,
                                                ImportJournal journal, ProjectProfile profile,
                                                String targetEnv, String targetBaseUrl,
                                                StepCounter counter) throws Exception {
        String tableName = table.name();
        String tableKey = FkGraph.norm(tableName);
        String primaryPk = pol.pkColumns.isEmpty() ? null : pol.pkColumns.get(0);

        for (Map<String, Object> row : rows) {
            Object sourcePk = primaryPk != null ? lookup(row, primaryPk) : null;
            Map<String, Object> setPairs = new LinkedHashMap<>();
            for (DataModel.Column col : safe(table.columns())) {
                String cname = col.name();
                if (cname == null) continue;
                String lower = cname.toLowerCase(Locale.ROOT);
                if (pol.strippedLower.contains(lower)) continue;
                Object v = lookup(row, cname);
                ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
                if (remap != null && v != null) {
                    Object mapped = lookupIdMap(idMap, remap.targetTable(), v);
                    if (mapped == null && remap.useSentinel()) {
                        mapped = ReferenceRemapService.lookupSentinel(idMap, remap.targetTable());
                    }
                    if (mapped == null) {
                        if (!remap.isReferenceTarget()) {
                            throw new HaltException(
                                    "Cannot remap FK " + tableName + "." + cname + " = " + v
                                    + " → " + remap.targetTable() + ".\n"
                                    + "  What this means: this row references " + remap.targetTable()
                                    + "'s row with source-side id=" + v + ", but the id-map has no entry for it.\n"
                                    + "  Why: the walker didn't fetch/insert " + remap.targetTable()
                                    + " OR the walker fetched it but its insert previously failed silently.\n"
                                    + "  Likely fix: (a) if " + remap.targetTable() + " is app-scoped, ensure it's NOT listed in profile.referenceTables so the walker fetches it; "
                                    + "(b) if it's shared-lookup data, add it to profile.referenceTableConfigs with a natural key; "
                                    + "(c) check earlier journal entries for a failure on " + remap.targetTable() + " that halted its insert.");
                        }
                        // Reference target with no natural-key remap configured
                        // — fall back to pass-through of the source value.
                        setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
                        continue;
                    }
                    setPairs.put(cname, SqlBuilder.convertForInsert(mapped, col));
                    continue;
                }
                setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
            }
            String insertSql = SqlBuilder.insertSet(tableName, setPairs);
            String batch = insertSql + "; SELECT LAST_INSERT_ID() AS newId";
            ExecuteResult res = sqlService.execute(profile, targetEnv, batch);
            int order = counter.next();
            int status = res.status();
            if (status < 200 || status >= 300) {
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "SQL", sourcePk, null, status, "failed",
                        "HTTP " + status + " — " + trunc(res.body()), Instant.now()));
                journalStore.write(journal);
                throw new HaltException("INSERT failed for " + tableName + " (sourcePk=" + sourcePk
                        + ") HTTP " + status + " — " + trunc(res.body()));
            }
            // Envelope parse: [{insert...},{lastInsertId envelope}]
            Object newPk = parseLastInsertId(res.body());
            if (newPk == null) {
                journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                        "SQL", sourcePk, null, status, "failed",
                        "INSERT succeeded but LAST_INSERT_ID() came back empty.", Instant.now()));
                journalStore.write(journal);
                throw new HaltException("LAST_INSERT_ID() empty after inserting into " + tableName);
            }
            if (primaryPk != null && sourcePk != null) {
                idMap.computeIfAbsent(tableKey, k -> new LinkedHashMap<>()).put(sourcePk, newPk);
            }
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "SQL", sourcePk, newPk, status, "created", "OK", Instant.now()));
            journalStore.write(journal);
        }
        return journal;
    }

    /* ---------- journal / response parsing ---------- */

    private ImportJournal recordChunk(List<PreparedRow> chunk, ExecuteResult res,
                                       DataModel.Table table, String tableName,
                                       ImportJournal journal, StepCounter counter,
                                       String targetBaseUrl, boolean hadPreCleanStatements,
                                       ProjectProfile profile, String targetEnv) throws Exception {
        int status = res.status();
        if (status < 200 || status >= 300) {
            int order = counter.next();
            String detail = extractDbError(res.body());
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "SQL batch", null, null, status, "failed",
                    "HTTP " + status + " on batch — " + detail, Instant.now()));
            journalStore.write(journal);
            throw new HaltException("Batch INSERT failed HTTP " + status + " for " + tableName
                    + " — " + detail);
        }
        // Parse per-statement envelopes. IMPORTANT: executeSQLs continues past a
        // failed statement, so rows AFTER the failing one may have been committed
        // to target. Process ALL envelopes so the journal reflects everything
        // that actually landed — rollback needs the complete picture, not just
        // the entries up to the first failure.
        JsonNode arr = res.body().isBlank() ? mapper.createArrayNode() : mapper.readTree(res.body());
        int step = hadPreCleanStatements ? 2 : 1;    // envelopes per row (DELETE+INSERT or just INSERT)
        String firstFailureMsg = null;
        int firstFailureOrder = -1;
        // First pass: build the per-row result. Rows that FAWB says failed
        // are candidates for the "silent commit" check below.
        String pkColForVerify = table.primaryKey() != null && table.primaryKey().columns() != null
                && !table.primaryKey().columns().isEmpty()
                ? table.primaryKey().columns().get(0) : null;
        for (int i = 0; i < chunk.size(); i++) {
            int order = counter.next();
            PreparedRow pr = chunk.get(i);
            String result = "created";
            String message = "OK";
            int rowStatus = 200;
            Object targetPk = pr.newPk;

            int insertIdx = (i * step) + (step - 1);
            if (arr.isArray() && insertIdx < arr.size()) {
                JsonNode env = arr.get(insertIdx);
                if (env != null && env.isObject()) {
                    JsonNode resp = env.get("response");
                    if (resp != null && resp.isTextual()) {
                        String s = resp.asText();
                        if (!"success".equalsIgnoreCase(s)) {
                            result = "failed";
                            message = extractDbError(s);
                            rowStatus = 500;
                            targetPk = null;
                            boolean silentCommit = false;

                            // "Silent commit" check: some FAWB errors (like
                            // "could not execute statement; SQL [n/a]" from a
                            // JPA lifecycle listener throwing AFTER the SQL
                            // committed) leave the row in the DB even though
                            // the response says failure. Verify via SELECT.
                            if (pkColForVerify != null && pr.newPk != null) {
                                Object landedPk = verifyRowLanded(profile, targetEnv,
                                        tableName, pkColForVerify, pr.newPk);
                                if (landedPk != null) {
                                    log.warn("Silent commit detected: row {}={} landed in target despite FAWB error '{}'. Continuing — downstream FKs can reference it.",
                                            pkColForVerify, pr.newPk, trunc(message));
                                    result = "created-then-error";
                                    targetPk = landedPk;
                                    message = "DB inserted the row but FAWB reported error (continued past — downstream tables reference this row's id normally). Original error: " + trunc(message);
                                    rowStatus = 200;
                                    silentCommit = true;
                                }
                            }

                            // Only halt on GENUINE failures. Silent-commit rows are
                            // effectively created — id-map already has them, downstream
                            // work can proceed. Rollback still deletes them if a later
                            // step halts, because targetPk is set on the journal entry.
                            if (!silentCommit && firstFailureMsg == null) {
                                firstFailureMsg = message;
                                firstFailureOrder = order;
                            }
                        }
                    }
                }
            }
            journal = journal.withEntry(new ImportJournal.Entry(order, table.entityName(), tableName,
                    "SQL batch", pr.sourcePk, targetPk, rowStatus, result, message, Instant.now()));
        }
        journalStore.write(journal);
        if (firstFailureMsg != null) {
            // Diagnostic hook: dump the full response body to disk so the user
            // can inspect what FAWB returned when the parser reports
            // "(no DB detail returned)".
            dumpFailedResponseBody(res.body(), journal.profileId(), tableName, firstFailureOrder);
            // Attach the journal snapshot so execute()'s catch has the latest
            // entries (including any silent-commit "created-then-error" we just
            // added). Without this, rollback would use the caller's stale journal.
            throw new HaltException(
                    "Row " + firstFailureOrder + " (" + tableName + ") failed: " + firstFailureMsg,
                    journal);
        }
        return journal;
    }

    private Object parseLastInsertId(String body) throws Exception {
        if (body == null || body.isBlank()) return null;
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray()) return null;
        for (int i = arr.size() - 1; i >= 0; i--) {
            JsonNode env = arr.get(i);
            if (env == null || !env.isObject()) continue;
            JsonNode resp = env.get("response");
            if (resp == null || !resp.isTextual()) continue;
            String s = resp.asText();
            if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;
            try {
                JsonNode parsed = mapper.readTree(s);
                if (parsed.has("newId") && !parsed.get("newId").isNull()) {
                    JsonNode v = parsed.get("newId");
                    return v.isNumber() ? v.numberValue() : v.asText();
                }
                // Fallback: any numeric field
                var fields = parsed.fields();
                if (fields.hasNext()) {
                    var e = fields.next();
                    if (!e.getValue().isNull()) {
                        return e.getValue().isNumber() ? e.getValue().numberValue() : e.getValue().asText();
                    }
                }
            } catch (Exception ignore) {
                // response wasn't a JSON object — skip
            }
        }
        return null;
    }

    /* ---------- helpers ---------- */

    private static Object lookup(Map<String, Object> row, String col) {
        if (row == null || col == null) return null;
        if (row.containsKey(col)) return row.get(col);
        String lower = col.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        // snake_case → camelCase fallback (executeSQLs sometimes echoes Hibernate field names, not DB columns)
        if (col.indexOf('_') >= 0) {
            StringBuilder sb = new StringBuilder(col.length());
            boolean up = false;
            for (int i = 0; i < col.length(); i++) {
                char c = col.charAt(i);
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
        // String-coercion match: JSON numbers may round-trip as Long/Integer, source as Double, etc.
        for (Map.Entry<Object, Object> e : byTable.entrySet()) {
            if (String.valueOf(e.getKey()).equals(String.valueOf(src))) return e.getValue();
        }
        return null;
    }

    private static Set<String> normaliseReferences(List<String> configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String s : configured) if (s != null && !s.isBlank()) out.add(FkGraph.norm(s.trim()));
        return out;
    }

    private static String resolveBaseUrl(ProjectProfile p, String env) {
        if (p == null || env == null) return null;
        return switch (env.toLowerCase(Locale.ROOT)) {
            case "design" -> p.designBaseUrl();
            case "sandbox" -> p.sandboxBaseUrl();
            default -> null;
        };
    }

    private static boolean isUuidLike(Object v) {
        return v instanceof String s && UUID_PATTERN.matcher(s).matches();
    }

    /**
     * Journal-safe truncation. Kept generous (8000 chars) because these
     * strings feed the journal.message column that the UI shows to the user
     * for diagnosis — the goal is completeness over compactness. Log lines
     * can carry the same untruncated content; SLF4J handles long strings fine.
     */
    private static String trunc(String s) {
        return s == null ? "" : (s.length() > 8000 ? s.substring(0, 8000) + "…(+" + (s.length() - 8000) + " chars)" : s);
    }

    /**
     * Turn the verbose REST-refusal explanation into a short, user-facing tag.
     * The long form is useful in server logs for diagnosis; the journal cell
     * just needs a plain-English hint about why REST didn't work. Success —
     * the row IS in target — is what the message should lead with.
     */
    private static String shortRestRefusalReason(String longReason) {
        if (longReason == null) return "REST refused; used SQL";
        String l = longReason.toLowerCase(Locale.ROOT);
        if (l.contains("methodaccessdenied") || l.contains("access denied for this method"))
            return "REST create method restricted for this entity";
        if (l.contains("content-type"))
            return "payload too large for REST endpoint";
        if (l.contains("no post endpoint") || l.contains("no endpoint post"))
            return "no REST create endpoint registered";
        if (l.contains("assigned-pk"))
            return "REST layer discards client-supplied PK";
        if (l.contains("transient"))
            return "REST rejects referenced entity as unsaved (FAWB per-request session limitation)";
        return "REST refused for this entity";
    }

    /**
     * Extract the most useful part of a Spring/JDBC error string. Spring prefixes
     * DB errors with the full failing SQL ("StatementCallback; SQL [...]; ...")
     * which pushes the actual MariaDB message past any reasonable truncation.
     * If we see that pattern, keep everything AFTER the closing bracket — that's
     * the real "why". Also cap total length to 2000 chars.
     */
    /**
     * If the response body contains a MariaDB foreign-key constraint failure,
     * return the referenced parent table name (upper-case). Otherwise null.
     * Pattern used by FAWB (and MariaDB directly):
     *   {@code Cannot add or update a child row: a foreign key constraint fails
     *    (`<db>`.`<child>`, CONSTRAINT `<name>` FOREIGN KEY (`<col>`) REFERENCES `<PARENT>` (`<pkCol>`))}
     */
    private static String extractFkParentTable(String body) {
        if (body == null || body.isEmpty()) return null;
        if (!body.contains("foreign key constraint fails")) return null;
        // Look for REFERENCES `<PARENT>`
        int idx = body.indexOf("REFERENCES `");
        if (idx < 0) return null;
        int start = idx + "REFERENCES `".length();
        int end = body.indexOf('`', start);
        if (end <= start) return null;
        return body.substring(start, end);
    }

    private static String extractDbError(String s) {
        if (s == null || s.isBlank()) return "";
        String cleaned = s;
        int sqlStart = s.indexOf("SQL [");
        if (sqlStart >= 0) {
            // Use the LAST `]` in the string (not the first after "SQL [")
            // so we don't get confused by nested `]` inside a value (e.g. a
            // JSON blob written as `... SET col = '[stuff]' ...`).
            int sqlEnd = s.lastIndexOf(']');
            if (sqlEnd > sqlStart) {
                String prefix = s.substring(0, sqlStart).trim();
                String suffix = s.substring(sqlEnd + 1).trim();
                if (suffix.startsWith(";")) suffix = suffix.substring(1).trim();
                if (suffix.isBlank()) {
                    // FAWB stripped the DB detail — echo a helpful hint about
                    // where the full response body was captured on disk.
                    cleaned = prefix + " — (no DB detail returned by FAWB; check the failure-body dump on disk)";
                } else {
                    cleaned = prefix + " — " + suffix;
                }
            }
        }
        return cleaned.length() > 8000 ? cleaned.substring(0, 8000) + "…(+" + (cleaned.length() - 8000) + " chars)" : cleaned;
    }

    /**
     * SELECT the row from target to confirm whether it landed despite FAWB
     * reporting an INSERT failure. Returns the target-side PK if the row
     * exists, else null. Never throws — verification is best-effort; on
     * error we assume the row didn't land.
     */
    /**
     * Result of a SQL fallback attempt when REST refused an insert.
     * <ul>
     *   <li>{@code succeeded} — the raw-SQL INSERT landed cleanly. Caller
     *   should journal as {@code created} and populate id-map with {@code newPk}.</li>
     *   <li>{@code newPk} — the target-side PK. Non-null for assigned-PK tables
     *   (we know the value from FK remap). Null for identity-PK tables where
     *   the DB assigned the id and we didn't chase LAST_INSERT_ID (safe when
     *   the table is a leaf).</li>
     *   <li>{@code errorDetail} — populated when {@code succeeded=false}, empty
     *   when {@code succeeded=true}. Includes the raw envelope error text so
     *   the caller's "skipped" message can name both the REST and SQL failures.</li>
     * </ul>
     */
    private record SqlFallbackResult(boolean succeeded, Object newPk, String errorDetail) {}

    /**
     * Attempt a raw-SQL INSERT for a row that FAWB's REST layer refused.
     * Mirrors the setPairs construction of {@link #executeAssignedTable}:
     * strip server-generated columns, regenerate assigned-UUID PKs, remap
     * FK values via id-map (or sentinel), then emit
     * {@code INSERT INTO T SET col=val, ...} through {@link SqlService}.
     * <p>
     * Returns null when a fallback isn't sensible (e.g. an FK we need can't
     * be remapped because its target was itself skipped). The caller treats
     * this the same as {@code succeeded=false} and skips + cascade-marks.
     */
    private SqlFallbackResult trySqlFallbackInsert(Map<String, Object> sourceRow,
                                                    DataModel.Table table,
                                                    AppSqlPlanService.ColumnPolicies pol,
                                                    Map<String, Map<Object, Object>> idMap,
                                                    ProjectProfile profile, String targetEnv,
                                                    StepCounter counter, int order,
                                                    Object sourcePk, String primaryPk) {
        String tableName = table.name();
        Map<String, Object> setPairs = new LinkedHashMap<>();
        Object newPk = null;

        for (DataModel.Column col : safe(table.columns())) {
            String cname = col.name();
            if (cname == null) continue;
            String lower = cname.toLowerCase(Locale.ROOT);
            if (pol.strippedLower.contains(lower)) continue;

            Object v = lookup(sourceRow, cname);

            // Regenerate assigned-UUID PKs client-side (as executeAssignedTable does).
            if (pol.uuidPkColumnsLower.contains(lower) && isUuidLike(v)) {
                String fresh = UUID.randomUUID().toString();
                setPairs.put(cname, fresh);
                if (cname.equalsIgnoreCase(primaryPk)) newPk = fresh;
                continue;
            }

            // FK remap via id-map (populated for both walked tables and
            // configured reference tables). Sentinel fallback for audit cols.
            ImportPlan.FkRemap remap = pol.fkByColumnLower.get(lower);
            if (remap != null && v != null) {
                Object mapped = lookupIdMap(idMap, remap.targetTable(), v);
                if (mapped == null && remap.useSentinel()) {
                    mapped = ReferenceRemapService.lookupSentinel(idMap, remap.targetTable());
                }
                if (mapped != null) {
                    setPairs.put(cname, SqlBuilder.convertForInsert(mapped, col));
                    if (cname.equalsIgnoreCase(primaryPk)) newPk = mapped;
                    continue;
                }
                if (!remap.isReferenceTarget()) {
                    // FK target wasn't inserted and isn't a reference table —
                    // no way to satisfy the FK constraint. Bail on fallback.
                    return null;
                }
                // Reference target with no natural-key remap — pass-through.
                setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
                continue;
            }
            setPairs.put(cname, SqlBuilder.convertForInsert(v, col));
        }

        if (setPairs.isEmpty()) {
            return new SqlFallbackResult(false, null, "no columns to insert after strip/policy");
        }

        // Identify large-text columns whose values would blow past the URL
        // budget in a single INSERT (ACTIVITY_PAYLOAD.dataPayload etc.).
        // Split them out and route through INSERT-small + CONCAT-UPDATE-chunks.
        Map<String, DataModel.Column> colByName = new LinkedHashMap<>();
        for (DataModel.Column c : safe(table.columns())) {
            if (c != null && c.name() != null) colByName.put(c.name().toLowerCase(Locale.ROOT), c);
        }
        Map<String, String> largeTextValues = new LinkedHashMap<>();
        Map<String, Object> smallSetPairs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : setPairs.entrySet()) {
            DataModel.Column c = colByName.get(e.getKey().toLowerCase(Locale.ROOT));
            Object v = e.getValue();
            if (isLargeTextColumn(c) && v instanceof String s && !s.isEmpty()) {
                // If the source served it as base64-encoded (FAWB does this for
                // BLOB/CLOB columns), decode first so the stored value is raw
                // JSON/text (matches what the REST path does via formatForRest).
                String decoded = AppRestInsertService.maybeDecodeBase64Json(s);
                largeTextValues.put(e.getKey(), decoded);
                // Placeholder in the small INSERT: NULL if nullable, empty else.
                smallSetPairs.put(e.getKey(), (c != null && c.nullable()) ? null : "");
            } else {
                smallSetPairs.put(e.getKey(), v);
            }
        }
        boolean useSplitPath = !largeTextValues.isEmpty();

        // For identity-PK tables (no client-known PK), snapshot MAX(id) BEFORE
        // the INSERT. FAWB frequently silent-commits identity rows (returns
        // "could not execute statement — no DB detail" while the row lands).
        // Comparing MAX(id) after the INSERT lets us detect and recover.
        // Also used in the split path to discover the identity id for
        // subsequent CONCAT UPDATE targeting.
        Object maxIdBefore = null;
        if (newPk == null && primaryPk != null) {
            maxIdBefore = fetchMaxPk(profile, targetEnv, tableName, primaryPk);
        }

        // Build and run: DELETE-by-PK pre-clean + small INSERT. Pre-clean
        // protects against re-import collisions on assigned-PK tables (same
        // as executeAssignedTable's needsPreClean path).
        List<String> stmts = new ArrayList<>(2);
        if (newPk != null && primaryPk != null) {
            stmts.add(SqlBuilder.deleteByEquals(tableName, primaryPk, newPk));
        }
        stmts.add(SqlBuilder.insertSet(tableName, smallSetPairs));
        String batchSql = SqlBuilder.batch(stmts);

        ExecuteResult res;
        try {
            res = sqlService.execute(profile, targetEnv, batchSql);
        } catch (Exception e) {
            return new SqlFallbackResult(false, null,
                    "transport error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        String err;
        try {
            err = firstStatementError(res);
        } catch (Exception e) {
            return new SqlFallbackResult(false, null,
                    "response parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        // Determine the actual target PK now that INSERT has been attempted.
        // Two paths: (a) client-known PK for assigned tables — verify row
        // landed; (b) identity table — find via MAX(id) diff. Also handles
        // the silent-commit case where FAWB reports error but the row is in.
        //
        // Perf: for assigned-PK tables (a), only run the verify SELECT when
        // there's an error signal to investigate. Clean responses are trusted
        // and we skip an entire round-trip per row (~1-2s each). If FAWB ever
        // silently drops an assigned-PK row without erroring — rare enough
        // that we haven't seen it — downstream FK failures would surface it.
        Object actualPk = null;
        if (newPk != null && primaryPk != null) {
            if (err != null) {
                Object landedPk = verifyRowLanded(profile, targetEnv, tableName, primaryPk, newPk);
                if (landedPk != null) {
                    actualPk = newPk;
                    log.warn("SQL fallback for {} reported error but the row is in target (silent commit). "
                            + "Treating as success. FAWB error: {}", tableName, trunc(err));
                }
            } else {
                // Clean response — trust it. Skip the verify probe.
                actualPk = newPk;
            }
        } else if (primaryPk != null) {
            Object maxIdAfter = fetchMaxPk(profile, targetEnv, tableName, primaryPk);
            if (maxIdAfter != null && isGreaterThan(maxIdAfter, maxIdBefore)) {
                actualPk = maxIdAfter;
                if (err != null) {
                    log.warn("SQL fallback for {} (identity) reported error but MAX({}) went from {} to {} — silent commit detected. Treating as success.",
                            tableName, primaryPk, maxIdBefore, maxIdAfter);
                }
            }
        }
        if (actualPk == null) {
            // Insert genuinely failed.
            if (err != null) {
                return new SqlFallbackResult(false, null, err);
            }
            if (newPk == null && primaryPk != null) {
                return new SqlFallbackResult(false, null,
                        "MAX(" + primaryPk + ") did not increase after INSERT — row didn't land");
            }
            if (newPk != null) {
                return new SqlFallbackResult(false, null,
                        "SQL response was success but verification SELECT found no row with " + primaryPk + "=" + newPk);
            }
            // No PK to verify against — trust the response.
            return new SqlFallbackResult(true, null, "");
        }

        // Row is in target with PK = actualPk. If this was a split-path
        // insert (large-text columns extracted), now apply each large-text
        // via chunked CONCAT UPDATE. Same mechanism used by executeOversizedRow
        // for CASE_DATUM-style tables: base64-shielded chunks WHERE pk=actualPk.
        if (useSplitPath && primaryPk != null) {
            for (Map.Entry<String, String> lt : largeTextValues.entrySet()) {
                String colName = lt.getKey();
                String fullValue = lt.getValue();
                if (fullValue == null || fullValue.isEmpty()) continue;
                List<String> chunks = chunkString(fullValue, LARGE_TEXT_CHUNK_CHARS);
                for (int idx = 0; idx < chunks.size(); idx++) {
                    String chunkValue = chunks.get(idx);
                    String updateSql = (idx == 0)
                            ? SqlBuilder.updateSetBase64(tableName, colName, chunkValue, primaryPk, actualPk)
                            : SqlBuilder.updateConcatSetBase64(tableName, colName, chunkValue, primaryPk, actualPk);
                    ExecuteResult ur;
                    try {
                        ur = sqlService.execute(profile, targetEnv, updateSql);
                    } catch (Exception e) {
                        return new SqlFallbackResult(false, null,
                                "large-text CONCAT UPDATE for " + colName + " chunk " + (idx + 1)
                                        + "/" + chunks.size() + " transport error: "
                                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    String uErr;
                    try { uErr = firstStatementError(ur); }
                    catch (Exception e) {
                        return new SqlFallbackResult(false, null,
                                "large-text CONCAT UPDATE for " + colName + " chunk " + (idx + 1)
                                        + " parse error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    if (uErr != null) {
                        // Silent-commit pattern: FAWB returns "could not execute
                        // statement — (no DB detail returned by FAWB...)" but the
                        // UPDATE actually applies at DB level. Same pattern we've
                        // observed on APPLICATION_DETAILS / PersonFinancial / etc.
                        // Trust the pattern and continue to the next chunk; a
                        // final length verification below confirms whether the
                        // full content landed.
                        if (uErr.contains("no DB detail returned")) {
                            log.warn("Large-text CONCAT UPDATE for {}.{} chunk {}/{} reported '{}' — likely silent commit, continuing.",
                                    tableName, colName, idx + 1, chunks.size(), trunc(uErr));
                            continue;
                        }
                        return new SqlFallbackResult(false, null,
                                "large-text CONCAT UPDATE for " + colName + " chunk " + (idx + 1)
                                        + "/" + chunks.size() + " failed: " + uErr);
                    }
                }
                // Verify the full content landed: SELECT CHAR_LENGTH and compare
                // to the expected raw length. If it matches (or is close, within
                // a few bytes for UTF-8 multi-byte characters), all chunks got
                // applied — silent-commit or otherwise.
                Object landedLen = fetchColumnLength(profile, targetEnv, tableName, colName, primaryPk, actualPk);
                int expectedLen = fullValue.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (landedLen == null) {
                    log.warn("Large-text verification for {}.{} pk={}: SELECT returned no length; can't confirm all chunks landed. Trusting the split.",
                            tableName, colName, actualPk);
                } else {
                    long actualLen = ((Number) landedLen).longValue();
                    if (actualLen < expectedLen * 0.9) {
                        // Less than 90% of expected — chunks likely didn't all commit.
                        return new SqlFallbackResult(false, null,
                                "large-text CONCAT UPDATEs claimed silent-commit but final "
                                        + colName + " byte length in target is " + actualLen
                                        + ", expected ~" + expectedLen
                                        + ". Chunks did not fully land.");
                    }
                    log.info("Large-text {}.{} pk={}: verified byte length {} vs expected {} (all chunks landed).",
                            tableName, colName, actualPk, actualLen, expectedLen);
                }
            }
            log.info("SQL fallback split path completed for {}: {} large-text column(s), pk={}",
                    tableName, largeTextValues.size(), actualPk);
        }
        return new SqlFallbackResult(true, actualPk, "");
    }

    /**
     * Query {@code SELECT LENGTH(col) FROM T WHERE pk = X} to verify how much
     * data actually landed for a large-text column. Used after chunked
     * CONCAT UPDATE to confirm all chunks were applied despite FAWB's silent-
     * commit error responses. Returns null on any parse/transport error.
     */
    private Object fetchColumnLength(ProjectProfile profile, String targetEnv, String tableName,
                                     String colName, String pkCol, Object pkValue) {
        try {
            String sql = "SELECT LENGTH(" + SqlBuilder.ident(colName) + ") AS len FROM "
                    + SqlBuilder.ident(tableName) + " WHERE " + SqlBuilder.ident(pkCol)
                    + " = " + SqlBuilder.literal(pkValue);
            ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
            if (res.status() < 200 || res.status() >= 300) return null;
            String body = res.body();
            if (body == null || body.isBlank()) return null;
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return null;
            for (JsonNode env : arr) {
                if (env == null || !env.isObject()) continue;
                JsonNode resp = env.get("response");
                if (resp == null || !resp.isTextual()) continue;
                String s = resp.asText();
                if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;
                if (s.charAt(0) != '{' && s.charAt(0) != '[') continue;
                JsonNode parsed = mapper.readTree(s);
                JsonNode row = parsed.isArray() ? (parsed.isEmpty() ? null : parsed.get(0)) : parsed;
                if (row == null || !row.isObject()) continue;
                JsonNode v = row.get("len");
                if (v == null || v.isNull()) {
                    var fields = row.fields();
                    while (fields.hasNext()) {
                        var e = fields.next();
                        if (e.getValue().isNumber()) { v = e.getValue(); break; }
                    }
                }
                if (v != null && v.isNumber()) return v.numberValue();
            }
        } catch (Exception e) {
            log.debug("fetchColumnLength failed for {}.{} pk={}: {}", tableName, colName, pkValue, e.getMessage());
        }
        return null;
    }

    /**
     * Query {@code SELECT <pk> FROM <table> ORDER BY <pk> DESC LIMIT 1} to
     * find the highest existing PK value. Used to snapshot before/after an
     * identity-PK insert so we can detect silent commits by comparing.
     * Returns null on any error (missing endpoint access, parse failure,
     * empty table) — callers must be robust to that.
     */
    private Object fetchMaxPk(ProjectProfile profile, String targetEnv, String tableName, String pkCol) {
        try {
            String sql = "SELECT " + SqlBuilder.ident(pkCol) + " FROM " + SqlBuilder.ident(tableName)
                    + " ORDER BY " + SqlBuilder.ident(pkCol) + " DESC LIMIT 1";
            ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
            if (res.status() < 200 || res.status() >= 300) return null;
            String body = res.body();
            if (body == null || body.isBlank()) return null;
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return null;
            for (JsonNode env : arr) {
                if (env == null || !env.isObject()) continue;
                JsonNode resp = env.get("response");
                if (resp == null || !resp.isTextual()) continue;
                String s = resp.asText();
                if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;
                if (s.charAt(0) != '{' && s.charAt(0) != '[') continue;
                JsonNode parsed = mapper.readTree(s);
                JsonNode row = parsed.isArray() ? (parsed.isEmpty() ? null : parsed.get(0)) : parsed;
                if (row == null || !row.isObject()) continue;
                // Case-insensitive column lookup
                JsonNode v = row.get(pkCol);
                if (v == null || v.isNull()) {
                    var fields = row.fields();
                    while (fields.hasNext()) {
                        var e = fields.next();
                        if (e.getKey() != null && e.getKey().equalsIgnoreCase(pkCol)) {
                            v = e.getValue();
                            break;
                        }
                    }
                }
                if (v == null || v.isNull()) continue;
                return v.isNumber() ? v.numberValue() : v.asText();
            }
        } catch (Exception e) {
            log.debug("fetchMaxPk failed for {}.{}: {}", tableName, pkCol, e.getMessage());
        }
        return null;
    }

    /** Numeric comparison for {@code Object} pk values (handles both integer/long forms). */
    private static boolean isGreaterThan(Object after, Object before) {
        if (after == null) return false;
        if (before == null) return true;   // any value beats "no rows before"
        try {
            long a = (after instanceof Number) ? ((Number) after).longValue() : Long.parseLong(after.toString());
            long b = (before instanceof Number) ? ((Number) before).longValue() : Long.parseLong(before.toString());
            return a > b;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Object verifyRowLanded(ProjectProfile profile, String targetEnv, String tableName,
                                   String pkCol, Object pkValue) {
        try {
            String sql = SqlBuilder.selectByEquals(tableName, pkCol, pkValue);
            ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
            if (res.status() < 200 || res.status() >= 300) return null;
            String body = res.body();
            if (body == null || body.isBlank()) return null;
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return null;
            for (JsonNode env : arr) {
                if (env == null || !env.isObject()) continue;
                JsonNode resp = env.get("response");
                if (resp == null || !resp.isTextual()) continue;
                String s = resp.asText();
                if (s.isBlank() || "success".equalsIgnoreCase(s)) continue;
                if (s.charAt(0) != '{' && s.charAt(0) != '[') continue;
                // Row(s) came back — verification succeeded.
                return pkValue;
            }
        } catch (Exception e) {
            log.debug("verifyRowLanded threw for {}.{}={}: {}", tableName, pkCol, pkValue, e.getMessage());
        }
        return null;
    }

    /**
     * When a batch INSERT fails, write the full raw response body to disk so
     * we can inspect exactly what FAWB returned (the sql-diagnostic.log
     * truncates bodies at 800 chars). Path is
     * {@code %USERPROFILE%\.devbridge\imports\<profileId>\failed-bodies\failed-<table>-<order>-<ts>.txt}.
     * Never throws — diagnostic only.
     */
    private void dumpFailedResponseBody(String body, String profileId, String tableName, int order) {
        if (body == null || body.isBlank()) return;
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path dir = java.nio.file.Paths.get(home, ".devbridge", "imports",
                    profileId != null ? profileId : "unknown", "failed-bodies");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve("failed-" + tableName + "-" + order + "-"
                    + System.currentTimeMillis() + ".txt");
            java.nio.file.Files.writeString(file, body);
            log.warn("Full failure response body saved to: {}", file.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to dump failure body to disk: {}", e.getMessage());
        }
    }

    private static <T> List<T> safe(List<T> l) { return l == null ? List.of() : l; }

    /* ---------- rollback ---------- */

    /**
     * Roll back everything that landed on target and update the journal's
     * final status accordingly. Called from BOTH the {@link HaltException} and
     * the unexpected-exception branches so any failure — expected or not —
     * leaves the target in its pre-run state.
     */
    private ImportJournal rollbackAndAnnotate(ImportJournal journal, ProjectProfile profile,
                                              String targetEnv, FkGraph graph,
                                              String failureReason) {
        RollbackOutcome rb = rollback(journal, profile, targetEnv, graph);
        String status = rb.nothingToRevert()
                ? "failed"                                   // nothing landed, nothing to undo
                : (rb.everythingReverted() ? "failed-rolled-back" : "failed");
        String annotated = failureReason + "\nRollback: " + rb.summary();
        return journal.withStatus(status, annotated, Instant.now());
    }

    /**
     * Undo every row this run committed to target. Walks the journal in reverse
     * (child rows before their parents, so FK constraints stay satisfied) and
     * groups consecutive same-table entries into batched {@code DELETE ... WHERE
     * pk IN (...)} statements. Best-effort — a failure on one group is logged
     * and rollback continues on the rest.
     * <p>
     * Filter is {@code targetId != null}, not {@code result == "created"} — a
     * split-mode row whose INSERT succeeded but a follow-up CONCAT UPDATE
     * failed is journaled as {@code result=failed} with the target PK set,
     * and MUST still be deleted to keep target clean.
     */
    private RollbackOutcome rollback(ImportJournal journal, ProjectProfile profile,
                                     String targetEnv, FkGraph graph) {
        List<ImportJournal.Entry> entries = journal.entries();
        if (entries == null || entries.isEmpty()) return new RollbackOutcome(0, 0, List.of());

        // Walk in reverse, group consecutive same-table entries with targetId != null
        List<TableRollback> groups = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ImportJournal.Entry e = entries.get(i);
            if (e.targetId() == null) continue;   // never landed on target
            String tname = FkGraph.norm(e.tableName());
            if (!groups.isEmpty() && groups.get(groups.size() - 1).table.equals(tname)) {
                groups.get(groups.size() - 1).pks.add(e.targetId());
            } else {
                TableRollback g = new TableRollback();
                g.table = tname;
                g.pks.add(e.targetId());
                groups.add(g);
            }
        }
        if (groups.isEmpty()) return new RollbackOutcome(0, 0, List.of());

        int deleted = 0, failed = 0;
        List<String> issues = new ArrayList<>();
        for (TableRollback g : groups) {
            DataModel.Table t = graph.table(g.table).orElse(null);
            if (t == null) {
                issues.add(g.table + ": table not found in dataModel — " + g.pks.size() + " row(s) not deleted");
                failed += g.pks.size();
                continue;
            }
            // Use the dataModel's original-case table name for the SQL. FAWB's DB
            // is case-sensitive on identifiers (Linux + lower_case_table_names=0);
            // g.table is upper-normalised for id-map grouping and cannot be used
            // in the emitted SQL — DELETE FROM `CASEREVIEW` would fail when the
            // real table is `CaseReview`.
            String dbTableName = t.name();
            List<String> pkCols = t.primaryKey() == null ? null : t.primaryKey().columns();
            if (pkCols == null || pkCols.isEmpty()) {
                issues.add(g.table + ": no PK column — " + g.pks.size() + " row(s) not deleted");
                failed += g.pks.size();
                continue;
            }
            String pkCol = pkCols.get(0);
            // Batch DELETE ... IN (...) — grow the pk list until the URL-encoded
            // SQL would exceed the budget, then flush.
            int i = 0;
            while (i < g.pks.size()) {
                List<Object> chunk = new ArrayList<>();
                while (i < g.pks.size()) {
                    chunk.add(g.pks.get(i));
                    String candidate = SqlBuilder.selectByIn(dbTableName, pkCol, chunk);
                    if (candidate == null) { i++; continue; }
                    candidate = "DELETE FROM " + candidate.substring("SELECT * FROM ".length());
                    if (chunk.size() > 1 && urlEncodedLength(candidate) > MAX_ENCODED_SQL_LENGTH) {
                        chunk.remove(chunk.size() - 1);
                        break;
                    }
                    i++;
                }
                if (chunk.isEmpty()) continue;
                String sql = SqlBuilder.selectByIn(dbTableName, pkCol, chunk);
                if (sql == null) continue;
                sql = "DELETE FROM " + sql.substring("SELECT * FROM ".length());
                try {
                    ExecuteResult res = sqlService.execute(profile, targetEnv, sql);
                    // Two-layer check: HTTP status AND per-envelope response text.
                    // executeSQLs returns HTTP 200 even when the DELETE fails with a
                    // FK constraint (the DB error is embedded in the envelope's
                    // "response" field). Reuse firstStatementError to catch both.
                    String err = firstStatementError(res);
                    if (err != null) {
                        issues.add(g.table + ": " + err);
                        failed += chunk.size();
                    } else {
                        // "N rows deleted" is misleading — HTTP+envelope success
                        // doesn't tell us whether rows existed. Count is the number
                        // of PKs we ATTEMPTED; unaffected-row DELETE also returns
                        // success. Correct rollback status is derived from failed=0.
                        deleted += chunk.size();
                    }
                } catch (Exception e) {
                    issues.add(g.table + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
                    failed += chunk.size();
                }
            }
        }
        return new RollbackOutcome(deleted, failed, issues);
    }

    private static final class TableRollback {
        String table;
        final List<Object> pks = new ArrayList<>();
    }

    private record RollbackOutcome(int deleted, int failed, List<String> issues) {
        String summary() {
            if (deleted == 0 && failed == 0) return "nothing to revert (no rows had landed on target)";
            String s = deleted + " row(s) deleted, " + failed + " failed";
            if (!issues.isEmpty()) s += "; " + String.join("; ", issues);
            return s;
        }
        boolean everythingReverted() { return failed == 0 && deleted > 0; }
        boolean nothingToRevert() { return deleted == 0 && failed == 0; }
    }

    private record PreparedRow(Object sourcePk, Object newPk, Map<String, Object> setPairs) {}

    /**
     * Thrown by executor helpers on failure. Carries an optional snapshot of
     * the latest journal so the top-level try/catch in {@link #execute} can
     * see entries that were added deeper in the call stack. Without this,
     * ImportJournal being immutable (a record) means any withEntry mutations
     * done in a helper are lost when the helper throws — the caller's local
     * `journal` variable still points at the pre-call version. That was the
     * source of "silent-commit fix journaled it but rollback didn't see it".
     */
    private static final class HaltException extends RuntimeException {
        final ImportJournal journalSnapshot;   // may be null when caller has no updated journal to attach
        HaltException(String msg) { this(msg, null); }
        HaltException(String msg, ImportJournal journalSnapshot) {
            super(msg);
            this.journalSnapshot = journalSnapshot;
        }
    }

    private static final class StepCounter {
        private int n = 0;
        int next() { return ++n; }
        int get() { return n; }
    }
}
