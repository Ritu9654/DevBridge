package com.devbridge.api;

import com.devbridge.fawb.TestConnectionResult;
import com.devbridge.fawb.TestConnectionService;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final ProfileService service;
    private final TestConnectionService testConnectionService;

    public ProfileController(ProfileService service, TestConnectionService testConnectionService) {
        this.service = service;
        this.testConnectionService = testConnectionService;
    }

    @GetMapping
    public List<ProjectProfile> list() {
        return service.list();
    }

    @GetMapping("/active")
    public ResponseEntity<ProjectProfile> active() {
        return service.getActive()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    public ProjectProfile create(@RequestBody ProjectProfile input) {
        return service.create(input);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProjectProfile> update(@PathVariable String id, @RequestBody ProjectProfile input) {
        return service.update(id, input)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable String id) {
        return Map.of("deleted", service.delete(id));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ProjectProfile> activate(@PathVariable String id) {
        return service.activate(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/test-connection")
    public ResponseEntity<List<TestConnectionResult>> testConnection(@PathVariable String id) {
        return service.findById(id)
                .map(profile -> ResponseEntity.ok(testConnectionService.test(profile)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Stateless test-connection: the client sends the full profile in
     * the body. Server does the network probe but doesn't need to have
     * the profile stored on disk. This is the path used after profiles
     * moved to localStorage.
     */
    @PostMapping("/test-connection")
    public List<TestConnectionResult> testConnectionByBody(@RequestBody TestConnectionRequest req) {
        return testConnectionService.test(req.profile());
    }

    public record TestConnectionRequest(ProjectProfile profile) {}

    /**
     * Legacy on-disk profiles export for one-time client migration to
     * browser localStorage. Read-only, never modifies anything. New
     * profiles created after the localStorage cutover will never appear
     * here. Client calls this on first boot when its localStorage is
     * empty; if this endpoint returns a non-empty array, it copies them
     * in and never asks again.
     */
    @GetMapping("/legacy-export")
    public List<ProjectProfile> legacyExport() {
        return service.list();
    }
}
