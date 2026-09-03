package com.devbridge.datamodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Persists per-profile FAWB dataModel JSON files under
 * <code>%USERPROFILE%\.devbridge\cache\{profileId}\dataModel.json</code>,
 * parses them on load / upload, and caches the parsed model in memory
 * (keyed by profileId) for fast repeated access.
 */
@Service
public class DataModelService {

    private static final Logger log = LoggerFactory.getLogger(DataModelService.class);
    private static final String CACHE_FILE_NAME = "dataModel.json";
    /** Minimum keys we expect at the top level of a valid published dataModel. */
    private static final String[] REQUIRED_TOP_KEYS = {"tables"};

    private final Path cacheRoot;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, DataModel> memoryCache = new ConcurrentHashMap<>();

    public DataModelService(ObjectMapper mapper) {
        this.mapper = mapper;
        String home = System.getProperty("user.home");
        this.cacheRoot = Paths.get(home, ".devbridge", "cache");
        try {
            Files.createDirectories(this.cacheRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise dataModel cache at " + cacheRoot, e);
        }
    }

    /**
     * Save the uploaded JSON bytes for a profile. Parses to validate structure
     * before persisting; throws if the JSON is malformed or missing expected keys.
     */
    public DataModel saveAndParse(String profileId, byte[] bytes) throws IOException {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId is required");
        }
        // Parse-and-validate first (in memory) so a bad upload never touches disk
        JsonNode tree = mapper.readTree(bytes);
        if (!tree.isObject()) {
            throw new IllegalArgumentException(
                    "Uploaded file is not a JSON object at the top level.");
        }
        for (String key : REQUIRED_TOP_KEYS) {
            if (!tree.has(key)) {
                throw new IllegalArgumentException(
                        "Uploaded file is missing required key '" + key
                                + "'. Is this the published dataModel JSON?");
            }
        }
        DataModel model = mapper.treeToValue(tree, DataModel.class);

        // Persist to disk
        Path target = pathFor(profileId);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);

        memoryCache.put(profileId, model);
        log.info("Cached dataModel for profile {} ({} tables, {} bytes)",
                profileId, model.tableCount(), bytes.length);
        return model;
    }

    public Optional<DataModel> get(String profileId) {
        if (profileId == null || profileId.isBlank()) return Optional.empty();
        DataModel cached = memoryCache.get(profileId);
        if (cached != null) return Optional.of(cached);

        // Try to load from disk (survives app restart)
        Path target = pathFor(profileId);
        if (!Files.exists(target)) return Optional.empty();
        try {
            DataModel model = mapper.readValue(target.toFile(), DataModel.class);
            memoryCache.put(profileId, model);
            return Optional.of(model);
        } catch (IOException e) {
            log.warn("Failed to load cached dataModel for {}: {}", profileId, e.getMessage());
            return Optional.empty();
        }
    }

    public DataModelSummary summary(String profileId) {
        Optional<DataModel> maybe = get(profileId);
        if (maybe.isEmpty()) return DataModelSummary.notLoaded();
        DataModel m = maybe.get();
        Path file = pathFor(profileId);
        long size = 0;
        Instant uploadedAt = null;
        try {
            if (Files.exists(file)) {
                size = Files.size(file);
                uploadedAt = Files.getLastModifiedTime(file).toInstant();
            }
        } catch (IOException e) {
            log.debug("Could not stat dataModel file for {}: {}", profileId, e.getMessage());
        }
        return new DataModelSummary(
                true, m.name(), m.packageName(),
                m.tableCount(), m.columnCount(), m.relationCount(), m.virtualRelationCount(),
                size, uploadedAt);
    }

    public boolean delete(String profileId) {
        if (profileId == null) return false;
        memoryCache.remove(profileId);
        Path target = pathFor(profileId);
        try {
            return Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Failed to delete dataModel for {}: {}", profileId, e.getMessage());
            return false;
        }
    }

    private Path pathFor(String profileId) {
        return cacheRoot.resolve(profileId).resolve(CACHE_FILE_NAME);
    }
}
