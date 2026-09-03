import { planImport, executeImport, getActiveProfile } from '../api.js';

const state = {
    profile: null,
    lastPlan: null,
};

export const appImportView = {
    title: 'Import App',
    render() {
        return `
            <h2>Import App</h2>
            <p>Pick a source app, choose the environments, and preview the import. The tool walks the FK graph from your profile's root table and gathers every row belonging to that app — no facade endpoint, no JSON tree guessing. Execution writes into the target env via SQL.</p>

            <div class="import-form card">
                <div class="env-row">
                    <div class="env-picker">
                        <label class="env-label">Source (read from)</label>
                        <select id="source-env" class="select">
                            <option value="design">Design</option>
                            <option value="sandbox">Sandbox</option>
                        </select>
                    </div>
                    <div class="env-arrow" aria-hidden="true">→</div>
                    <div class="env-picker">
                        <label class="env-label">Target (write to)</label>
                        <select id="target-env" class="select">
                            <option value="sandbox" selected>Sandbox</option>
                            <option value="design">Design</option>
                        </select>
                    </div>
                </div>
                <div class="form-inline">
                    <label for="import-lookup-col">Look up by</label>
                    <select id="import-lookup-col" class="select">
                        <option value="" selected>Application ID (root PK)</option>
                        <option value="applicationNumber">Application Number</option>
                    </select>
                    <label for="app-id-input">Value</label>
                    <input type="text" id="app-id-input" placeholder="e.g., 6901" autocomplete="off">
                    <button class="btn" id="btn-plan-import">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                            <path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>
                        </svg>
                        Preview import plan
                    </button>
                </div>
                <p class="form-help" id="fetch-help"></p>
                <p class="form-help">
                    <strong>Auto-cleanup:</strong> before insert, the tool deletes any existing instance
                    of this app in the target env (using the same lookup key). Recommended lookup:
                    <em>Application Number</em> — stable across environments.
                </p>
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

        const planBtn = document.getElementById('btn-plan-import');
        const input = document.getElementById('app-id-input');
        const sourceSel = document.getElementById('source-env');
        const targetSel = document.getElementById('target-env');

        if (!active) {
            document.getElementById('plan-results').innerHTML = `<p class="muted">No active profile.
                <a href="#/profiles">Add and activate one</a> before importing an app.</p>`;
            planBtn.disabled = true;
            input.disabled = true;
            return;
        }

        const missing = [];
        if (!active.designBaseUrl) missing.push('design base URL');
        if (!active.sandboxBaseUrl) missing.push('sandbox base URL');
        if (!active.rootTableName) missing.push('root table name');
        if (!active.dbServiceName && !active.sqlDbName) missing.push('DB service name or SQL DB name');
        if (missing.length > 0) {
            document.getElementById('plan-results').innerHTML = `<p class="muted">Active profile is missing:
                <strong>${missing.map(escapeHtml).join(', ')}</strong>.
                <a href="#/profiles">Edit the profile</a> to add these before importing.</p>`;
            planBtn.disabled = true;
            input.disabled = true;
            return;
        }

        // Disable env options for envs the profile has no URL for
        [...sourceSel.options, ...targetSel.options].forEach(opt => {
            if (opt.value === 'design' && !active.designBaseUrl) opt.disabled = true;
            if (opt.value === 'sandbox' && !active.sandboxBaseUrl) opt.disabled = true;
        });

        updateHelpText();
        sourceSel.addEventListener('change', updateHelpText);
        targetSel.addEventListener('change', updateHelpText);
        const lookupSel = document.getElementById('import-lookup-col');
        if (lookupSel) {
            lookupSel.addEventListener('change', () => {
                updateHelpText();
                input.placeholder = lookupSel.value === 'applicationNumber'
                    ? 'e.g., 202606090606776'
                    : 'e.g., 6901';
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
    const sel = document.getElementById('import-lookup-col');
    return sel && sel.value ? sel.value : '';
}

function updateHelpText() {
    const p = state.profile;
    if (!p) return;
    const el = document.getElementById('fetch-help');
    const sourceEnv = document.getElementById('source-env').value;
    const baseUrl = sourceEnv === 'design' ? p.designBaseUrl : p.sandboxBaseUrl;
    const chosen = currentLookupColumn();
    const filterCol = chosen || p.rootPkFilterColumn || '(root PK)';
    if (baseUrl) {
        el.textContent = `Walks the FK graph starting at ${p.rootTableName} where ${filterCol} = <value>, on ${baseUrl}.`;
    } else {
        el.textContent = '';
    }
}

async function doPlan() {
    const input = document.getElementById('app-id-input');
    const planBtn = document.getElementById('btn-plan-import');
    const sourceEnv = document.getElementById('source-env').value;
    const targetEnv = document.getElementById('target-env').value;
    const planResultsEl = document.getElementById('plan-results');
    const appId = input.value.trim();

    if (!appId) {
        showStatus('Enter an application ID before previewing.', 'error');
        return;
    }

    planBtn.disabled = true;
    const originalHtml = planBtn.innerHTML;
    planBtn.textContent = 'Planning…';
    showStatus('Fetching rows from source env and building plan…', 'info');
    planResultsEl.innerHTML = '';

    const lookupColumn = currentLookupColumn();
    const started = Date.now();
    try {
        const plan = await planImport({ appId, lookupColumn, sourceEnv, targetEnv });
        const elapsed = Date.now() - started;
        if (!plan || plan.totalTables === 0) {
            showStatus(`Plan returned 0 tables in ${elapsed} ms — see notes below.`, 'error');
        } else {
            const w = (plan.warnings && plan.warnings.length) || 0;
            const warnPart = w > 0 ? ` (${w} note${w === 1 ? '' : 's'})` : '';
            showStatus(`Plan built — ${plan.totalTables} table${plan.totalTables === 1 ? '' : 's'}, ${plan.totalRows} row${plan.totalRows === 1 ? '' : 's'} in ${elapsed} ms${warnPart}. No writes performed.`, 'success');
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
    const facadeHtml = renderFacadeChecks(plan.facadeChecks || []);
    const envHtml = renderEnvSection(plan.source, plan.target);
    const stepsHtml = renderSteps(plan.steps || []);
    const refRemapHtml = renderReferenceRemaps(plan.referenceRemaps || []);
    const sentinelHtml = renderSentinelRemaps(plan.sentinelRemaps || []);
    const canExecute = plan.totalRows > 0;

    el.innerHTML = `
        <div class="plan-summary card">
            <h3 style="margin-top:0">Dry-run import plan</h3>
            <p class="plan-summary-line">
                <strong>${plan.totalTables}</strong> table${plan.totalTables === 1 ? '' : 's'}
                &middot; <strong>${plan.totalRows}</strong> row${plan.totalRows === 1 ? '' : 's'}
                &middot; <span class="muted-inline">read-only — no writes to any environment</span>
            </p>
            ${envHtml}
            ${refRemapHtml}
            ${sentinelHtml}
            ${warningsHtml}
            ${facadeHtml}
            <div class="plan-actions">
                <button class="btn danger" id="btn-execute-plan" ${canExecute ? '' : 'disabled'}
                        title="${canExecute ? 'Open confirmation dialog' : 'Nothing to execute'}">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <polygon points="5 3 19 12 5 21 5 3"/>
                    </svg>
                    Execute this plan…
                </button>
                <span class="plan-actions-hint">Writes to target env. A confirmation dialog will appear.</span>
            </div>
        </div>
        ${stepsHtml}
        <div id="execute-results"></div>
    `;

    const btn = document.getElementById('btn-execute-plan');
    if (btn && canExecute) btn.addEventListener('click', openExecuteModal);
}

/* ------------ Execute (confirmation modal + POST) ------------ */

function openExecuteModal() {
    const plan = state.lastPlan;
    if (!plan) return;
    const appId = document.getElementById('app-id-input').value.trim();
    const sourceEnv = document.getElementById('source-env').value;
    const targetEnv = document.getElementById('target-env').value;

    document.getElementById('confirm-profile').textContent = state.profile ? state.profile.name : '(unknown)';
    document.getElementById('confirm-envs').textContent = `${sourceEnv}  →  ${targetEnv}`;
    document.getElementById('confirm-target-url').textContent =
        (plan.target && plan.target.baseUrl) ? plan.target.baseUrl : '(unset)';
    document.getElementById('confirm-appid').textContent = appId || '(none)';
    document.getElementById('confirm-steps').textContent = `${plan.totalRows} row${plan.totalRows === 1 ? '' : 's'} across ${plan.totalTables} table${plan.totalTables === 1 ? '' : 's'}`;

    const input = document.getElementById('confirm-input');
    const doBtn = document.getElementById('btn-do-execute');
    input.value = '';
    doBtn.disabled = true;

    const lookupColumn = currentLookupColumn();
    input.oninput = () => { doBtn.disabled = input.value.trim() !== appId; };
    document.getElementById('btn-cancel-execute').onclick = closeExecuteModal;
    doBtn.onclick = () => runExecute(appId, sourceEnv, targetEnv, input.value.trim(), lookupColumn);

    document.getElementById('execute-modal').classList.remove('hidden');
    setTimeout(() => input.focus(), 10);
}

function closeExecuteModal() {
    document.getElementById('execute-modal').classList.add('hidden');
}

async function runExecute(appId, sourceEnv, targetEnv, confirmToken, lookupColumn) {
    const doBtn = document.getElementById('btn-do-execute');
    const cancelBtn = document.getElementById('btn-cancel-execute');
    doBtn.disabled = true;
    cancelBtn.disabled = true;
    doBtn.textContent = 'Executing…';

    try {
        const journal = await executeImport({
            appId, lookupColumn, sourceEnv, targetEnv,
            confirm: true, confirmToken,
        });
        closeExecuteModal();
        renderExecuteResult(journal);
        const st = journal.status;
        const entryCount = (journal.entries || []).length;
        let toastMsg, toastKind;
        if (st === 'success') {
            toastMsg = `Import complete — ${entryCount} rows written.`;
            toastKind = 'success';
        } else if (st === 'failed-rolled-back') {
            toastMsg = `Import halted — target rolled back cleanly. See details below.`;
            toastKind = 'info';
        } else {
            toastMsg = `Import halted after ${entryCount} step(s); rollback incomplete. See details.`;
            toastKind = 'error';
        }
        (window.showToast || alert)(toastMsg, toastKind, 7000);
    } catch (err) {
        (window.showToast || alert)('Execute failed: ' + err.message, 'error');
    } finally {
        doBtn.disabled = false;
        cancelBtn.disabled = false;
        doBtn.textContent = 'Execute now';
    }
}

function renderExecuteResult(journal) {
    const el = document.getElementById('execute-results');
    if (!el || !journal) return;
    const statusClass = journal.status === 'success' ? 'success'
        : journal.status === 'failed' ? 'error'
        : journal.status === 'failed-rolled-back' ? 'info'
        : 'info';
    const successRows = (journal.entries || []).filter(e => e.result === 'created').length;
    const failRows = (journal.entries || []).filter(e => e.result === 'failed').length;
    el.innerHTML = `
        <div class="execute-summary card">
            <h3 style="margin-top:0">Import result — ${escapeHtml(journal.status || 'unknown')}</h3>
            <div class="sql-status ${statusClass}">
                ${escapeHtml(journal.errorMessage || (journal.status === 'success' ? 'All rows written.' : ''))}
            </div>
            <div class="execute-summary-line">
                <strong>${successRows}</strong> row${successRows === 1 ? '' : 's'} created
                &middot; <strong>${failRows}</strong> failed
                &middot; job <code>${escapeHtml(journal.jobId || '')}</code>
            </div>
            <details>
                <summary>Show full journal (${(journal.entries || []).length} entries)</summary>
                <div class="sql-table-wrapper">
                    <table class="sql-table">
                        <thead><tr><th>#</th><th>Entity</th><th>Table</th><th>Source ID</th><th>Target ID</th><th>Status</th><th>Result</th><th>Message</th></tr></thead>
                        <tbody>
                            ${(journal.entries || []).map(e => `
                                <tr>
                                    <td>${e.order}</td>
                                    <td>${escapeHtml(e.entityName || '')}</td>
                                    <td>${escapeHtml(e.tableName || '')}</td>
                                    <td>${escapeHtml(String(e.sourceId ?? ''))}</td>
                                    <td>${escapeHtml(String(e.targetId ?? ''))}</td>
                                    <td>${e.httpStatus || '—'}</td>
                                    <td><span class="badge ${e.result === 'created' ? '' : 'badge-danger'}">${escapeHtml(e.result)}</span></td>
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

function renderEnvSection(source, target) {
    if (!source && !target) return '';
    return `
        <div class="plan-env-grid">
            ${source ? envCard('Source (read from)', source, 'source') : ''}
            ${target ? envCard('Target (write to)', target, 'target') : ''}
        </div>
    `;
}

function envCard(title, env, kind) {
    return `
        <div class="plan-env plan-env-${kind}">
            <div class="plan-env-title">${escapeHtml(title)}</div>
            <div class="plan-env-label">${escapeHtml(env.label || '')}</div>
            <div class="plan-env-url">${escapeHtml(env.baseUrl || '')}</div>
            ${env.dbName ? `<div class="plan-env-url">DB: <code>${escapeHtml(env.dbName)}</code></div>` : ''}
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

/**
 * Render the facade cross-check as a compact table. Advisory only — never
 * gates execute. Colour-codes status so mismatches stand out.
 */
function renderReferenceRemaps(items) {
    if (!items || !items.length) return '';
    const sourceBadge = (s) => {
        if (s === 'auto') return '<span class="badge badge-muted">auto</span>';
        if (s === 'user') return '<span class="badge">user</span>';
        if (s === 'override') return '<span class="badge">override</span>';
        return `<span class="badge">${escapeHtml(s || '')}</span>`;
    };
    return `
        <details class="plan-notes plan-notes-info" open>
            <summary>
                <strong>Reference-table remapping</strong> — ${items.length} table${items.length === 1 ? '' : 's'}
                <span class="muted-inline">(FK ids resolved by natural key against target env)</span>
            </summary>
            <div class="sql-table-wrapper" style="max-height:340px">
                <table class="sql-table">
                    <thead><tr><th>Table</th><th>Natural key</th><th>Active filter</th><th>Source</th></tr></thead>
                    <tbody>
                        ${items.map(r => `
                            <tr>
                                <td><code>${escapeHtml(r.tableName)}</code></td>
                                <td>${(r.naturalKey || []).map(c => `<code>${escapeHtml(c)}</code>`).join(' + ') || '<span class="muted-inline">—</span>'}</td>
                                <td>${r.activeFilter ? `<code>${escapeHtml(r.activeFilter)}</code>` : '<span class="muted-inline">—</span>'}</td>
                                <td>${sourceBadge(r.source)}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>
            <p class="muted" style="margin-top:8px;font-size:12px">
                Auto-detected from target-env unique indexes. To override a specific table, add its config to the profile.
            </p>
        </details>
    `;
}

function renderSentinelRemaps(items) {
    if (!items || !items.length) return '';
    return `
        <details class="plan-notes plan-notes-info" open>
            <summary>
                <strong>Sentinel remap</strong> — ${items.length} table${items.length === 1 ? '' : 's'}
                <span class="muted-inline">(audit-column FKs like CreatedBy/UpdatedBy substituted with any target row)</span>
            </summary>
            <div class="sql-table-wrapper" style="max-height:240px">
                <table class="sql-table">
                    <thead><tr><th>Target table</th><th>Audit columns pointing to it</th></tr></thead>
                    <tbody>
                        ${items.map(r => `
                            <tr>
                                <td><code>${escapeHtml(r.tableName)}</code></td>
                                <td>${(r.auditColumns || []).map(c => `<code>${escapeHtml(c)}</code>`).join(', ') || '<span class="muted-inline">—</span>'}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>
            <p class="muted" style="margin-top:8px;font-size:12px">
                For each of these tables, one row is fetched from the target env at execute time.
                All audit-column FKs pointing to that table get their source id replaced with the sentinel id.
                Row identity of "who created/updated the row" isn't preserved across envs.
            </p>
        </details>
    `;
}

function renderFacadeChecks(items) {
    if (!items || !items.length) return '';
    const good = items.filter(i => i.status === 'match').length;
    const total = items.length;
    const anyIssue = items.some(i => i.status !== 'match');
    return `
        <details class="plan-notes plan-notes-${anyIssue ? 'warning' : 'info'}" ${anyIssue ? 'open' : ''}>
            <summary>
                <strong>Facade cross-check</strong> — ${good}/${total} match
                <span class="muted-inline">(advisory — compares FAWB's facade coverage against the FK-walk)</span>
            </summary>
            <div class="sql-table-wrapper" style="max-height:340px">
                <table class="sql-table">
                    <thead><tr><th>Facade key</th><th>Table</th><th>Facade #</th><th>Walk #</th><th>Status</th></tr></thead>
                    <tbody>
                        ${items.map(c => `
                            <tr>
                                <td><code>${escapeHtml(c.facadeKey)}</code></td>
                                <td>${c.resolvedTable ? `<code>${escapeHtml(c.resolvedTable)}</code>` : '<span class="muted-inline">—</span>'}</td>
                                <td>${c.facadeCount}</td>
                                <td>${c.fkWalkCount}</td>
                                <td>${renderCrossCheckStatus(c.status)}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>
        </details>
    `;
}

function renderCrossCheckStatus(status) {
    const label = escapeHtml(status || '');
    const cls = status === 'match' ? '' : 'badge-danger';
    return `<span class="badge ${cls}">${label}</span>`;
}

function renderSteps(steps) {
    if (!steps.length) return '<p class="muted">No steps to display.</p>';
    return `
        <details class="plan-steps-wrapper" open>
            <summary class="plan-steps-summary">
                <strong>Tables in insert order</strong>
                <span class="muted-inline">— parents first, ${steps.length} table${steps.length === 1 ? '' : 's'}</span>
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
                            <span class="badge badge-muted">${escapeHtml(s.pkStrategy)} PK</span>
                        </div>
                        ${s.notes ? `<p class="plan-step-notes">${escapeHtml(s.notes)}</p>` : ''}
                        <details class="plan-step-body">
                            <summary>SQL preview &middot; ${(s.strippedColumns || []).length} stripped &middot; ${(s.fkColumns || []).length} FK remap${(s.fkColumns || []).length === 1 ? '' : 's'}</summary>
                            <pre class="plan-json">${escapeHtml(s.sampleInsertSql || '')}</pre>
                            ${renderStrippedList(s.strippedColumns || [])}
                            ${renderFkList(s.fkColumns || [])}
                        </details>
                    </div>
                `).join('')}
            </div>
        </details>
    `;
}

function renderStrippedList(items) {
    if (!items.length) return '';
    return `
        <div class="plan-sublist">
            <div class="plan-sublist-title">Stripped columns (server-assigned or non-insertable)</div>
            <ul>${items.map(m => `<li>${escapeHtml(m)}</li>`).join('')}</ul>
        </div>
    `;
}

function renderFkList(items) {
    if (!items.length) return '';
    return `
        <div class="plan-sublist">
            <div class="plan-sublist-title">FK remaps (at execute time)</div>
            <ul>${items.map(r =>
                `<li><code>${escapeHtml(r.fkColumn)}</code> → <code>${escapeHtml(r.targetTable)}.${escapeHtml(r.targetPkColumn)}</code></li>`
            ).join('')}</ul>
        </div>
    `;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
