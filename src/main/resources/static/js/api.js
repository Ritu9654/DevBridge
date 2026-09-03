// Thin wrapper around fetch() for calls to our Spring Boot backend.
// All backend endpoints live under /api.

const BASE = '/api';

async function request(path, options = {}) {
    const res = await fetch(`${BASE}${path}`, {
        headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
        ...options,
    });
    if (res.status === 204) return null;
    if (!res.ok) {
        // Any 401 from any endpoint → token is bad. Dispatch globally so main.js
        // clears localStorage, refreshes the sidebar, opens the paste modal.
        if (res.status === 401) {
            window.dispatchEvent(new CustomEvent('token-expired'));
        }
        // Prefer a friendly error field from the body if the backend returned JSON.
        const bodyText = await res.text();
        let message = `${res.status} ${res.statusText}`;
        try {
            const parsed = JSON.parse(bodyText);
            if (parsed && typeof parsed.error === 'string' && parsed.error.trim()) {
                message = parsed.error;
            } else if (parsed && typeof parsed.message === 'string' && parsed.message.trim()) {
                message = parsed.message;
            } else {
                message = `${res.status} ${res.statusText}: ${bodyText}`;
            }
        } catch {
            // Body isn't JSON — fall back to the raw text
            if (bodyText) message = `${res.status} ${res.statusText}: ${bodyText}`;
        }
        throw new Error(message);
    }
    const contentType = res.headers.get('content-type') || '';
    return contentType.includes('application/json') ? res.json() : res.text();
}

/* Health */
export function getHealth() {
    return request('/health');
}

/* Auth token */
const TOKEN_STORAGE_PREFIX = 'devbridge.token.';

export function getAuthStatus() {
    return request('/auth/status');
}
export async function setAuthToken(token) {
    const result = await request('/auth/token', {
        method: 'POST',
        body: JSON.stringify({ token }),
    });
    if (result && result.profileId) {
        localStorage.setItem(TOKEN_STORAGE_PREFIX + result.profileId, token);
    }
    return result;
}
export async function clearAuthToken() {
    const result = await request('/auth/token', { method: 'DELETE' });
    if (result && result.profileId) {
        localStorage.removeItem(TOKEN_STORAGE_PREFIX + result.profileId);
    }
    return result;
}
/** Auto-fetch a fresh token using OAuth2 credentials configured on the profile.
 *  Also persists the fetched token to localStorage so refresh/devtools restart preserve it. */
export async function fetchAuthToken() {
    const result = await request('/auth/fetch-token', { method: 'POST' });
    if (result && result.profileId && result.token) {
        localStorage.setItem(TOKEN_STORAGE_PREFIX + result.profileId, result.token);
    }
    return result;
}

/** Read a stored token from localStorage — used at page load / page refresh
 *  to re-hydrate the backend's in-memory holder after devtools restarts. */
export function getStoredToken(profileId) {
    if (!profileId) return null;
    return localStorage.getItem(TOKEN_STORAGE_PREFIX + profileId);
}

/** Wipe the localStorage entry for a profile (e.g., when token has expired
 *  and we don't want to auto-restore it on next page load). */
export function clearStoredToken(profileId) {
    if (!profileId) return;
    localStorage.removeItem(TOKEN_STORAGE_PREFIX + profileId);
}

/** Push a token directly to the backend without touching localStorage.
 *  Used during page-load token restore so we don't loop storage → backend → storage. */
