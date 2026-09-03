package com.devbridge.profile;

import org.springframework.stereotype.Component;

/**
 * In-memory holder for the currently active profile ID.
 * Single-user tool → no need for session scope or per-user state.
 * On restart, no profile is active until the user re-activates one.
 */
@Component
public class ActiveProfileHolder {

    private volatile String activeProfileId;

    public String getActiveProfileId() {
        return activeProfileId;
    }

    public void setActiveProfileId(String id) {
        this.activeProfileId = id;
    }

    public void clear() {
        this.activeProfileId = null;
    }
}
