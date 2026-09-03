package com.devbridge.apps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of every write attempt during an import run. Persisted
 * to disk step-by-step so a crash or interruption still leaves a truthful
 * record on disk — the input to the auto-rollback path.
 */
public record ImportJournal(
        String jobId,
        String profileId,
        String appId,
        String sourceEnv,
        String targetEnv,
        String targetBaseUrl,
        Instant startedAt,
        Instant endedAt,
        String status,       // "running" | "success" | "failed" | "cancelled"
        String errorMessage, // populated when status = "failed"
        int totalPlanned,
        List<Entry> entries
) {
    /** One row of the journal — one POST attempt. */
    public record Entry(
            int order,
            String entityName,
            String tableName,
            String targetUrl,
            Object sourceId,        // the source-env PK (before remap)
            Object targetId,        // the target-env PK (server-assigned), null on failure
            int httpStatus,
            String result,          // "created" | "failed" | "skipped"
            String message,         // human-readable outcome
            Instant timestamp
    ) {}

    public ImportJournal withEntry(Entry e) {
        List<Entry> copy = new ArrayList<>(entries == null ? List.of() : entries);
        copy.add(e);
        return new ImportJournal(jobId, profileId, appId, sourceEnv, targetEnv, targetBaseUrl,
                startedAt, endedAt, status, errorMessage, totalPlanned, copy);
    }

    public ImportJournal withStatus(String newStatus, String message, Instant when) {
        return new ImportJournal(jobId, profileId, appId, sourceEnv, targetEnv, targetBaseUrl,
                startedAt, when, newStatus, message, totalPlanned, entries);
    }
}
