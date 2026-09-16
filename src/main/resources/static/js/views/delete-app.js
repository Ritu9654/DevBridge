import { planDelete, executeDelete, getActiveProfile, getLatestDeleteJournal } from '../api.js';

const state = {
    profile: null,
    lastPlan: null,
};

export const deleteAppView = {
    title: 'Delete App',
    render() {
        return `
            <h2>Delete App</h2>
            <p>Delete an application from <strong>sandbox</strong> by app ID or application number. Walks the FK graph from your profile's root table, gathers every row belonging to the app, and deletes them in <strong>reverse dependency order</strong> (children first, root last) so foreign-key constraints don't block. Reference tables are never touched.</p>

            <div class="import-form card">
                <div class="env-row">
                    <div class="env-picker">
                        <label class="env-label">Env (delete from)</label>
                        <div class="env-fixed" id="delete-env-badge">Sandbox</div>
                    </div>
                </div>
                <div class="form-inline">
                    <label for="delete-lookup-col">Look up by</label>
                    <select id="delete-lookup-col" class="select">
                        <option value="" selected>Application ID (root PK)</option>
                        <option value="applicationNumber">Application Number</option>
                    </select>
                    <label for="app-id-input">Value</label>
                    <input type="text" id="app-id-input" placeholder="e.g., 6901" autocomplete="off">
                    <button class="btn" id="btn-plan-delete">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
                        </svg>
                        Preview delete plan
                    </button>
                </div>
                <p class="form-help" id="delete-help"></p>
            </div>

            <div id="fetch-status" class="sql-status hidden"></div>
            <div id="plan-results"></div>
        `;
    },

    async mount() {
        state.profile = null;
        state.lastPlan = null;

        let active = null;
        try { active = await getActiveProfile(); } catch { /* ignore */ }
        state.profile = active;

        const planBtn = document.getElementById('btn-plan-delete');
        const input = document.getElementById('app-id-input');

        if (!active) {
            document.getElementById('plan-results').innerHTML = `<p class="muted">No active profile.
                <a href="#/profiles">Add and activate one</a> before deleting an app.</p>`;
            planBtn.disabled = true;
            input.disabled = true;
            return;
        }

        const missing = [];
        if (!active.sandboxBaseUrl) missing.push('sandbox base URL');
        if (!active.rootTableName) missing.push('root table name');
        if (!active.dbServiceName && !active.sqlDbName) missing.push('DB service name or SQL DB name');
        if (missing.length > 0) {
            document.getElementById('plan-results').innerHTML = `<p class="muted">Active profile is missing:
                <strong>${missing.map(escapeHtml).join(', ')}</strong>.
                <a href="#/profiles">Edit the profile</a> to add these before deleting.</p>`;
            planBtn.disabled = true;
            input.disabled = true;
            return;
        }

        updateHelpText();
        const lookupSel = document.getElementById('delete-lookup-col');
        if (lookupSel) {
            lookupSel.addEventListener('change', () => {
                updateHelpText();
                // update placeholder to guide the user
                if (lookupSel.value === 'applicationNumber') {
                    input.placeholder = 'e.g., 202606090606776';
                } else {
                    input.placeholder = 'e.g., 6901';
                }
            });
        }
        planBtn.addEventListener('click', doPlan);
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); doPlan(); }
        });
        input.focus();
    },
};

function currentLookupColumn() {
    const sel = document.getElementById('delete-lookup-col');
    return sel && sel.value ? sel.value : '';
}

function updateHelpText() {
    const p = state.profile;
    if (!p) return;
    const el = document.getElementById('delete-help');
    const chosen = currentLookupColumn();
    const filterCol = chosen || p.rootPkFilterColumn || '(root PK)';
    if (p.sandboxBaseUrl) {
        el.textContent = `Walks the FK graph starting at ${p.rootTableName} where ${filterCol} = <value>, on ${p.sandboxBaseUrl}. Deletes in reverse.`;
    } else {
        el.textContent = '';
    }
}

