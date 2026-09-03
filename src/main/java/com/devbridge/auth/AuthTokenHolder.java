package com.devbridge.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory per-profile bearer token holder. Keyed by profile ID so that
 * profiles pointing at different regions / tenants can hold different tokens
 * simultaneously. Cleared on app restart. Never persisted to disk.
 * <p>
 * The token itself is only reachable via {@link #getToken(String)} for
 * internal HTTP clients. The REST API surface only reveals a boolean
 * "set / not set" status per profile, never a value.
 */
@Component
public class AuthTokenHolder {

    private final ConcurrentMap<String, String> tokens = new ConcurrentHashMap<>();

    public String getToken(String profileId) {
        if (profileId == null) return null;
        return tokens.get(profileId);
    }

    public void setToken(String profileId, String token) {
        if (profileId == null || token == null || token.isBlank()) return;
        tokens.put(profileId, token.trim());
    }

    public void clear(String profileId) {
        if (profileId == null) return;
        tokens.remove(profileId);
    }

    public void clearAll() {
        tokens.clear();
    }

    public boolean isSet(String profileId) {
        String t = getToken(profileId);
        return t != null && !t.isBlank();
    }
}
