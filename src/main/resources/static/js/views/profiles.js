import {
    listProfiles,
    createProfile,
    updateProfile,
    deleteProfile,
    activateProfile,
    getActiveProfile,
    testConnection,
    getAuthStatus,
    getDataModelSummary,
    uploadDataModel,
    deleteDataModel,
    exportProfilesToJson,
    importProfilesFromJson,
    getVirtualFkSuggestions,
} from '../api.js';

const FIELDS = [
    { key: 'name', label: 'Profile name', placeholder: 'My Project', required: true },
    { key: 'sandboxBaseUrl', label: 'Sandbox base URL', placeholder: 'https://fawb-nxt.<domain>/run-<id>/<project>',
      help: 'Full URL to your dev sandbox, up to (but not including) /services/... Used for writes.' },
    { key: 'designBaseUrl', label: 'Design base URL', placeholder: 'https://fawb-app-<id>.<domain>',
      help: 'Full URL to the shared design environment. Used for reads.' },
    { key: 'dbServiceName', label: 'DB service name', placeholder: 'e.g. Core, CaseManager',
      help: 'The FAWB JPA service name — appears in entity CRUD paths as /services/<this>/<Entity>. Distinct from the DB schema name; for Core they happen to coincide. Used by import writes and (as fallback) by design SQL.' },
    { key: 'sqlDbName', label: 'Design SQL DB name (optional)', placeholder: 'Blank = use DB service name',
      help: 'The value the SQL runner sends as ?db=<this> for the DESIGN env. For older projects like Core, leave blank (falls back to DB service name). For per-user-schema projects (e.g. TFG), set to the schema name like "usrzzifdsi3l".' },
    { key: 'sandboxSqlDbName', label: 'Sandbox SQL DB name (optional)', placeholder: 'Blank = use DB service name',
      help: 'The value the SQL runner sends as ?db=<this> for the SANDBOX env. Sandbox usually provisions its own DB name — e.g. "CaseManagerJ17__1". Different from the design value above; both fall back to DB service name if blank.' },
    { key: 'tokenUrl', label: 'Token URL', placeholder: 'https://…/oauth2/token',
      help: 'OAuth2 token endpoint. When set together with client ID + secret below, DevBridge fetches a fresh WM_AUTH_TOKEN on demand — no more manual paste every 30 min.' },
    { key: 'tokenClientId', label: 'Token client ID', placeholder: 'your-client-id',
      help: 'OAuth2 client ID for the token endpoint.' },
    { key: 'tokenClientSecret', label: 'Token client secret', placeholder: '••••••••', type: 'password',
      help: 'OAuth2 client secret. Stored in the profile JSON on disk (%USERPROFILE%\\.devbridge\\profiles\\) — never committed to git.' },
    { key: 'rootTableName', label: 'Root table name', placeholder: 'e.g. CASE_HEADER, APPLICATION',
      help: 'DB table the import walker starts from. All downstream tables and their insert order are derived from the FK graph automatically. Required for Import App.' },
];

const AVATAR_PALETTE = [
    '#4f46e5', '#0891b2', '#059669', '#dc2626',
    '#d97706', '#9333ea', '#db2777', '#0284c7',
    '#7c3aed', '#0d9488', '#e11d48', '#65a30d',
];

let profilesCache = [];
let editingProfileId = null;

