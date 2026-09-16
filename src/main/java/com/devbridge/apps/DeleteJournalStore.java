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
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Persists {@link DeleteJournal} objects under
 * {@code %USERPROFILE%\.devbridge\deletes\{profileId}\{yyyyMMdd-HHmmss}-{jobId}.json}.
 * Full-overwrite writes on each step — same approach as {@link ImportJournalStore}.
 */
@Component
public class DeleteJournalStore {

    private static final Logger log = LoggerFactory.getLogger(DeleteJournalStore.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path root;
    private final ObjectMapper mapper;

    public DeleteJournalStore(ObjectMapper baseMapper) {
        this.mapper = baseMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        String home = System.getProperty("user.home");
        this.root = Paths.get(home, ".devbridge", "deletes");
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise delete journal directory " + root, e);
        }
    }

    /**
     * Read the most recent delete journal on disk for the given profile.
     * Used by the frontend to poll live progress while a delete is running.
     */
    public java.util.Optional<DeleteJournal> readLatest(String profileId) {
        if (profileId == null || profileId.isBlank()) return java.util.Optional.empty();
        Path dir = root.resolve(profileId);
        if (!Files.isDirectory(dir)) return java.util.Optional.empty();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .flatMap(p -> {
                        try {
                            return java.util.Optional.of(mapper.readValue(p.toFile(), DeleteJournal.class));
                        } catch (IOException e) {
                            log.debug("Failed to read delete journal {}: {}", p, e.getMessage());
                            return java.util.Optional.empty();
                        }
                    });
        } catch (IOException e) {
            log.debug("Failed to list delete journals for {}: {}", profileId, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Write (or overwrite) the given journal to its per-profile file. Returns the absolute path. */
    public Path write(DeleteJournal journal) {
        if (journal == null || journal.profileId() == null || journal.jobId() == null) {
            throw new IllegalArgumentException("journal.profileId and journal.jobId are required");
        }
        Path dir = root.resolve(journal.profileId());
        try {
            Files.createDirectories(dir);
            String stamp;
            try {
                stamp = STAMP.format(journal.startedAt() != null
                        ? journal.startedAt().atZone(ZoneId.systemDefault())
                        : ZonedDateTime.now());
            } catch (DateTimeException e) {
                stamp = STAMP.format(ZonedDateTime.now());
            }
            Path file = dir.resolve(stamp + "-" + journal.jobId() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), journal);
            return file;
        } catch (IOException e) {
            log.warn("Failed to write delete journal for job {}: {}", journal.jobId(), e.getMessage());
            throw new RuntimeException("Failed to persist delete journal", e);
        }
    }
}
