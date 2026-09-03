package com.devbridge.apps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only record of every DELETE attempt during a delete-app run.
 * Persisted step-by-step so a crash or interruption leaves a truthful record
 * on disk. Unlike {@link ImportJournal} there is no rollback path — a failed
 * DELETE is just journaled and the run halts.
 */
public record DeleteJournal(
        String jobId,
        String profileId,
        String appId,
        String env,
        String baseUrl,
        Instant startedAt,
        Instant endedAt,
        String status,        // "running" | "success" | "failed"
        String errorMessage,  // populated when status = "failed"
        int totalPlanned,
        List<Entry> entries
) {
    /** One row of the journal — one DELETE attempt. */
    public record Entry(
            int order,
            String entityName,
            String tableName,
            Object deletedId,       // the PK we tried to delete
            int httpStatus,
            String result,          // "deleted" | "failed" | "skipped"
            String message,
            Instant timestamp
    ) {}

    public DeleteJournal withEntry(Entry e) {
        List<Entry> copy = new ArrayList<>(entries == null ? List.of() : entries);
        copy.add(e);
        return new DeleteJournal(jobId, profileId, appId, env, baseUrl,
                startedAt, endedAt, status, errorMessage, totalPlanned, copy);
    }

    public DeleteJournal withStatus(String newStatus, String message, Instant when) {
        return new DeleteJournal(jobId, profileId, appId, env, baseUrl,
                startedAt, when, newStatus, message, totalPlanned, entries);
    }
}
