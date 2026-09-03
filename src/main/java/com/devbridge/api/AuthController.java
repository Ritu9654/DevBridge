package com.devbridge.api;

import com.devbridge.auth.AuthTokenHolder;
import com.devbridge.auth.TokenFetchService;
import com.devbridge.profile.ActiveProfileHolder;
import com.devbridge.profile.ProfileService;
import com.devbridge.profile.ProjectProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * All endpoints target one profile's token slot. Callers can pass
 * {@code ?profileId=...} explicitly; otherwise the active profile is used.
 * If no active profile and no explicit profileId, returns 400.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthTokenHolder holder;
    private final ActiveProfileHolder activeProfileHolder;
    private final ProfileService profileService;
    private final TokenFetchService tokenFetchService;

    public AuthController(AuthTokenHolder holder,
                          ActiveProfileHolder activeProfileHolder,
                          ProfileService profileService,
                          TokenFetchService tokenFetchService) {
        this.holder = holder;
        this.activeProfileHolder = activeProfileHolder;
        this.profileService = profileService;
        this.tokenFetchService = tokenFetchService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestParam(required = false) String profileId) {
        String id = resolve(profileId);
        if (id == null) {
            return ResponseEntity.ok(Map.of("set", false, "profileId", ""));
        }
        return ResponseEntity.ok(Map.of("set", holder.isSet(id), "profileId", id));
    }

    @PostMapping("/token")
    public ResponseEntity<?> setToken(@RequestParam(required = false) String profileId,
                                     @RequestBody TokenRequest req) {
        String id = resolve(profileId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "no profile targeted — pass ?profileId=... or activate a profile first"));
        }
        if (req == null || req.token() == null || req.token().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token must not be blank"));
        }
        holder.setToken(id, req.token());
        return ResponseEntity.ok(Map.of("set", true, "profileId", id));
    }

    @DeleteMapping("/token")
    public ResponseEntity<?> clear(@RequestParam(required = false) String profileId) {
        String id = resolve(profileId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "no profile targeted — pass ?profileId=... or activate a profile first"));
        }
        holder.clear(id);
        return ResponseEntity.ok(Map.of("set", false, "profileId", id));
    }

    /**
     * Fetch a fresh WM_AUTH_TOKEN using the OAuth2 credentials configured on
     * the profile (tokenUrl + tokenClientId + tokenClientSecret). Stores it
     * in the holder on success.
     */
    @PostMapping("/fetch-token")
    public ResponseEntity<?> fetchToken(@RequestParam(required = false) String profileId) {
        String id = resolve(profileId);
        if (id == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "no profile targeted — pass ?profileId=... or activate a profile first"));
        }
        Optional<ProjectProfile> profile = profileService.findById(id);
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "profile not found: " + id));
        }
        try {
            TokenFetchService.FetchResult result = tokenFetchService.fetch(profile.get());
            holder.setToken(id, result.token());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("set", true);
            body.put("profileId", id);
            if (result.expiresInSeconds() != null) body.put("expiresIn", result.expiresInSeconds());
            body.put("token", result.token());  // echoed so the frontend can persist to localStorage
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Token fetch failed for profile {}", id, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getClass().getSimpleName() + ": "
                            + (e.getMessage() != null ? e.getMessage() : "(no detail)")));
        }
    }

    private String resolve(String explicit) {
        if (explicit != null && !explicit.isBlank()) return explicit;
        return activeProfileHolder.getActiveProfileId();
    }

    public record TokenRequest(String token) {}
}