export const profilesView = {
    title: 'Project Profiles',
    render() {
        return `
            <div class="profiles-header">
                <div class="profiles-header-text">
                    <h2>Project Profiles</h2>
                    <p>One profile per project. Only <strong>Profile name</strong> is required to save. Click any inactive card to activate — the active profile drives every module.</p>
                </div>
                <div class="section-header-actions profiles-header-actions">
                    <button class="btn secondary" id="btn-import-profiles" title="Import profiles from a JSON file">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                        Import
                    </button>
                    <button class="btn secondary" id="btn-export-profiles" title="Download all profiles as a JSON file (includes OAuth secrets — treat as sensitive)">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                        Export all
                    </button>
                    <button class="btn" id="btn-show-add">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                        Add profile
                    </button>
                </div>
            </div>
            <p class="form-help">
                Profiles live in <strong>this browser only</strong> — nothing is stored on the server.
                Use <strong>Export</strong> to back them up or hand them to a teammate.
                The export file includes OAuth client secrets when set on the profile;
                treat it like a password.
            </p>

            <section id="profiles-status-section" aria-live="polite">
                <div class="home-status-panel" id="profiles-status-panel">
                    ${renderStatusSkeleton()}
                </div>
            </section>

            <h3 class="profiles-list-heading">Existing profiles</h3>
            <div id="profiles-list"></div>

            <div class="add-form-wrapper hidden" id="add-form-wrapper">
                <h3 id="form-title">New profile</h3>
                <form id="add-profile-form" class="profile-form">
                    ${FIELDS.map(renderField).join('')}

                    <div class="form-section" id="datamodel-section" hidden>
                        <hr class="form-divider">
                        <div class="form-section-title">
                            <h4>Data model</h4>
                            <span id="form-dm-status" class="dm-status dm-loading">Checking…</span>
                        </div>
                        <p class="form-help">Upload the FAWB-generated <code>*_published_dataModel.json</code> for this project. Required for Import App.</p>
                        <div class="form-section-actions">
                            <button type="button" class="btn secondary btn-sm" id="btn-upload-dm">
                                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                                    <polyline points="17 8 12 3 7 8"/>
                                    <line x1="12" y1="3" x2="12" y2="15"/>
                                </svg>
                                <span id="btn-upload-dm-label">Upload dataModel</span>
                            </button>
                            <button type="button" class="btn ghost btn-sm" id="btn-remove-dm" hidden>Remove</button>
                        </div>
                    </div>

                    <div class="form-section">
                        <hr class="form-divider">
                        <div class="form-section-title">
                            <h4>Reference / setup tables <span class="hint-inline">(never written)</span></h4>
                        </div>
                        <p class="form-help">
                            Comma-separated list of entity or table names that should <strong>never</strong> be written to
                            during import. Common examples: <code>DomainValue</code>, <code>Locale</code>, <code>Permission</code>.
                            FK values referencing these tables are passed through unchanged — assumes the sandbox already
                            has the same reference data with the same IDs.
                        </p>
                        <textarea id="field-referenceTables" name="referenceTables" class="json-overrides-input"
                                  rows="2" spellcheck="false"
                                  placeholder="DomainValue, Locale, Permission"></textarea>
                    </div>

                    <div class="form-section" id="virtual-fk-section" hidden>
                        <hr class="form-divider">
                        <div class="form-section-title">
                            <h4>Virtual foreign keys <span class="hint-inline">(non-FK columns holding reference ids)</span></h4>
                            <button type="button" class="btn ghost btn-sm" id="btn-suggest-vfks">Suggest candidates</button>
                        </div>
                        <p class="form-help">
                            One entry per line, format: <code>Table.Column -&gt; RefTable</code>. Use this for columns
                            that logically hold ids from a reference table (usually <code>DomainValue</code>) but are
                            <strong>not</strong> declared as FKs in the dataModel — DevBridge treats them exactly like
                            real FKs at import time, remapping source ids to target ids. Fixes "domain value not found"
                            validation errors on submit in the local env. Requires the dataModel to be uploaded first;
                            the target table should also appear in Reference / setup tables above.
                        </p>
                        <textarea id="field-virtualForeignKeys" name="virtualForeignKeys" class="json-overrides-input"
                                  rows="3" spellcheck="false"
                                  placeholder="Application.StatusId -> DomainValue&#10;CasePayload.ReasonCode -> DomainValue"></textarea>
                        <div id="vfk-suggestions-panel" class="vfk-suggestions-panel" hidden></div>
                    </div>

                    <div class="form-actions">
                        <button type="submit" class="btn" id="btn-submit">Save profile</button>
                        <button type="button" class="btn secondary" id="btn-cancel-add">Cancel</button>
                    </div>
                </form>
            </div>
        `;
    },
    async mount() {
        await refreshList();
        refreshStatusAndContext().catch(() => {});
        document.getElementById('btn-show-add').addEventListener('click', () => {
            resetFormMode();
            document.getElementById('add-form-wrapper').classList.remove('hidden');
            document.getElementById('field-name').focus();
        });
        document.getElementById('btn-cancel-add').addEventListener('click', () => {
            document.getElementById('add-form-wrapper').classList.add('hidden');
            document.getElementById('add-profile-form').reset();
            resetFormMode();
        });
        document.getElementById('add-profile-form').addEventListener('submit', onSubmit);
        document.getElementById('btn-export-profiles').addEventListener('click', handleExportAll);
        document.getElementById('btn-import-profiles').addEventListener('click', handleImportClick);

        // DataModel upload / remove buttons inside the edit form
        document.getElementById('btn-upload-dm').addEventListener('click', async (e) => {
            if (!editingProfileId) return;
            await handleUploadDataModel(editingProfileId, e.currentTarget);
        });
        document.getElementById('btn-remove-dm').addEventListener('click', async () => {
            if (!editingProfileId) return;
            if (!confirm('Remove the uploaded dataModel for this profile?')) return;
            try {
                await deleteDataModel(editingProfileId);
                (window.showToast || alert)('DataModel removed.', 'info');
                await refreshFormDataModel(editingProfileId);
                refreshStatusAndContext().catch(() => {});
            } catch (err) {
                (window.showToast || alert)('Remove failed: ' + err.message, 'error');
            }
        });

        // Suggest candidates for virtualForeignKeys (name-heuristic scan of dataModel)
        document.getElementById('btn-suggest-vfks').addEventListener('click', async (e) => {
            if (!editingProfileId) return;
            await handleSuggestVirtualFks(editingProfileId, e.currentTarget);
        });
    },
};

function renderField(f) {
    const inputType = f.type || 'text';
    const autocomplete = inputType === 'password' ? 'new-password' : 'off';
    return `
        <div class="form-row">
            <label for="field-${f.key}">${f.label}${f.required ? ' *' : ''}</label>
            <input type="${inputType}" id="field-${f.key}" name="${f.key}"
                   placeholder="${f.placeholder}" autocomplete="${autocomplete}"${f.required ? ' required' : ''}>
            ${f.help ? `<p class="form-help">${f.help}</p>` : ''}
        </div>
    `;
}

