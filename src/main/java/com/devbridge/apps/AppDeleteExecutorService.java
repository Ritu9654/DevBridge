package com.devbridge.apps;

import com.devbridge.apps.AppSqlFetchService.FetchResult;
import com.devbridge.datamodel.DataModel;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Deletes an app's rows from a single env by walking the fetched rows in
 * reverse-topological FK order (children first, root last) and emitting
 * one {@code DELETE FROM T WHERE pk = v} per row. Statements are batched
 * into groups of {@link #BATCH_SIZE} per HTTP call.
 *
 * <p>Safety invariants:
 * <ol>
 *   <li>Halt on the first envelope that reports failure. Later tables are
 *   NOT attempted — the user reruns after fixing the FK blocker.</li>
 *   <li>Journal every DELETE outcome (success and failure), persist to disk
 *   after each table so an interruption still leaves a truthful record.</li>
 *   <li>All FK-dependent tables are included (no referenceTables filter) so FK constraints are always satisfied.</li>
 *   <li>No rollback — delete is destructive. Rerun is safe: already-deleted
 *   rows just no-op (envelope will report a benign "no rows affected").</li>
 * </ol>
 */
@Service
public class AppDeleteExecutorService {

    private static final Logger log = LoggerFactory.getLogger(AppDeleteExecutorService.class);
    /** DELETEs per HTTP call. Keeps each URL comfortably under the executeSQLs GET budget. */
    private static final int BATCH_SIZE = 50;

    private final SqlService sqlService;
    private final ObjectMapper mapper;
    private final DeleteJournalStore journalStore;

    public AppDeleteExecutorService(SqlService sqlService, ObjectMapper mapper,
                                    DeleteJournalStore journalStore) {
        this.sqlService = sqlService;
        this.mapper = mapper;
        this.journalStore = journalStore;
    }

    public DeleteJournal execute(FetchResult fetch, ProjectProfile profile,
                                 String env, String appId) {
        String jobId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        String baseUrl = resolveBaseUrl(profile, env);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Profile has no " + env + " base URL. Cannot execute delete.");
        }

        int totalPlanned = fetch.totalRowCount();
        DeleteJournal journal = new DeleteJournal(jobId, profile.id(), appId, env, baseUrl,
                startedAt, null, "running", null, totalPlanned, new ArrayList<>());
        journalStore.write(journal);

        FkGraph graph = fetch.graph();
        List<String> subset = new ArrayList<>(fetch.rowsByTable().keySet());
        FkGraph.TopoResult topo = graph.topoSort(subset);
        List<String> deleteOrder = new ArrayList<>(topo.ordered());
        Collections.reverse(deleteOrder);

        StepCounter counter = new StepCounter();
        try {
            for (String tableName : deleteOrder) {
                List<Map<String, Object>> rows = fetch.rowsByTable().get(tableName);
                if (rows == null || rows.isEmpty()) continue;

                DataModel.Table table = graph.table(tableName).orElse(null);
                if (table == null) continue;
                String pkColumn = firstPkColumn(table);
                if (pkColumn == null) {
                    journal = journal.withEntry(new DeleteJournal.Entry(
                            counter.next(), table.entityName(), tableName, null, 0, "skipped",
                            "Table has no PK column — cannot emit DELETE", Instant.now()));
                    journalStore.write(journal);
                    continue;
                }

                journal = deleteTable(journal, table, pkColumn, rows, profile, env, counter);

                journalStore.write(journal);
                // Halt as soon as this table reported a failure.
                if (containsFailure(journal)) {
                    String reason = firstFailureMessage(journal);
                    journal = journal.withStatus("failed",
                            "Halted at table " + tableName + ": " + reason, Instant.now());
                    journalStore.write(journal);
                    return journal;
                }
            }
            journal = journal.withStatus("success", null, Instant.now());
        } catch (Exception e) {
            log.warn("Delete halted for job {}: {}", jobId, e.getMessage());
            String msg = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "(no detail)");
            journal = journal.withStatus("failed", msg, Instant.now());
        }
        journalStore.write(journal);
        return journal;
    }

    /**
     * Batch DELETEs for one table into HTTP calls of at most {@link #BATCH_SIZE} rows.
     * Every envelope in the response is journaled (success or failure) before returning.
     * Does NOT throw on a per-row failure — the caller checks {@link #containsFailure}.
     */
    private DeleteJournal deleteTable(DeleteJournal journal, DataModel.Table table,
                                      String pkColumn, List<Map<String, Object>> rows,
                                      ProjectProfile profile, String env, StepCounter counter) {
        String tableName = table.name();
        String entityName = table.entityName();

        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, rows.size());
            List<Map<String, Object>> chunk = rows.subList(start, end);
            List<Object> pkValues = new ArrayList<>(chunk.size());
            StringBuilder sql = new StringBuilder();
            for (Map<String, Object> row : chunk) {
                Object pk = lookupCaseInsensitive(row, pkColumn);
                pkValues.add(pk);
                sql.append(SqlBuilder.deleteByEquals(tableName, pkColumn, pk)).append(";");
            }

            int httpStatus = 0;
            String body;
            try {
                ExecuteResult res = sqlService.execute(profile, env, sql.toString());
                httpStatus = res.status();
                body = res.body();
            } catch (Exception e) {
                // Network / transport failure — mark the whole chunk as failed.
                for (Object pk : pkValues) {
                    journal = journal.withEntry(new DeleteJournal.Entry(
                            counter.next(), entityName, tableName, pk, 0, "failed",
                            "Transport error: " + e.getClass().getSimpleName()
                                    + ": " + safeMessage(e.getMessage()),
                            Instant.now()));
                }
                return journal;
            }

            List<String> envelopes = parseEnvelopeMessages(body, pkValues.size());
            for (int i = 0; i < pkValues.size(); i++) {
                String envMsg = i < envelopes.size() ? envelopes.get(i) : "(no envelope in response)";
                boolean ok = "success".equalsIgnoreCase(envMsg);
                journal = journal.withEntry(new DeleteJournal.Entry(
                        counter.next(),
                        entityName,
                        tableName,
                        pkValues.get(i),
                        httpStatus,
                        ok ? "deleted" : "failed",
                        ok ? "OK" : trunc(envMsg),
                        Instant.now()));
            }
        }
        return journal;
    }

    /**
     * Parse the executeSQLs response body into a list of per-statement outcome
     * strings. Each entry is either {@code "success"} or a DB error message.
     * If the response is malformed or shorter than expected, missing slots
     * default to {@code "(no envelope)"}.
     */
    private List<String> parseEnvelopeMessages(String body, int expected) {
        List<String> out = new ArrayList<>(expected);
        if (body == null || body.isBlank()) return out;
        try {
            JsonNode arr = mapper.readTree(body);
            if (arr.isArray()) {
                for (JsonNode env : arr) {
                    if (env == null || !env.isObject()) { out.add("(malformed envelope)"); continue; }
                    JsonNode resp = env.get("response");
                    if (resp == null || resp.isNull()) { out.add("(no response field)"); continue; }
                    out.add(resp.isTextual() ? resp.asText() : resp.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse delete response body: {}", e.getMessage());
        }
        return out;
    }

    private static boolean containsFailure(DeleteJournal journal) {
        if (journal.entries() == null) return false;
        for (DeleteJournal.Entry e : journal.entries()) {
            if ("failed".equals(e.result())) return true;
        }
        return false;
    }

    private static String firstFailureMessage(DeleteJournal journal) {
        if (journal.entries() == null) return "(unknown)";
        for (DeleteJournal.Entry e : journal.entries()) {
            if ("failed".equals(e.result())) return e.message() != null ? e.message() : "(no message)";
        }
        return "(unknown)";
    }

    private static String resolveBaseUrl(ProjectProfile profile, String env) {
        if ("design".equalsIgnoreCase(env)) return profile.designBaseUrl();
        return profile.sandboxBaseUrl();
    }

    private static String firstPkColumn(DataModel.Table t) {
        if (t == null || t.primaryKey() == null || t.primaryKey().columns() == null) return null;
        List<String> cols = t.primaryKey().columns();
        return cols.isEmpty() ? null : cols.get(0);
    }

    private static Object lookupCaseInsensitive(Map<String, Object> row, String col) {
        if (row == null || col == null) return null;
        if (row.containsKey(col)) return row.get(col);
        String lower = col.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(lower)) return e.getValue();
        }
        return null;
    }

    private static String trunc(String s) {
        return s == null ? "" : (s.length() > 8000 ? s.substring(0, 8000) + "…(+" + (s.length() - 8000) + " chars)" : s);
    }

    private static String safeMessage(String s) { return s == null ? "(no detail)" : s; }

    /** Monotonic per-run counter, so every journal entry has a unique 1-based order. */
    private static final class StepCounter {
        private int n = 0;
        int next() { return ++n; }
    }
}
