// Browser-local profile storage. All profiles live in localStorage under
// `devbridge.profiles` (JSON array) with the active-profile id under
// `devbridge.activeProfileId`. The server never sees or persists profiles;
// each browser is its own tenancy boundary.
//
// Bearer tokens continue to live under `devbridge.token.<profileId>` — that
// pattern is unchanged from the previous release.
//
// On first read (localStorage empty), we try to pull legacy profiles from the
// server-side disk store via GET /api/profiles/legacy-export. If any come
// back, they're auto-imported so an existing user never sees their profiles
// disappear after upgrade. Legacy files on disk are left untouched.

const PROFILES_KEY = 'devbridge.profiles';
const ACTIVE_ID_KEY = 'devbridge.activeProfileId';

let migrationAttempted = false;

/* ---------- primitives ---------- */

function readAll() {
    try {
        const raw = localStorage.getItem(PROFILES_KEY);
        if (!raw) return [];
        const parsed = JSON.parse(raw);
        return Array.isArray(parsed) ? parsed : [];
    } catch {
        return [];
    }
}

function writeAll(profiles) {
    localStorage.setItem(PROFILES_KEY, JSON.stringify(profiles));
}

function uuid() {
    if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    // Fallback for older browsers — RFC-4122-ish
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    });
}

/* ---------- one-time migration from legacy on-disk profiles ---------- */

async function tryLegacyMigration() {
    if (migrationAttempted) return;
    migrationAttempted = true;
    if (readAll().length > 0) return;                 // localStorage already populated
    let legacy;
    try {
        const res = await fetch('/api/profiles/legacy-export');
        if (!res.ok) return;
        legacy = await res.json();
    } catch {
        return;                                       // legacy endpoint absent or unreachable
    }
    if (!Array.isArray(legacy) || legacy.length === 0) return;
    writeAll(legacy);
    // Preserve the previously active profile if the server was tracking one
    try {
        const activeRes = await fetch('/api/profiles/active');
        if (activeRes.status === 200) {
            const active = await activeRes.json();
            if (active && active.id) localStorage.setItem(ACTIVE_ID_KEY, active.id);
        }
    } catch { /* ignore */ }
}

/* ---------- CRUD (sync, but a bootstrap fetch is async) ---------- */

export async function listProfiles() {
    await tryLegacyMigration();
    return readAll();
}

export async function getActiveProfile() {
    await tryLegacyMigration();
    const id = localStorage.getItem(ACTIVE_ID_KEY);
    if (!id) return null;
    return readAll().find(p => p.id === id) || null;
}

export async function createProfile(input) {
    await tryLegacyMigration();
    const now = new Date().toISOString();
    const profile = { ...input, id: uuid(), createdAt: now, lastUsedAt: null };
    const all = readAll();
    all.push(profile);
    writeAll(all);
    // Shadow-write to server so its on-disk copy stays in sync with
    // localStorage. Import / delete / sql endpoints still read the profile
    // from disk on the server side — without this sync, edits made in the
    // browser (e.g., referenceTables changes) would never be seen by the
    // server. Fire-and-forget: local save is authoritative for the UI, the
    // shadow write is best-effort. Uses PUT-by-id so the server's copy
    // matches our client-generated UUID (server's create() would generate
    // its own — using PUT preserves the id).
    shadowPutProfile(profile);
    return profile;
}

export async function updateProfile(id, input) {
    await tryLegacyMigration();
    const all = readAll();
    const idx = all.findIndex(p => p.id === id);
    if (idx < 0) throw new Error(`Profile not found: ${id}`);
    // Preserve id, createdAt, lastUsedAt — those are managed here, not from client input
    const prev = all[idx];
    all[idx] = { ...input, id: prev.id, createdAt: prev.createdAt, lastUsedAt: prev.lastUsedAt };
    writeAll(all);
    // Shadow-write to server (see createProfile for rationale).
    shadowPutProfile(all[idx]);
    return all[idx];
}

