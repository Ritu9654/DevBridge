package com.devbridge.apps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;

/**
 * Persists {@link ImportJournal} objects under
 * {@code %USERPROFILE%\.devbridge\imports\{profileId}\{yyyyMMdd-HHmmss}-{jobId}.json}.
 * Every write is a full overwrite of the file — cheap for the sizes we deal with
 * (dozens to a few hundred entries) and gives us an atomic, always-consistent
 * snapshot even if the executor is killed mid-run.
 */
@Component
public class ImportJournalStore {

    private static final Logger log = LoggerFactory.getLogger(ImportJournalStore.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path root;
    private final ObjectMapper mapper;

    public ImportJournalStore(ObjectMapper baseMapper) {
        this.mapper = baseMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        String home = System.getProperty("user.home");
        this.root = Paths.get(home, ".devbridge", "imports");
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise import journal directory " + root, e);
        }
    }

    /**
     * Read the most-recent (by filename timestamp) import journal for the given
     * profile. Used by the frontend to poll live progress while an import is
     * running — the file is rewritten after each row so we always see the
     * current state within one poll interval.
     *
     * <p>Returns {@link java.util.Optional#empty()} if no journals exist for
     * this profile or the file can't be read.
     */
    public java.util.Optional<ImportJournal> readLatest(String profileId) {
        if (profileId == null || profileId.isBlank()) return java.util.Optional.empty();
        Path dir = root.resolve(profileId);
        if (!Files.isDirectory(dir)) return java.util.Optional.empty();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .flatMap(p -> {
                        try {
                            return java.util.Optional.of(mapper.readValue(p.toFile(), ImportJournal.class));
                        } catch (IOException e) {
                            log.debug("Failed to read journal {}: {}", p, e.getMessage());
                            return java.util.Optional.empty();
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to list journals for {}: {}", profileId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Write (or overwrite) the given journal to its per-profile file. Returns the
     * absolute path so callers can log or surface it in responses.
     */
    public Path write(ImportJournal journal) {
        if (journal == null || journal.profileId() == null || journal.jobId() == null) {
            throw new IllegalArgumentException("journal.profileId and journal.jobId are required");
        }
        Path dir = root.resolve(journal.profileId());
        try {
            Files.createDirectories(dir);
            String stamp = STAMP.format(journal.startedAt() != null
                    ? journal.startedAt().atZone(java.time.ZoneId.systemDefault())
                    : java.time.ZonedDateTime.now());
            Path file = dir.resolve(stamp + "-" + journal.jobId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), journal);
            return file;
        } catch (IOException e) {
            log.warn("Failed to write import journal for job {}: {}", journal.jobId(), e.getMessage());
            throw new RuntimeException("Failed to persist import journal", e);
        }
    }
}