async function doPlan() {
    const input = document.getElementById('app-id-input');
    const planBtn = document.getElementById('btn-plan-delete');
    const planResultsEl = document.getElementById('plan-results');
    const appId = input.value.trim();

    if (!appId) {
        showStatus('Enter an application ID before previewing.', 'error');
        return;
    }

    planBtn.disabled = true;
    const originalHtml = planBtn.innerHTML;
    planBtn.textContent = 'Planning…';
    showStatus('Fetching rows from sandbox and building delete plan…', 'info');
    planResultsEl.innerHTML = '';

    const started = Date.now();
    const lookupColumn = currentLookupColumn();
    try {
        const plan = await planDelete({ appId, lookupColumn });
        const elapsed = Date.now() - started;
        if (!plan || plan.totalTables === 0) {
            const idLabel = lookupColumn ? lookupColumn : 'app id';
            showStatus(`Plan returned 0 tables in ${elapsed} ms — nothing to delete (${idLabel} not found).`, 'info');
        } else {
            const w = (plan.warnings && plan.warnings.length) || 0;
            const warnPart = w > 0 ? ` (${w} note${w === 1 ? '' : 's'})` : '';
            showStatus(`Plan built — ${plan.totalTables} table${plan.totalTables === 1 ? '' : 's'}, ${plan.totalRows} row${plan.totalRows === 1 ? '' : 's'} to delete in ${elapsed} ms${warnPart}. No writes performed.`, 'success');
        }
        renderPlan(plan);
    } catch (err) {
        showStatus(`Plan failed: ${err.message}`, 'error');
    } finally {
        planBtn.disabled = false;
        planBtn.innerHTML = originalHtml;
    }
}

function showStatus(msg, kind) {
    const el = document.getElementById('fetch-status');
    el.textContent = msg;
    el.className = `sql-status ${kind}`;
    el.classList.remove('hidden');
}

/* ------------ Plan rendering ------------ */

function renderPlan(plan) {
    const el = document.getElementById('plan-results');
    state.lastPlan = plan;
    const warningsHtml = renderNotes('Warnings', 'warning', plan.warnings || []);
    const envHtml = renderEnvSection(plan.env);
    const stepsHtml = renderSteps(plan.steps || []);
    const canExecute = plan.totalRows > 0;

    el.innerHTML = `
        <div class="plan-summary card">
            <h3 style="margin-top:0">Dry-run delete plan</h3>
            <p class="plan-summary-line">
                <strong>${plan.totalTables}</strong> table${plan.totalTables === 1 ? '' : 's'}
                &middot; <strong>${plan.totalRows}</strong> row${plan.totalRows === 1 ? '' : 's'} to delete
                &middot; <span class="muted-inline">read-only — no writes to sandbox</span>
            </p>
            ${envHtml}
            ${warningsHtml}
            <div class="plan-actions">
                <button class="btn danger" id="btn-execute-delete" ${canExecute ? '' : 'disabled'}
                        title="${canExecute ? 'Open confirmation dialog' : 'Nothing to delete'}">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/>
                    </svg>
                    Execute delete…
                </button>
                <span class="plan-actions-hint">Removes rows from sandbox. A confirmation dialog will appear.</span>
            </div>
        </div>
        ${stepsHtml}
        <div id="execute-results"></div>
    `;

    const btn = document.getElementById('btn-execute-delete');
    if (btn && canExecute) btn.addEventListener('click', openDeleteModal);
}

/* ------------ Execute (confirmation modal + POST) ------------ */

function openDeleteModal() {
    const plan = state.lastPlan;
    if (!plan) return;
    const appId = document.getElementById('app-id-input').value.trim();

    document.getElementById('delete-confirm-profile').textContent = state.profile ? state.profile.name : '(unknown)';
    document.getElementById('delete-confirm-env').textContent = (plan.env && plan.env.env) ? plan.env.env : 'sandbox';
    document.getElementById('delete-confirm-target-url').textContent =
        (plan.env && plan.env.baseUrl) ? plan.env.baseUrl : '(unset)';
    document.getElementById('delete-confirm-appid').textContent = appId || '(none)';
    document.getElementById('delete-confirm-steps').textContent =
        `${plan.totalRows} row${plan.totalRows === 1 ? '' : 's'} across ${plan.totalTables} table${plan.totalTables === 1 ? '' : 's'}`;

    const input = document.getElementById('delete-confirm-input');
    const doBtn = document.getElementById('btn-do-delete');
    input.value = '';
    doBtn.disabled = true;

    const lookupColumn = currentLookupColumn();
    input.oninput = () => { doBtn.disabled = input.value.trim() !== appId; };
    document.getElementById('btn-cancel-delete').onclick = closeDeleteModal;
    doBtn.onclick = () => runExecuteDelete(appId, input.value.trim(), lookupColumn);

    document.getElementById('delete-modal').classList.remove('hidden');
    setTimeout(() => input.focus(), 10);
}

