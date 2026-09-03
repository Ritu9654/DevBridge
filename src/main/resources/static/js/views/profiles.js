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
    { key: 'rootPkFilterColumn', label: 'Root PK filter column (optional)', placeholder: 'Blank = root table\'s PK',
      help: 'Column on the root table used to filter one app (e.g. "ID" for CASE_HEADER). Leave blank to use the root table\'s primary key.' },
    { key: 'facadeServicePath', label: 'Facade service path (optional)', placeholder: 'yourFacadeService',
      help: 'Only used by the facade cross-check — an advisory comparison of the FK-walk result against FAWB\'s composite facade output. The import itself is FK-graph driven and doesn\'t need these fields.' },
    { key: 'facadeEndpoint', label: 'Facade endpoint (optional)', placeholder: '/entity',
      help: 'Path within the facade service. Only used by the cross-check.' },
    { key: 'facadeQueryParam', label: 'Facade query param (optional)', placeholder: 'entityId',
      help: 'Just the parameter name — no "=", no value. Only used by the cross-check. Preserve any typos exactly (e.g. "applicantionId").' },
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
            <h2>Project Profiles</h2>
            <p>One profile per project. Only <strong>Profile name</strong> is required to save. Click any inactive card to activate — the active profile drives every module.</p>

            <div class="section-header">
                <h3>Existing profiles</h3>
                <button class="btn" id="btn-show-add">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                    Add profile
                </button>
            </div>
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
                            <h4>Facade key overrides <span class="hint-inline">(advanced — cross-check only)</span></h4>
                        </div>
                        <p class="form-help">
                            Map facade JSON keys to DB table names, used only by the facade cross-check
                            (an advisory comparison between the facade's coverage and the FK-walk result).
                            The import itself is FK-graph driven and doesn't depend on these.
                            Format: JSON object mapping key → table name.
                        </p>
                        <textarea id="field-facadeKeyOverrides" name="facadeKeyOverrides" class="json-overrides-input"
                                  rows="4" spellcheck="false"
                                  placeholder='{"applicants": "CASE_PARTY", "productDecisionOutputs": "PRODUCT_DECISION_OUTPUT_EXTRACT"}'></textarea>
                        <p class="form-help" id="facade-overrides-error"></p>
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

                    <div class="form-section">
                        <hr class="form-divider">
                        <div class="form-section-title">
                            <h4>Reference-table overrides <span class="hint-inline">(usually leave blank)</span></h4>
                        </div>
                        <p class="form-help">
                            <strong>Auto-detected by default</strong> — you don't need to fill this out unless
                            something's wrong. Before each import, the tool inspects the target DB's unique
                            indexes to figure out the natural key of every reference table the app touches
                            (DOMAINVALUE, PRODUCT, etc.) and remaps FKs by matching on that key. The plan
                            preview shows what was detected.
                        </p>
                        <p class="form-help">
                            Only add an entry here to <strong>override</strong> a specific table when auto-detection
                            picks the wrong natural key. User overrides win per-table; unlisted tables continue
                            to use auto-detection. Keys are physical DB table names. Format:
                            <code>{"TABLE": {"naturalKey": ["col1","col2"], "activeFilter": "IsActive=1"}}</code>
                        </p>
                        <textarea id="field-referenceTableConfigs" name="referenceTableConfigs" class="json-overrides-input"
                                  rows="6" spellcheck="false"
                                  placeholder='{"DOMAINVALUE": {"naturalKey": ["Code","DomainValueType"], "activeFilter": "IsActive=1"}, "DOMAINVALUETYPE": {"naturalKey": ["Code"]}}'></textarea>
                        <p class="form-help" id="reference-configs-error"></p>
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
                await refreshDataModelStatus(editingProfileId);
            } catch (err) {
                (window.showToast || alert)('Remove failed: ' + err.message, 'error');
            }
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

    // facadeKeyOverrides comes in as a raw JSON string from the textarea. Parse + validate.
    const errEl = document.getElementById('facade-overrides-error');
    if (errEl) errEl.textContent = '';
    if (data.facadeKeyOverrides) {
        try {
            const parsed = JSON.parse(data.facadeKeyOverrides);
            if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null) {
                throw new Error('Must be a JSON object like {"key": "TABLE_NAME"}.');
            }
            for (const [k, v] of Object.entries(parsed)) {
                if (typeof v !== 'string' || v.trim() === '') {
                    throw new Error(`Value for key "${k}" must be a non-empty string.`);
                }
            }
            data.facadeKeyOverrides = parsed;
        } catch (err) {
            if (errEl) errEl.textContent = 'Invalid facade key overrides: ' + err.message;
            (window.showToast || alert)('Invalid facade key overrides: ' + err.message, 'error');
            return;
        }
    } else {
        delete data.facadeKeyOverrides;
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

    // referenceTableConfigs is a raw JSON string from the textarea. Parse + validate.
    const refCfgErrEl = document.getElementById('reference-configs-error');
    if (refCfgErrEl) refCfgErrEl.textContent = '';
    if (data.referenceTableConfigs) {
        try {
            const parsed = JSON.parse(data.referenceTableConfigs);
            if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null) {
                throw new Error('Must be a JSON object like {"TABLE": {"naturalKey": ["col"]}}.');
            }
            for (const [k, v] of Object.entries(parsed)) {
                if (!v || typeof v !== 'object' || Array.isArray(v)) {
                    throw new Error(`Entry '${k}' must be an object with naturalKey.`);
                }
                if (!Array.isArray(v.naturalKey) || v.naturalKey.length === 0) {
                    throw new Error(`Entry '${k}': naturalKey must be a non-empty array of column names.`);
                }
                for (const col of v.naturalKey) {
                    if (typeof col !== 'string' || !col.trim()) {
                        throw new Error(`Entry '${k}': naturalKey contains a non-string or blank column.`);
                    }
                }
                if (v.activeFilter != null && typeof v.activeFilter !== 'string') {
                    throw new Error(`Entry '${k}': activeFilter must be a string when set.`);
                }
            }
            data.referenceTableConfigs = parsed;
        } catch (err) {
            if (refCfgErrEl) refCfgErrEl.textContent = 'Invalid reference table configs: ' + err.message;
            (window.showToast || alert)('Invalid reference table configs: ' + err.message, 'error');
            return;
        }
    } else {
        delete data.referenceTableConfigs;
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
}