async function onSubmit(e) {
    e.preventDefault();
    const form = e.target;
    const data = Object.fromEntries(new FormData(form).entries());
    // Trim whitespace from all string values — copy-pasted URLs often have trailing spaces
    // that break URL construction downstream.
    for (const k of Object.keys(data)) {
        if (typeof data[k] === 'string') data[k] = data[k].trim();
        if (data[k] === '') delete data[k];
    }

    // referenceTables comes in as comma-separated text. Parse to array of trimmed non-empty strings.
    if (data.referenceTables) {
        const list = String(data.referenceTables)
            .split(/[,\n]/)
            .map(s => s.trim())
            .filter(s => s.length > 0);
        if (list.length === 0) delete data.referenceTables;
        else data.referenceTables = list;
    } else {
        delete data.referenceTables;
    }

    // virtualForeignKeys — one entry per line/comma, format "Table.Column -> RefTable".
    // Silently drops malformed lines rather than blocking save; the profile stays saveable
    // even if the user is mid-edit. Bad lines are logged to console for visibility.
    if (data.virtualForeignKeys) {
        const parsed = parseVirtualForeignKeys(String(data.virtualForeignKeys));
        if (parsed.length === 0) delete data.virtualForeignKeys;
        else data.virtualForeignKeys = parsed;
    } else {
        delete data.virtualForeignKeys;
    }

    try {
        if (editingProfileId) {
            await updateProfile(editingProfileId, data);
        } else {
            await createProfile(data);
        }
        form.reset();
        document.getElementById('add-form-wrapper').classList.add('hidden');
        resetFormMode();
        await refreshList();
        window.dispatchEvent(new CustomEvent('profile-changed'));
        (window.showToast || alert)('Profile saved.', 'success');
    } catch (err) {
        (window.showToast || alert)('Failed: ' + err.message, 'error');
    }
}

function resetFormMode() {
    editingProfileId = null;
    const title = document.getElementById('form-title');
    const submitBtn = document.getElementById('btn-submit');
    if (title) title.textContent = 'New profile';
    if (submitBtn) submitBtn.textContent = 'Save profile';
    // Hide dataModel section — only visible when editing an existing profile
    const dmSection = document.getElementById('datamodel-section');
    if (dmSection) dmSection.hidden = true;
    // Hide virtualForeignKeys section — dataModel-driven suggestions only make sense when editing
    const vfkSection = document.getElementById('virtual-fk-section');
    if (vfkSection) vfkSection.hidden = true;
    const vfkPanel = document.getElementById('vfk-suggestions-panel');
    if (vfkPanel) { vfkPanel.hidden = true; vfkPanel.innerHTML = ''; }
}

function startEdit(profile) {
    editingProfileId = profile.id;
    const form = document.getElementById('add-profile-form');
    FIELDS.forEach(f => {
        const input = form.querySelector(`[name="${f.key}"]`);
        if (input) input.value = profile[f.key] != null ? profile[f.key] : '';
    });
    // referenceTables is stored as an array — render as comma-separated
    const refInput = form.querySelector('[name="referenceTables"]');
    if (refInput) {
        refInput.value = Array.isArray(profile.referenceTables) && profile.referenceTables.length
            ? profile.referenceTables.join(', ')
            : '';
    }
    // virtualForeignKeys is stored as an array of {sourceTable, sourceColumn, targetTable} —
    // render as one "Table.Column -> RefTable" line per entry
    const vfkInput = form.querySelector('[name="virtualForeignKeys"]');
    if (vfkInput) {
        vfkInput.value = renderVirtualForeignKeys(profile.virtualForeignKeys);
    }
    document.getElementById('form-title').textContent = `Edit profile — ${profile.name || '(unnamed)'}`;
    document.getElementById('btn-submit').textContent = 'Save changes';

    // Reveal dataModel section and load current status
    const dmSection = document.getElementById('datamodel-section');
    if (dmSection) dmSection.hidden = false;
    refreshFormDataModel(profile.id).catch(() => {});

    // Reveal virtualForeignKeys section (edit-only) and clear any prior suggestions panel
    const vfkSection = document.getElementById('virtual-fk-section');
    if (vfkSection) vfkSection.hidden = false;
    const vfkPanel = document.getElementById('vfk-suggestions-panel');
    if (vfkPanel) { vfkPanel.hidden = true; vfkPanel.innerHTML = ''; }

    const wrap = document.getElementById('add-form-wrapper');
    wrap.classList.remove('hidden');
    wrap.scrollIntoView({ behavior: 'smooth', block: 'start' });
    document.getElementById('field-name').focus();
}