function closeDeleteModal() {
    document.getElementById('delete-modal').classList.add('hidden');
}

async function runExecuteDelete(appId, confirmToken, lookupColumn) {
    // Close the confirmation modal and open a live progress panel that
    // polls the delete journal on disk every second. Mirrors the import UX.
    closeDeleteModal();
    openDeleteProgressPanel();

    const started = Date.now();
    const poller = startDeleteJournalPoller();
    try {
        const journal = await executeDelete({ appId, lookupColumn, confirm: true, confirmToken });
        const elapsedMs = Date.now() - started;
        renderDeleteProgressPanel(journal, elapsedMs, /*done=*/ true);
        renderDeleteResult(journal, elapsedMs);
        const st = journal.status;
        const deletedCount = (journal.entries || []).filter(e => e.result === 'deleted').length;
        const failedCount = (journal.entries || []).filter(e => e.result === 'failed').length;
        const took = formatDuration(elapsedMs);
        let toastMsg, toastKind;
        if (st === 'success') {
            toastMsg = `Delete complete — ${deletedCount} row${deletedCount === 1 ? '' : 's'} removed in ${took}.`;
            toastKind = 'success';
        } else {
            toastMsg = `Delete halted after ${took} — ${deletedCount} row${deletedCount === 1 ? '' : 's'} removed, ${failedCount} failed. See details.`;
            toastKind = 'error';
        }
        (window.showToast || alert)(toastMsg, toastKind, 7000);
    } catch (err) {
        const elapsedMs = Date.now() - started;
        (window.showToast || alert)(`Delete failed after ${formatDuration(elapsedMs)}: ${err.message}`, 'error');
    } finally {
        if (poller) clearInterval(poller);
        if (deleteProgressState.elapsedTimer) {
            clearInterval(deleteProgressState.elapsedTimer);
            deleteProgressState.elapsedTimer = null;
        }
    }
}

/* ---------- Live progress panel (polls the delete journal on disk) ---------- */

const deleteProgressState = {
    startedAt: 0,
    pollTimer: null,
    elapsedTimer: null,
};

function openDeleteProgressPanel() {
    const container = document.getElementById('execute-results');
    if (!container) return;
    deleteProgressState.startedAt = Date.now();
    const plan = state.lastPlan;
    const steps = (plan && plan.steps) || [];
    container.innerHTML = `
        <div class="import-progress card" id="delete-progress-panel">
            <div class="import-progress-header">
                <div>
                    <h3 style="margin:0">Delete in progress</h3>
                    <p class="muted" style="margin:4px 0 0 0">
                        Watching the journal for per-table updates every second.
                        Children delete first; the root table is last.
                    </p>
                </div>
                <div class="import-progress-stats">
                    <div><span class="import-progress-num" id="delete-progress-rows-done">0</span> / <span id="delete-progress-rows-total">${plan ? plan.totalRows : 0}</span> rows</div>
                    <div class="muted"><span id="delete-progress-elapsed">0s</span> elapsed</div>
                </div>
            </div>
            <ol class="import-progress-list" id="delete-progress-list">
                ${steps.map(s => `
                    <li class="import-progress-step" data-table="${escapeHtml(s.tableName)}">
                        <span class="import-progress-icon" data-status="pending">⏳</span>
                        <span class="import-progress-name">${escapeHtml(s.tableName)}</span>
                        <span class="import-progress-meta">${s.rowCount} row${s.rowCount === 1 ? '' : 's'} expected</span>
                    </li>
                `).join('')}
            </ol>
        </div>
    `;
    deleteProgressState.elapsedTimer = setInterval(() => {
        const el = document.getElementById('delete-progress-elapsed');
        if (el) el.textContent = formatDuration(Date.now() - deleteProgressState.startedAt);
    }, 500);
}

