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
}