export async function restoreTokenToBackend(profileId, token) {
    if (!profileId || !token) return;
    await fetch(`/api/auth/token?profileId=${encodeURIComponent(profileId)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
    });
}

/* Profiles */
export function listProfiles() {
    return request('/profiles');
}
export function getActiveProfile() {
    return request('/profiles/active'); // returns null on 204
}
export function createProfile(profile) {
    return request('/profiles', { method: 'POST', body: JSON.stringify(profile) });
}
export function updateProfile(id, profile) {
    return request(`/profiles/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(profile),
    });
}
export function deleteProfile(id) {
    return request(`/profiles/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
export function activateProfile(id) {
    return request(`/profiles/${encodeURIComponent(id)}/activate`, { method: 'POST' });
}
export function testConnection(id) {
    return request(`/profiles/${encodeURIComponent(id)}/test-connection`, { method: 'POST' });
}

/* SQL execution */
/**
 * Uses raw fetch (not the request() wrapper) so we can return the backend's
 * structured error body — including requestUrl and the FAWB response body —
 * on non-2xx responses, instead of throwing and losing that context.
 */
export async function executeSql({ env, sql }) {
    const res = await fetch(`${BASE}/sql/execute`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ env, sql }),
    });
    const text = await res.text();
    let parsed = null;
    try { parsed = text ? JSON.parse(text) : null; } catch { /* leave null */ }

    if (res.ok) {
        return parsed || { success: true, status: res.status };
    }

    // Non-2xx: prefer the structured backend body verbatim so requestUrl / body reach the UI.
    if (parsed && typeof parsed === 'object') {
        // Make sure the caller sees success:false even if the backend forgot to set it.
        if (parsed.success !== false) parsed.success = false;
        if (parsed.status == null) parsed.status = res.status;
        return parsed;
    }
    return {
        success: false,
        status: res.status,
        error: text || `${res.status} ${res.statusText}`,
    };
}

/* App import (Module 2) — SQL-driven FK-graph walk */
export function planImport({ appId, lookupColumn, sourceEnv, targetEnv } = {}) {
    const body = {};
    if (appId) body.appId = appId;
    if (lookupColumn) body.lookupColumn = lookupColumn;
    if (sourceEnv) body.sourceEnv = sourceEnv;
    if (targetEnv) body.targetEnv = targetEnv;
    return request('/apps/import-plan', {
        method: 'POST',
        body: JSON.stringify(body),
    });
}

/**
 * Execute an import. WRITES to the target env — requires explicit confirm + confirmToken (must match appId).
 * Returns the final journal. Pre-import auto-cleanup runs before the insert phase, using the
 * same lookupColumn to identify (and delete) any existing target-side instance.
 */
export function executeImport({ appId, lookupColumn, sourceEnv, targetEnv, confirm, confirmToken } = {}) {
    const body = {
        appId, sourceEnv, targetEnv,
        confirm: !!confirm,
        confirmToken,
    };
    if (lookupColumn) body.lookupColumn = lookupColumn;
    return request('/apps/execute', {
        method: 'POST',
        body: JSON.stringify(body),
    });
}

/* App delete (Module 3-adjacent) — reverse FK-graph walk in sandbox */
export function planDelete({ appId, lookupColumn } = {}) {
    const body = {};
    if (appId) body.appId = appId;
    if (lookupColumn) body.lookupColumn = lookupColumn;
    return request('/apps/delete-plan', {
        method: 'POST',
        body: JSON.stringify(body),
    });
}

/**
 * Execute a delete. REMOVES rows from sandbox — irreversible. Requires
 * explicit confirm + confirmToken (must match appId). Returns the final journal.
 * Optional lookupColumn selects which root-table column to filter by
 * (e.g. "applicationNumber"); defaults to the root PK when unset.
 */
export function executeDelete({ appId, lookupColumn, confirm, confirmToken } = {}) {
    const body = { appId, confirm: !!confirm, confirmToken };
    if (lookupColumn) body.lookupColumn = lookupColumn;
    return request('/apps/execute-delete', {
        method: 'POST',
        body: JSON.stringify(body),
    });
}

/* DataModel (per profile) */
export function getDataModelSummary(profileId) {
    return request(`/profiles/${encodeURIComponent(profileId)}/data-model`);
}
export async function uploadDataModel(profileId, file) {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`${BASE}/profiles/${encodeURIComponent(profileId)}/data-model`, {
        method: 'POST',
        body: form,   // do NOT set Content-Type; browser sets multipart boundary
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({ error: `HTTP ${res.status}` }));
        throw new Error(err.error || `HTTP ${res.status}`);
    }
    return res.json();
}
export function deleteDataModel(profileId) {
    return request(`/profiles/${encodeURIComponent(profileId)}/data-model`, { method: 'DELETE' });
}
export function listDataModelTables(profileId) {
    return request(`/profiles/${encodeURIComponent(profileId)}/data-model/tables`);
}
export function getDataModelTable(profileId, tableName) {
    return request(`/profiles/${encodeURIComponent(profileId)}/data-model/tables/${encodeURIComponent(tableName)}`);
}
export function getDataModelGraph(profileId) {
    return request(`/profiles/${encodeURIComponent(profileId)}/data-model/graph`);
}