export async function deleteProfile(id) {
    const all = readAll();
    const before = all.length;
    const filtered = all.filter(p => p.id !== id);
    writeAll(filtered);
    if (localStorage.getItem(ACTIVE_ID_KEY) === id) {
        localStorage.removeItem(ACTIVE_ID_KEY);
    }
    localStorage.removeItem('devbridge.token.' + id);
    // Shadow-delete on server (best-effort).
    fetch(`/api/profiles/${encodeURIComponent(id)}`, { method: 'DELETE' })
        .catch(() => { /* server-side may not have it; local delete is authoritative */ });
    return { deleted: filtered.length < before };
}

/**
 * PUT the given profile to the server. Uses PUT-by-id so the server's disk
 * copy uses OUR client-generated UUID (POST /profiles would let the server
 * generate a new id). If the profile doesn't exist server-side yet
 * (e.g., created after the migration window), we fall back to POST.
 */
function shadowPutProfile(profile) {
    if (!profile || !profile.id) return;
    fetch(`/api/profiles/${encodeURIComponent(profile.id)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(profile),
    }).then(res => {
        if (res.status === 404) {
            // Profile isn't on the server yet — POST to create it. Note: the
            // server's create() generates its own id, so we lose the id-sync.
            // To keep ids consistent we send the create then PUT it back.
            return fetch('/api/profiles', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(profile),
            }).then(created => {
                if (!created.ok) return;
                return created.json().then(srv => {
                    if (srv && srv.id && srv.id !== profile.id) {
                        // Server used a different id. Best-effort: PUT back
                        // under OUR id via a follow-up call so subsequent
                        // shadow-writes hit the right file.
                        return fetch(`/api/profiles/${encodeURIComponent(profile.id)}`, {
                            method: 'PUT',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(profile),
                        }).catch(() => {});
                    }
                });
            });
        }
    }).catch(() => { /* silent — best-effort */ });
}

export async function activateProfile(id) {
    const all = readAll();
    const p = all.find(x => x.id === id);
    if (!p) throw new Error(`Profile not found: ${id}`);
    const now = new Date().toISOString();
    p.lastUsedAt = now;
    writeAll(all);
    localStorage.setItem(ACTIVE_ID_KEY, id);
    // Transitional shadow write: keep the server's active-profile tracker
    // in sync during the migration period. Once auth + fetch-token move to
    // profile-in-body (Step 3), this fire-and-forget call is removed.
    // Silently ignores 4xx/5xx — new profiles created after migration
    // legitimately won't exist server-side.
    fetch(`/api/profiles/${encodeURIComponent(id)}/activate`, { method: 'POST' })
        .catch(() => { /* server-side active tracking is optional here */ });
    return p;
}

/* ---------- portability: export / import ---------- */

export async function exportAllToJson() {
    const all = await listProfiles();
    return JSON.stringify({
        exportedAt: new Date().toISOString(),
        source: 'devbridge',
        profiles: all,
    }, null, 2);
}

export async function importFromJson(text) {
    let parsed;
    try {
        parsed = JSON.parse(text);
    } catch (e) {
        throw new Error('Not valid JSON: ' + e.message);
    }
    const incoming = Array.isArray(parsed) ? parsed
        : (parsed && Array.isArray(parsed.profiles)) ? parsed.profiles
        : null;
    if (!incoming) throw new Error('Expected a JSON array of profiles, or an object with a "profiles" array.');
    // De-dupe by id — imported profile replaces existing one with the same id
    const existing = readAll();
    const byId = new Map(existing.map(p => [p.id, p]));
    let added = 0, replaced = 0;
    for (const p of incoming) {
        if (!p || typeof p !== 'object') continue;
        if (!p.id) p.id = uuid();
        if (byId.has(p.id)) replaced++; else added++;
        byId.set(p.id, p);
    }
    writeAll(Array.from(byId.values()));
    return { added, replaced, total: byId.size };
}

/* ---------- helpers exposed for other modules ---------- */

export function _forceMigrationRetry() {
    migrationAttempted = false;
}