async function refreshFormDataModel(profileId) {
    const statusEl = document.getElementById('form-dm-status');
    const removeBtn = document.getElementById('btn-remove-dm');
    const uploadLabel = document.getElementById('btn-upload-dm-label');
    if (!statusEl) return;

    statusEl.className = 'dm-status dm-loading';
    statusEl.innerHTML = 'Checking…';

    try {
        const s = await getDataModelSummary(profileId);
        if (s && s.loaded) {
            statusEl.className = 'dm-status dm-loaded';
            statusEl.innerHTML = `<span class="dm-dot"></span>Loaded: <strong>${s.tables}</strong> tables · <strong>${s.columns}</strong> columns · <strong>${s.relations}</strong> relations <span class="dm-hint">· ${formatBytes(s.sizeBytes)}</span>`;
            if (removeBtn) removeBtn.hidden = false;
            if (uploadLabel) uploadLabel.textContent = 'Replace dataModel';
        } else {
            statusEl.className = 'dm-status dm-missing';
            statusEl.innerHTML = `<span class="dm-dot"></span>Not uploaded yet.`;
            if (removeBtn) removeBtn.hidden = true;
            if (uploadLabel) uploadLabel.textContent = 'Upload dataModel';
        }
    } catch {
        statusEl.className = 'dm-status dm-missing';
        statusEl.innerHTML = `<span class="dm-dot"></span>Could not read status.`;
        if (removeBtn) removeBtn.hidden = true;
    }
}

async function refreshList() {
    const [profiles, active] = await Promise.all([listProfiles(), getActiveProfile()]);
    profilesCache = profiles;
    const activeId = active ? active.id : null;

    const listEl = document.getElementById('profiles-list');
    if (!profiles.length) {
        listEl.innerHTML = renderEmptyState();
        const emptyBtn = listEl.querySelector('#btn-empty-add');
        if (emptyBtn) emptyBtn.addEventListener('click', () => document.getElementById('btn-show-add').click());
        return;
    }

    listEl.innerHTML = `<div class="profile-grid">${profiles.map(p => renderCard(p, activeId)).join('')}</div>`;

    listEl.querySelectorAll('.profile-card.clickable').forEach(card => {
        card.addEventListener('click', async (e) => {
            if (e.target.closest('button')) return;
            try {
                await activateProfile(card.dataset.id);
                window.dispatchEvent(new CustomEvent('profile-changed'));
                await refreshList();
            } catch (err) {
                (window.showToast || alert)('Activate failed: ' + err.message, 'error');
            }
        });
    });

    listEl.querySelectorAll('button[data-action]').forEach(btn => {
        btn.addEventListener('click', (e) => handleCardAction(btn, e));
    });
}

function renderEmptyState() {
    return `
        <div class="empty-state">
            <svg class="empty-icon" width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
                <line x1="12" y1="11" x2="12" y2="17"/>
                <line x1="9" y1="14" x2="15" y2="14"/>
            </svg>
            <h3>No profiles yet</h3>
            <p>Create your first project profile to connect DevBridge to your FAWB project.</p>
            <button class="btn" id="btn-empty-add">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                Add profile
            </button>
        </div>
    `;
}

function renderCard(p, activeId) {
    const isActive = p.id === activeId;
    const initials = avatarInitials(p.name || '?');
    const bg = avatarColor(p.name || '?');

    return `
        <div class="card profile-card ${isActive ? 'active' : 'clickable'}"
             data-id="${p.id}"
             ${isActive ? '' : 'title="Click to activate"'}>
            <div class="profile-card-top">
                <div class="profile-avatar" style="background:${bg}">${escapeHtml(initials)}</div>
                <div class="profile-header">
                    <h4>${escapeHtml(p.name || '(unnamed)')}</h4>
                </div>
                ${isActive ? '<span class="badge">Active</span>' : ''}
            </div>
            <div class="profile-card-actions">
                <button class="btn secondary btn-sm" data-action="test" data-id="${p.id}">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="9 11 12 14 22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
                    Test
                </button>
                <button class="btn ghost btn-sm" data-action="edit" data-id="${p.id}">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                    Edit
                </button>
                <button class="btn ghost btn-sm" data-action="export" data-id="${p.id}" title="Download just this profile as JSON (includes OAuth secret if set)">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                    Export
                </button>
                <button class="btn ghost btn-sm" data-action="delete" data-id="${p.id}">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>
                    Delete
                </button>
            </div>
        </div>
    `;
}

// Hidden file input reused across all profile cards for dataModel uploads
function ensureFileInput() {
    let input = document.getElementById('hidden-dm-file-input');
    if (!input) {
        input = document.createElement('input');
        input.type = 'file';
        input.id = 'hidden-dm-file-input';
        input.accept = '.json,application/json';
        input.style.display = 'none';
        document.body.appendChild(input);
    }
    return input;
}

async function handleUploadDataModel(profileId, btn) {
    const input = ensureFileInput();
    input.value = '';   // reset so re-selecting the same file still triggers change

    input.onchange = async () => {
        const file = input.files && input.files[0];
        if (!file) return;
        const originalHtml = btn.innerHTML;
        btn.disabled = true;
        btn.textContent = 'Uploading…';
        try {
            const res = await uploadDataModel(profileId, file);
            (window.showToast || alert)(res.message || 'DataModel uploaded.', 'success');
            // Refresh both places that show status: form (if visible) and top status tiles
            await refreshFormDataModel(profileId);
            refreshStatusAndContext().catch(() => {});
        } catch (err) {
            (window.showToast || alert)('Upload failed: ' + err.message, 'error');
        } finally {
            btn.disabled = false;
            btn.innerHTML = originalHtml;
        }
    };
    input.click();
}

