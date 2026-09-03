package com.devbridge.datamodel;

import java.time.Instant;

/**
 * Lightweight summary returned to the frontend when checking whether a profile
 * has a dataModel loaded, and after upload. Never includes the raw JSON body.
 */
public record DataModelSummary(
        boolean loaded,
        String name,
        String packageName,
        int tables,
        int columns,
        int relations,
        int virtualRelations,
        long sizeBytes,
        Instant uploadedAt
) {
    public static DataModelSummary notLoaded() {
        return new DataModelSummary(false, null, null, 0, 0, 0, 0, 0L, null);
    }
}
