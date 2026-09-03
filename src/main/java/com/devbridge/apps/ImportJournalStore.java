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