function downloadSingleProfile(profile) {
    const wrapped = {
        exportedAt: new Date().toISOString(),
        source: 'devbridge',
        profiles: [profile],
    };
    const json = JSON.stringify(wrapped, null, 2);
    const safeName = String(profile.name || 'profile').replace(/[^a-zA-Z0-9-_]+/g, '-').replace(/^-+|-+$/g, '') || 'profile';
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `devbridge-${safeName}-${stamp}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    const hasSecret = !!(profile.tokenClientSecret && profile.tokenClientSecret.length);
    const note = hasSecret
        ? ' File contains the OAuth client secret — share only via trusted channels.'
        : ' No OAuth secret set on this profile.';
    (window.showToast || alert)(`Exported "${profile.name || '(unnamed)'}".${note}`, 'success', 6000);
}

async function handleExportAll() {
    try {
        const profiles = await listProfiles();
        if (!profiles.length) {
            (window.showToast || alert)('No profiles to export.', 'info');
            return;
        }
        const json = await exportProfilesToJson();
        const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
        const blob = new Blob([json], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `devbridge-profiles-${stamp}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        (window.showToast || alert)(
            `Exported ${profiles.length} profile${profiles.length === 1 ? '' : 's'}. ` +
            `File contains any OAuth secrets — treat as sensitive.`,
            'success', 7000);
    } catch (err) {
        (window.showToast || alert)('Export failed: ' + err.message, 'error');
    }
}

function handleImportClick() {
    let input = document.getElementById('hidden-profile-import-input');
    if (!input) {
        input = document.createElement('input');
        input.type = 'file';
        input.id = 'hidden-profile-import-input';
        input.accept = '.json,application/json';
        input.style.display = 'none';
        document.body.appendChild(input);
    }
    input.value = '';
    input.onchange = async () => {
        const file = input.files && input.files[0];
        if (!file) return;
        try {
            const text = await file.text();
            const result = await importProfilesFromJson(text);
            await refreshList();
            window.dispatchEvent(new CustomEvent('profile-changed'));
            const parts = [];
            if (result.added) parts.push(`${result.added} added`);
            if (result.replaced) parts.push(`${result.replaced} replaced`);
            const summary = parts.length ? parts.join(', ') : 'no changes';
            (window.showToast || alert)(
                `Import complete — ${summary}. Total in browser: ${result.total}.`,
                'success', 6000);
        } catch (err) {
            (window.showToast || alert)('Import failed: ' + err.message, 'error', 8000);
        }
    };
    input.click();
}

function formatBytes(n) {
    if (!n || n <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    let v = n;
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return `${v.toFixed(v < 10 && i > 0 ? 1 : 0)} ${units[i]}`;
}

async function handleCardAction(btn, e) {
    e.stopPropagation();
    const action = btn.dataset.action;
    const id = btn.dataset.id;
    try {
        if (action === 'edit') {
            const profile = profilesCache.find(p => p.id === id);
            if (profile) startEdit(profile);
        } else if (action === 'delete') {
            if (!confirm('Delete this profile? This does not affect any remote environment.')) return;
            await deleteProfile(id);
            window.dispatchEvent(new CustomEvent('profile-changed'));
            await refreshList();
            (window.showToast || alert)('Profile deleted.', 'info');
        } else if (action === 'test') {
            const profile = profilesCache.find(p => p.id === id);
            if (!profile) throw new Error('Profile not found in browser storage');
            await runTestConnection(profile, btn);
        } else if (action === 'export') {
            const profile = profilesCache.find(p => p.id === id);
            if (!profile) throw new Error('Profile not found in browser storage');
            downloadSingleProfile(profile);
        }
    } catch (err) {
        (window.showToast || alert)(`${action} failed: ${err.message}`, 'error');
    }
}

async function runTestConnection(profile, btn) {
    const card = btn.closest('.profile-card');
    const originalHtml = btn.innerHTML;

    try {
        const auth = await getAuthStatus();
        if (!auth || !auth.set) {
            showTestResults(card, [{ env: 'auth', success: false, statusCode: 0,
                message: 'No token set — opening the token modal…' }]);
            window.dispatchEvent(new CustomEvent('open-token-modal'));
            return;
        }
    } catch { /* fall through */ }

    btn.textContent = 'Testing…';
    btn.disabled = true;
    try {
        const results = await testConnection(profile);
        showTestResults(card, results);
        if (Array.isArray(results) && results.some(r => r.statusCode === 401)) {
            window.dispatchEvent(new CustomEvent('token-expired'));
        }
    } catch (err) {
        showTestResults(card, [{ env: 'error', success: false, statusCode: 0, message: err.message }]);
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHtml;
    }
}

function showTestResults(card, results) {
    const existing = card.querySelector('.test-results');
    if (existing) existing.remove();
    const html = `
        <div class="test-results">
            ${results.map(r => `
                <div class="test-result ${r.success ? 'success' : 'failure'}">
                    <span class="test-result-dot"></span>
                    <span class="test-result-env">${escapeHtml(r.env)}</span>
                    ${r.statusCode ? `<span class="test-result-status">HTTP ${r.statusCode}</span>` : ''}
                    <span class="test-result-msg">${escapeHtml(r.message)}</span>
                </div>
            `).join('')}
        </div>
    `;
    card.insertAdjacentHTML('beforeend', html);
}

/* ---------- Virtual foreign keys ---------- */

/**
 * Parse the textarea contents into a list of {sourceTable, sourceColumn, targetTable}.
 * Format per entry: "Table.Column -> RefTable". Entries can be separated by newlines
 * or commas. Malformed entries are dropped silently (console warn) so a mid-edit save
 * doesn't fail — the user sees the reduced set the next time they open the form.
 */
function parseVirtualForeignKeys(text) {
    const out = [];
    if (!text) return out;
    const rawEntries = String(text).split(/[,\n]/).map(s => s.trim()).filter(Boolean);
    for (const entry of rawEntries) {
        const m = entry.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:->|→)\s*([A-Za-z_][A-Za-z0-9_]*)$/);
        if (!m) {
            console.warn('virtualForeignKeys: dropping malformed entry:', entry);
            continue;
        }
        out.push({ sourceTable: m[1], sourceColumn: m[2], targetTable: m[3] });
    }
    return out;
}

