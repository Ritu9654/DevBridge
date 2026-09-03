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
