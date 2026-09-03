package com.devbridge.api;

import com.devbridge.auth.AuthTokenHolder;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import com.devbridge.sql.SqlService;
import com.devbridge.sql.SqlService.ExecuteResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private static final Logger log = LoggerFactory.getLogger(SqlController.class);

    private final SqlService sqlService;
    private final ProfileService profileService;
    private final ActiveProfileHolder activeProfileHolder;
    private final AuthTokenHolder tokenHolder;
    private final ObjectMapper mapper;

    public SqlController(SqlService sqlService,
                         ProfileService profileService,
                         ActiveProfileHolder activeProfileHolder,
                         AuthTokenHolder tokenHolder,
                         ObjectMapper mapper) {
        this.sqlService = sqlService;
        this.profileService = profileService;
        this.activeProfileHolder = activeProfileHolder;
        this.tokenHolder = tokenHolder;
        this.mapper = mapper;
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestBody ExecuteRequest req) {
        String profileId = activeProfileHolder.getActiveProfileId();
        if (profileId == null) {
            return ResponseEntity.badRequest().body(errorBody("No active profile — activate one first."));
        }
        Optional<ProjectProfile> profile = profileService.findById(profileId);
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(errorBody("Active profile not found."));
        }
        if (req == null || req.sql() == null || req.sql().isBlank()) {
            return ResponseEntity.badRequest().body(errorBody("SQL text is required."));
        }

        try {
            ExecuteResult res = sqlService.execute(profile.get(), req.env(), req.sql());
            int status = res.status();
            if (status >= 200 && status < 300) {
                JsonNode data = mapper.readTree(res.body());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("status", status);
                body.put("requestUrl", res.url());
                body.put("data", data);
                return ResponseEntity.ok(body);
            }
            if (status == 401) {
                // Bearer expired — clear the slot so the UI can prompt for a refresh.
                tokenHolder.clear(profileId);
                Map<String, Object> body = errorBody(status,
                        "Token expired — paste a fresh WM_AUTH_TOKEN for this profile.");
                body.put("requestUrl", res.url());
                return ResponseEntity.status(status).body(body);
            }
            Map<String, Object> body = errorWithBody(status, "FAWB returned HTTP " + status + ".", res.body());
            body.put("requestUrl", res.url());
            return ResponseEntity.status(status).body(body);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("SQL execution failed", e);
            return ResponseEntity.internalServerError().body(errorBody(
                    e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("error", msg);
        return m;
    }

    private Map<String, Object> errorBody(int status, String msg) {
        Map<String, Object> m = errorBody(msg);
        m.put("status", status);
        return m;
    }

    private Map<String, Object> errorWithBody(int status, String msg, String body) {
        Map<String, Object> m = errorBody(status, msg);
        m.put("body", body);
        return m;
    }

    public record ExecuteRequest(String env, String sql) {}
}