function startEdit(profile) {
    editingProfileId = profile.id;
    const form = document.getElementById('add-profile-form');
    FIELDS.forEach(f => {
        const input = form.querySelector(`[name="${f.key}"]`);
        if (input) input.value = profile[f.key] != null ? profile[f.key] : '';
    });
    // facadeKeyOverrides is stored as an object — render as pretty JSON string in the textarea
    const jsonInput = form.querySelector('[name="facadeKeyOverrides"]');
    if (jsonInput) {
        jsonInput.value = profile.facadeKeyOverrides && Object.keys(profile.facadeKeyOverrides).length
            ? JSON.stringify(profile.facadeKeyOverrides, null, 2)
            : '';
    }
    // referenceTables is stored as an array — render as comma-separated
    const refInput = form.querySelector('[name="referenceTables"]');
    if (refInput) {
        refInput.value = Array.isArray(profile.referenceTables) && profile.referenceTables.length
            ? profile.referenceTables.join(', ')
            : '';
    }
    // referenceTableConfigs is stored as an object — render as pretty JSON in the textarea
    const refCfgInput = form.querySelector('[name="referenceTableConfigs"]');
    if (refCfgInput) {
        refCfgInput.value = profile.referenceTableConfigs && Object.keys(profile.referenceTableConfigs).length
            ? JSON.stringify(profile.referenceTableConfigs, null, 2)
            : '';
    }
    document.getElementById('form-title').textContent = `Edit profile — ${profile.name || '(unnamed)'}`;
    document.getElementById('btn-submit').textContent = 'Save changes';

    // Reveal dataModel section and load current status
    const dmSection = document.getElementById('datamodel-section');
    if (dmSection) dmSection.hidden = false;
    refreshFormDataModel(profile.id).catch(() => {});

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

    // Kick off dataModel status checks — async, doesn't block card rendering
    profiles.forEach(p => refreshDataModelStatus(p.id));

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
    const urlPreview = extractHost(p.designBaseUrl || p.sandboxBaseUrl || '');
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
                    <p class="profile-sub">${escapeHtml(urlPreview || 'No URL')}</p>
                </div>
                ${isActive ? '<span class="badge">Active</span>' : ''}
            </div>
            <div class="profile-datamodel" data-profile-id="${p.id}">
                <span class="dm-status dm-loading">Checking dataModel…</span>
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

async function refreshDataModelStatus(profileId) {
    const el = document.querySelector(`.profile-datamodel[data-profile-id="${profileId}"]`);
    if (!el) return;
    try {
        const s = await getDataModelSummary(profileId);
        if (s && s.loaded) {
            el.innerHTML = `<span class="dm-status dm-loaded" title="Click DataModel to replace or remove">
                <span class="dm-dot"></span>
                DataModel: <strong>${s.tables}</strong> tables
                <span class="dm-hint">· ${formatBytes(s.sizeBytes)}</span>
            </span>`;
        } else {
            el.innerHTML = `<span class="dm-status dm-missing">
                <span class="dm-dot"></span>
                DataModel not uploaded
            </span>`;
        }
    } catch {
        el.innerHTML = `<span class="dm-status dm-missing"><span class="dm-dot"></span>DataModel status unavailable</span>`;
    }
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
            // Refresh both places that show status: form (if visible) and card
            await refreshFormDataModel(profileId);
            await refreshDataModelStatus(profileId);
        } catch (err) {
            (window.showToast || alert)('Upload failed: ' + err.message, 'error');
        } finally {
            btn.disabled = false;
            btn.innerHTML = originalHtml;
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
            await runTestConnection(id, btn);
        }
    } catch (err) {
        (window.showToast || alert)(`${action} failed: ${err.message}`, 'error');
    }
}

async function runTestConnection(profileId, btn) {
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
        const results = await testConnection(profileId);
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
    }
});