function renderVirtualForeignKeys(arr) {
    if (!Array.isArray(arr) || arr.length === 0) return '';
    return arr
        .filter(v => v && v.sourceTable && v.sourceColumn && v.targetTable)
        .map(v => `${v.sourceTable}.${v.sourceColumn} -> ${v.targetTable}`)
        .join('\n');
}

async function handleSuggestVirtualFks(profileId, btn) {
    const panel = document.getElementById('vfk-suggestions-panel');
    if (!panel) return;
    const originalHtml = btn.innerHTML;
    btn.disabled = true;
    btn.textContent = 'Loading…';
    try {
        const suggestions = await getVirtualFkSuggestions(profileId);
        renderVfkSuggestionsPanel(panel, suggestions || []);
    } catch (err) {
        panel.hidden = false;
        panel.innerHTML = `<p class="form-help vfk-suggestions-error">Could not load suggestions: ${escapeHtml(err.message || String(err))}. Make sure the dataModel is uploaded.</p>`;
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHtml;
    }
}

function renderVfkSuggestionsPanel(panel, suggestions) {
    // Filter out entries already present in the textarea so the user only sees NEW candidates.
    const textarea = document.getElementById('field-virtualForeignKeys');
    const existing = new Set(parseVirtualForeignKeys(textarea ? textarea.value : '')
        .map(v => `${v.sourceTable.toLowerCase()}.${v.sourceColumn.toLowerCase()}`));
    const fresh = suggestions.filter(s =>
        !existing.has(`${(s.sourceTable || '').toLowerCase()}.${(s.sourceColumn || '').toLowerCase()}`));

    if (fresh.length === 0) {
        panel.hidden = false;
        panel.innerHTML = `<p class="form-help">No new candidates — the name-heuristic scan didn't find any columns to suggest, or all matches are already listed above.</p>`;
        return;
    }

    const rows = fresh.map((s, i) => `
        <label class="vfk-suggestion-row">
            <input type="checkbox" data-vfk-idx="${i}" checked>
            <code class="vfk-suggestion-col">${escapeHtml(s.sourceTable)}.${escapeHtml(s.sourceColumn)}</code>
            <span class="vfk-suggestion-arrow">→</span>
            <input type="text" class="vfk-suggestion-target" data-vfk-idx="${i}"
                   value="${escapeHtml(s.suggestedTarget || 'DomainValue')}"
                   spellcheck="false">
        </label>
    `).join('');

    panel.hidden = false;
    panel.innerHTML = `
        <div class="vfk-suggestions-header">
            <strong>${fresh.length}</strong> candidate${fresh.length === 1 ? '' : 's'} — untick any you don't want, edit the target table if needed, then add.
        </div>
        <div class="vfk-suggestions-list">${rows}</div>
        <div class="vfk-suggestions-actions">
            <button type="button" class="btn btn-sm" id="btn-vfk-add-selected">Add selected</button>
            <button type="button" class="btn ghost btn-sm" id="btn-vfk-dismiss">Dismiss</button>
        </div>
    `;

    panel.querySelector('#btn-vfk-add-selected').addEventListener('click', () => {
        const rowsEls = panel.querySelectorAll('input[type="checkbox"][data-vfk-idx]');
        const toAdd = [];
        rowsEls.forEach(cb => {
            if (!cb.checked) return;
            const idx = Number(cb.dataset.vfkIdx);
            const s = fresh[idx];
            const targetInput = panel.querySelector(`input.vfk-suggestion-target[data-vfk-idx="${idx}"]`);
            const target = targetInput ? targetInput.value.trim() : '';
            if (!target) return;
            toAdd.push({ sourceTable: s.sourceTable, sourceColumn: s.sourceColumn, targetTable: target });
        });
        if (toAdd.length === 0) {
            (window.showToast || alert)('Nothing selected.', 'info');
            return;
        }
        const currentText = textarea ? textarea.value.trim() : '';
        const appended = toAdd.map(v => `${v.sourceTable}.${v.sourceColumn} -> ${v.targetTable}`).join('\n');
        if (textarea) {
            textarea.value = currentText ? `${currentText}\n${appended}` : appended;
        }
        panel.hidden = true;
        panel.innerHTML = '';
        (window.showToast || alert)(`Added ${toAdd.length} entr${toAdd.length === 1 ? 'y' : 'ies'}. Click Save changes to persist.`, 'success');
    });

    panel.querySelector('#btn-vfk-dismiss').addEventListener('click', () => {
        panel.hidden = true;
        panel.innerHTML = '';
    });
}