function startDeleteJournalPoller() {
    if (!state.profile || !state.profile.id) return null;
    const profileId = state.profile.id;
    const initialTs = deleteProgressState.startedAt;
    deleteProgressState.pollTimer = setInterval(async () => {
        try {
            const journal = await getLatestDeleteJournal(profileId);
            if (!journal) return;
            const journalStarted = journal.startedAt ? new Date(journal.startedAt).getTime() : 0;
            if (journalStarted > 0 && journalStarted + 3000 < initialTs) return;   // stale
            renderDeleteProgressPanel(journal, Date.now() - deleteProgressState.startedAt, /*done=*/ false);
        } catch { /* silent — next tick will retry */ }
    }, 1000);
    return deleteProgressState.pollTimer;
}

function renderDeleteProgressPanel(journal, elapsedMs, done) {
    const list = document.getElementById('delete-progress-list');
    if (!list || !journal) return;

    const entries = journal.entries || [];
    const byTable = new Map();
    for (const e of entries) {
        if (!e || !e.tableName) continue;
        const prev = byTable.get(e.tableName) || { deleted: 0, failed: 0, skipped: 0, lastMsg: '' };
        if (e.result === 'deleted') prev.deleted++;
        else if (e.result === 'failed') prev.failed++;
        else if (e.result === 'skipped') prev.skipped++;
        prev.lastMsg = e.message || '';
        byTable.set(e.tableName, prev);
    }

    const lastEntry = entries.length > 0 ? entries[entries.length - 1] : null;
    const activeTable = done ? null : (lastEntry ? lastEntry.tableName : null);

    let rowsDone = 0;
    list.querySelectorAll('.import-progress-step').forEach(li => {
        const tableName = li.dataset.table;
        const stat = byTable.get(tableName);
        const iconEl = li.querySelector('.import-progress-icon');
        const metaEl = li.querySelector('.import-progress-meta');
        if (!stat) {
            if (done) {
                iconEl.textContent = '—';
                iconEl.dataset.status = 'unreached';
                metaEl.textContent = 'not reached';
            } else {
                iconEl.textContent = '⏳';
                iconEl.dataset.status = 'pending';
            }
            return;
        }
        rowsDone += stat.deleted;
        if (stat.failed > 0) {
            iconEl.textContent = '✗';
            iconEl.dataset.status = 'failed';
            metaEl.textContent = `${stat.deleted} deleted, ${stat.failed} failed`;
            li.setAttribute('title', stat.lastMsg);
        } else if (activeTable === tableName) {
            iconEl.textContent = '↻';
            iconEl.dataset.status = 'in-progress';
            metaEl.textContent = `${stat.deleted} deleted…`;
        } else {
            iconEl.textContent = '✓';
            iconEl.dataset.status = 'done';
            metaEl.textContent = `${stat.deleted} deleted`;
        }
    });

    const rowsDoneEl = document.getElementById('delete-progress-rows-done');
    if (rowsDoneEl) rowsDoneEl.textContent = rowsDone;
    const elapsedEl = document.getElementById('delete-progress-elapsed');
    if (elapsedEl && done) {
        elapsedEl.textContent = formatDuration(elapsedMs);
        if (deleteProgressState.elapsedTimer) {
            clearInterval(deleteProgressState.elapsedTimer);
            deleteProgressState.elapsedTimer = null;
        }
    }
    if (done && deleteProgressState.pollTimer) {
        clearInterval(deleteProgressState.pollTimer);
        deleteProgressState.pollTimer = null;
    }
}

/** Format a millisecond count as "823 ms", "4.2s", "3m 12s", "1h 4m 12s". */
function formatDuration(ms) {
    if (ms == null || ms < 0) return '—';
    if (ms < 1000) return `${ms} ms`;
    const totalSec = Math.round(ms / 1000);
    if (totalSec < 60) return `${(ms / 1000).toFixed(1)}s`;
    const h = Math.floor(totalSec / 3600);
    const m = Math.floor((totalSec % 3600) / 60);
    const s = totalSec % 60;
    if (h > 0) return `${h}h ${m}m ${s}s`;
    return `${m}m ${s}s`;
}

