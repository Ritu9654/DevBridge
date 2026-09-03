package com.devbridge.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * JSON-file-per-profile storage under %USERPROFILE%\.devbridge\profiles\.
 * Loads everything into memory on startup; writes back to disk on save/delete.
 */
@Repository
public class ProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(ProfileRepository.class);

    private final Path storageDir;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, ProjectProfile> cache = new ConcurrentHashMap<>();

    public ProfileRepository(ObjectMapper mapper) {
        this.mapper = mapper;
        String home = System.getProperty("user.home");
        this.storageDir = Paths.get(home, ".devbridge", "profiles");
        try {
            Files.createDirectories(this.storageDir);
            loadAll();
            log.info("Loaded {} profile(s) from {}", cache.size(), storageDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialise profile storage at " + storageDir, e);
        }
    }

    private void loadAll() throws IOException {
        try (Stream<Path> files = Files.list(storageDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::loadOne);
        }
    }

    private void loadOne(Path path) {
        try {
            ProjectProfile p = mapper.readValue(path.toFile(), ProjectProfile.class);
            if (p.id() != null) {
                cache.put(p.id(), p);
            }
        } catch (IOException e) {
            log.warn("Skipping unreadable profile file: {}", path, e);
        }
    }

    public List<ProjectProfile> findAll() {
        return new ArrayList<>(cache.values());
    }

    public Optional<ProjectProfile> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    public ProjectProfile save(ProjectProfile p) {
        cache.put(p.id(), p);
        try {
            Path file = storageDir.resolve(p.id() + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), p);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save profile: " + p.id(), e);
        }
        return p;
    }

    public boolean deleteById(String id) {
        ProjectProfile removed = cache.remove(id);
        if (removed == null) return false;
        try {
            Files.deleteIfExists(storageDir.resolve(id + ".json"));
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete profile: " + id, e);
        }
    }

    public Path getStorageDir() {
        return storageDir;
    }
}
