package com.devbridge.profile;

import com.devbridge.auth.AuthTokenHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProfileService {

    private final ProfileRepository repo;
    private final ActiveProfileHolder active;
    private final AuthTokenHolder tokenHolder;

    public ProfileService(ProfileRepository repo, ActiveProfileHolder active, AuthTokenHolder tokenHolder) {
        this.repo = repo;
        this.active = active;
        this.tokenHolder = tokenHolder;
    }

    public List<ProjectProfile> list() {
        return repo.findAll();
    }

    public Optional<ProjectProfile> findById(String id) {
        return repo.findById(id);
    }

    public ProjectProfile create(ProjectProfile input) {
        String id = UUID.randomUUID().toString();
        ProjectProfile withId = input.withIdAndTimestamps(id, Instant.now(), null);
        return repo.save(withId);
    }

    /**
     * Update an existing profile. Preserves the id, createdAt, and lastUsedAt
     * — those are managed by the service, not sent from the client.
     * Returns Optional.empty() if no profile exists with that id.
     */
    public Optional<ProjectProfile> update(String id, ProjectProfile input) {
        Optional<ProjectProfile> existing = repo.findById(id);
        if (existing.isEmpty()) return existing;
        ProjectProfile prev = existing.get();
        ProjectProfile updated = new ProjectProfile(
                prev.id(),
                input.name(),
                input.sandboxBaseUrl(),
                input.designBaseUrl(),
                input.dbServiceName(),
                input.sqlDbName(),
                input.sandboxSqlDbName(),
                input.tokenUrl(),
                input.tokenClientId(),
                input.tokenClientSecret(),
                input.facadeServicePath(),
                input.facadeEndpoint(),
                input.facadeQueryParam(),
                input.rootTableName(),
                input.rootPkFilterColumn(),
                input.dataModelJsonPath(),
                input.facadeKeyOverrides(),
                input.referenceTables(),
                input.referenceTableConfigs(),
                input.csvOnlyTables(),
                input.nestedInsertConfig(),
                input.virtualForeignKeys(),
                prev.createdAt(),
                prev.lastUsedAt()
        );
        return Optional.of(repo.save(updated));
    }

    public boolean delete(String id) {
        if (id.equals(active.getActiveProfileId())) {
            active.clear();
        }
        tokenHolder.clear(id);  // remove any in-memory token slot for this profile
        return repo.deleteById(id);
    }

    public Optional<ProjectProfile> activate(String id) {
        Optional<ProjectProfile> profile = repo.findById(id);
        profile.ifPresent(p -> {
            active.setActiveProfileId(id);
            ProjectProfile updated = p.withIdAndTimestamps(p.id(), p.createdAt(), Instant.now());
            repo.save(updated);
        });
        return profile;
    }

    public Optional<ProjectProfile> getActive() {
        String id = active.getActiveProfileId();
        if (id == null) return Optional.empty();
        return repo.findById(id);
    }
}