/* ---------- Helpers ---------- */

function avatarInitials(name) {
    const parts = String(name).trim().split(/\s+/).filter(Boolean);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    if (parts[0]) return parts[0].substring(0, 2).toUpperCase();
    return '?';
}

function avatarColor(str) {
    let hash = 0;
    for (let i = 0; i < str.length; i++) {
        hash = str.charCodeAt(i) + ((hash << 5) - hash);
    }
    return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length];
}

function extractHost(url) {
    if (!url) return '';
    try {
        const u = new URL(url);
        return u.host;
    } catch {
        return url.replace(/^https?:\/\//, '').split('/')[0];
    }
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}

window.addEventListener('profile-changed', () => {
    if (window.location.hash === '#/profiles' && document.getElementById('profiles-list')) {
        refreshList().catch(err => console.error('Profiles refresh failed', err));
        refreshStatusAndContext().catch(() => {});
    }
});
window.addEventListener('token-changed', () => {
    if (window.location.hash === '#/profiles' && document.getElementById('profiles-status-panel')) {
        refreshStatusAndContext().catch(() => {});
    }
});

/* ---------- Status panel + context card (state-aware header) ---------- */

function renderStatusSkeleton() {
    return ['Profile', 'DataModel', 'FAWB token'].map(t => `
        <div class="home-status-tile home-status-tile-loading">
            <div class="home-status-tile-label">${t}</div>
            <div class="home-status-tile-value">…</div>
        </div>
    `).join('');
}

async function refreshStatusAndContext() {
    let profile = null, authOk = false, dm = null;
    try { profile = await getActiveProfile(); } catch { /* stays null */ }
    const [authRes, dmRes] = await Promise.all([
        (async () => { try { return await getAuthStatus(); } catch { return null; } })(),
        (async () => {
            if (!profile || !profile.id) return null;
            try { return await getDataModelSummary(profile.id); } catch { return null; }
        })(),
    ]);
    authOk = !!(authRes && authRes.set);
    dm = dmRes && dmRes.loaded ? dmRes : null;
    renderStatusPanel(profile, dm, authOk);
    renderContextCard(profile, dm, authOk);
}

function renderStatusPanel(profile, dm, authOk) {
    const panel = document.getElementById('profiles-status-panel');
    if (!panel) return;

    const profileTile = profile
        ? statusTile({
            label: 'Active profile',
            valueHtml: `<strong>${escapeHtml(profile.name || '(unnamed)')}</strong>`,
            metaLines: [
                profile.designBaseUrl ? `Design: ${escapeHtml(extractHost(profile.designBaseUrl))}` : null,
                profile.sandboxBaseUrl ? `Sandbox: ${escapeHtml(extractHost(profile.sandboxBaseUrl))}` : null,
            ].filter(Boolean),
            statusDot: 'ok',
        })
        : statusTile({
            label: 'Active profile',
            valueHtml: `<em>None selected</em>`,
            metaLines: ['Add or activate a profile below'],
            statusDot: 'warn',
        });

    const dmTile = dm
        ? statusTile({
            label: 'Data model',
            valueHtml: `<strong>${dm.tables}</strong> tables · <strong>${dm.columns}</strong> columns`,
            metaLines: [`${dm.relations} relations · ${formatBytes(dm.sizeBytes)}`],
            statusDot: 'ok',
        })
        : statusTile({
            label: 'Data model',
            valueHtml: profile ? '<em>Not uploaded</em>' : '<em>—</em>',
            metaLines: profile
                ? ['Edit the active profile to upload dataModel.json']
                : ['Waiting for a profile'],
            statusDot: profile ? 'warn' : 'muted',
        });

    const tokenTile = authOk
        ? statusTile({
            label: 'FAWB token',
            valueHtml: `<strong>Set</strong>`,
            metaLines: ['Auto-refresh runs at ~90% of token TTL'],
            statusDot: 'ok',
        })
        : statusTile({
            label: 'FAWB token',
            valueHtml: `<em>Not set</em>`,
            metaLines: ['Click the token indicator in the sidebar footer'],
            statusDot: 'warn',
        });

    panel.innerHTML = profileTile + dmTile + tokenTile;
}

function statusTile({ label, valueHtml, metaLines, statusDot }) {
    const meta = (metaLines || []).map(l => `<div class="home-status-tile-meta">${l}</div>`).join('');
    return `
        <div class="home-status-tile home-status-tile-${statusDot}">
            <div class="home-status-tile-header">
                <span class="home-status-dot home-status-dot-${statusDot}" aria-hidden="true"></span>
                <span class="home-status-tile-label">${escapeHtml(label)}</span>
            </div>
            <div class="home-status-tile-value">${valueHtml}</div>
            ${meta}
        </div>
    `;
}

function renderContextCard(profile, dm, authOk) {
    const el = document.getElementById('profiles-context-card');
    if (!el) return;
    el.classList.remove('home-context-card-loading');

    let ctx;
    if (!profile) {
        ctx = {
            kind: 'setup', icon: ctxIconFolder(),
            title: 'Start by adding a profile',
            desc: `A profile connects DevBridge to one FAWB project — design and sandbox URLs, DB service name, OAuth credentials. Everything else builds on this.`,
            ctaLabel: 'Add a profile', ctaAction: 'focus-add',
        };
    } else if (!dm) {
        ctx = {
            kind: 'setup', icon: ctxIconUpload(),
            title: `Upload the project's data model`,
            desc: `<strong>${escapeHtml(profile.name || '(unnamed)')}</strong> is missing its dataModel.json. Import App and DB Explorer both need it to walk the FK graph and render the schema.`,
            ctaLabel: 'Edit profile to upload', ctaAction: 'edit-active',
        };
    } else if (!authOk) {
        ctx = {
            kind: 'setup', icon: ctxIconKey(),
            title: 'Set your FAWB bearer token',
            desc: `Every FAWB request needs a valid <code>WM_AUTH_TOKEN</code>. If OAuth credentials are set on the profile, we can auto-fetch — otherwise paste one from an active FAWB session.`,
            ctaLabel: 'Open token modal', ctaAction: 'open-token-modal',
        };
    } else {
        ctx = {
            kind: 'ready', icon: ctxIconRocket(),
            title: `Ready — ${escapeHtml(profile.name || '(unnamed)')} is fully configured`,
            desc: `Data model loaded (${dm.tables} tables), token set. Import an app from design to sandbox, or run ad-hoc SQL to check state.`,
            ctaLabel: 'Import an app', ctaHref: '#/import-app',
            ctaSecondaryLabel: 'Run SQL', ctaSecondaryHref: '#/sql-runner',
        };
    }

    const secondary = ctx.ctaSecondaryLabel
        ? `<a href="${ctx.ctaSecondaryHref}" class="btn secondary home-context-cta-secondary">${escapeHtml(ctx.ctaSecondaryLabel)}</a>`
        : '';
    const primary = ctx.ctaAction
        ? `<button class="btn home-context-cta" data-action="${ctx.ctaAction}">${escapeHtml(ctx.ctaLabel)}</button>`
        : `<a href="${ctx.ctaHref}" class="btn home-context-cta">${escapeHtml(ctx.ctaLabel)}</a>`;

    el.classList.remove('home-context-card-setup', 'home-context-card-ready');
    el.classList.add(`home-context-card-${ctx.kind}`);
    el.innerHTML = `
        <div class="home-context-card-icon">${ctx.icon}</div>
        <div class="home-context-card-body">
            <div class="home-context-card-title">${escapeHtml(ctx.title)}</div>
            <div class="home-context-card-desc">${ctx.desc}</div>
        </div>
        <div class="home-context-card-actions">
            ${primary}
            ${secondary}
        </div>
    `;

    el.querySelectorAll('button[data-action]').forEach(btn => {
        btn.addEventListener('click', () => {
            const action = btn.dataset.action;
            if (action === 'open-token-modal') {
                window.dispatchEvent(new CustomEvent('open-token-modal'));
            } else if (action === 'focus-add') {
                document.getElementById('btn-show-add')?.click();
            } else if (action === 'edit-active') {
                const p = profilesCache.find(x => x.id === profile.id);
                if (p) startEdit(p);
            }
        });
    });
}

function ctxIconFolder() {
    return `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>`;
}
function ctxIconUpload() {
    return `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>`;
}
function ctxIconKey() {
    return `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"/></svg>`;
}
function ctxIconRocket() {
    return `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4.5 16.5c-1.5 1.26-2 5-2 5s3.74-.5 5-2c.71-.84.7-2.13-.09-2.91a2.18 2.18 0 0 0-2.91-.09z"/><path d="M12 15l-3-3a22 22 0 0 1 2-3.95A12.88 12.88 0 0 1 22 2c0 2.72-.78 7.5-6 11a22.35 22.35 0 0 1-4 2z"/><path d="M9 12H4s.55-3.03 2-4c1.62-1.08 5 0 5 0"/><path d="M12 15v5s3.03-.55 4-2c1.08-1.62 0-5 0-5"/></svg>`;
}