function renderDeleteResult(journal, elapsedMs) {
    const el = document.getElementById('execute-results');
    if (!el || !journal) return;
    const statusClass = journal.status === 'success' ? 'success'
        : journal.status === 'failed' ? 'error'
        : 'info';
    const deletedRows = (journal.entries || []).filter(e => e.result === 'deleted').length;
    const failRows = (journal.entries || []).filter(e => e.result === 'failed').length;
    const skipRows = (journal.entries || []).filter(e => e.result === 'skipped').length;
    const durationPart = typeof elapsedMs === 'number'
        ? ` &middot; elapsed <strong>${formatDuration(elapsedMs)}</strong>`
        : '';
    el.innerHTML = `
        <div class="execute-summary card">
            <h3 style="margin-top:0">Delete result — ${escapeHtml(journal.status || 'unknown')}</h3>
            <div class="sql-status ${statusClass}">
                ${escapeHtml(journal.errorMessage || (journal.status === 'success' ? 'All rows deleted.' : ''))}
            </div>
            <div class="execute-summary-line">
                <strong>${deletedRows}</strong> row${deletedRows === 1 ? '' : 's'} deleted
                &middot; <strong>${failRows}</strong> failed
                ${skipRows > 0 ? `&middot; <strong>${skipRows}</strong> skipped` : ''}${durationPart}
                &middot; job <code>${escapeHtml(journal.jobId || '')}</code>
            </div>
            <details>
                <summary>Show full journal (${(journal.entries || []).length} entries)</summary>
                <div class="sql-table-wrapper">
                    <table class="sql-table">
                        <thead><tr><th>#</th><th>Entity</th><th>Table</th><th>Deleted ID</th><th>HTTP</th><th>Result</th><th>Message</th></tr></thead>
                        <tbody>
                            ${(journal.entries || []).map(e => `
                                <tr>
                                    <td>${e.order}</td>
                                    <td>${escapeHtml(e.entityName || '')}</td>
                                    <td>${escapeHtml(e.tableName || '')}</td>
                                    <td>${escapeHtml(String(e.deletedId ?? ''))}</td>
                                    <td>${e.httpStatus || '—'}</td>
                                    <td><span class="badge ${e.result === 'deleted' ? '' : 'badge-danger'}">${escapeHtml(e.result)}</span></td>
                                    <td class="journal-message-cell" title="${escapeHtml(e.message || '')}">${escapeHtml(e.message || '')}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            </details>
        </div>
    `;
}

function renderEnvSection(env) {
    if (!env) return '';
    return `
        <div class="plan-env-grid">
            <div class="plan-env plan-env-target">
                <div class="plan-env-title">${escapeHtml(env.label || 'Delete from')}</div>
                <div class="plan-env-label">${escapeHtml(env.env || '')}</div>
                <div class="plan-env-url">${escapeHtml(env.baseUrl || '')}</div>
                ${env.dbName ? `<div class="plan-env-url">DB: <code>${escapeHtml(env.dbName)}</code></div>` : ''}
            </div>
        </div>
    `;
}

function renderNotes(title, kind, items) {
    if (!items.length) return '';
    return `
        <details class="plan-notes plan-notes-${kind}" open>
            <summary><strong>${escapeHtml(title)}</strong> — ${items.length}</summary>
            <ul>${items.map(msg => `<li>${escapeHtml(msg)}</li>`).join('')}</ul>
        </details>
    `;
}

function renderSteps(steps) {
    if (!steps.length) return '<p class="muted">No steps to display.</p>';
    return `
        <details class="plan-steps-wrapper" open>
            <summary class="plan-steps-summary">
                <strong>Tables in delete order</strong>
                <span class="muted-inline">— children first, ${steps.length} table${steps.length === 1 ? '' : 's'}</span>
            </summary>
            <div class="plan-steps">
                ${steps.map(s => `
                    <div class="plan-step">
                        <div class="plan-step-head">
                            <span class="plan-step-num">${s.order}</span>
                            <div class="plan-step-title">
                                <div class="plan-step-name">
                                    <strong>${escapeHtml(s.tableName)}</strong>
                                    <span class="plan-step-table">(${s.rowCount} row${s.rowCount === 1 ? '' : 's'})</span>
                                </div>
                            </div>
                            ${s.pkColumn ? `<span class="badge badge-muted">PK: ${escapeHtml(s.pkColumn)}</span>` : ''}
                        </div>
                        ${s.notes ? `<p class="plan-step-notes">${escapeHtml(s.notes)}</p>` : ''}
                        <details class="plan-step-body">
                            <summary>DELETE preview</summary>
                            <pre class="plan-json">${escapeHtml(s.sampleDeleteSql || '')}</pre>
                        </details>
                    </div>
                `).join('')}
            </div>
        </details>
    `;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
