import { executeSql, getActiveProfile } from '../api.js';

const PAGE_SIZE_OPTIONS = [10, 25, 50, 100, 250];
const DEFAULT_PAGE_SIZE = 50;

// Module-scope state for the current result set + pagination.
// Reset in mount() so navigating away and back doesn't leak stale rows.
const sqlState = {
    allRows: [],
    rawData: null,
    requestUrl: null,
    currentPage: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    isTableView: false,
};

export const sqlRunnerView = {
    title: 'SQL Runner',
    render() {
        return `
            <h2>SQL Runner</h2>
            <p>Run SQL against the active profile's environment. Choose sandbox for your dev copy or design for the shared design env.</p>

            <div class="sql-runner">
                <div class="sql-controls">
                    <label for="sql-env">Environment</label>
                    <select id="sql-env" class="select">
                        <option value="sandbox">Sandbox</option>
                        <option value="design">Design</option>
                    </select>
                    <button class="btn" id="btn-run-sql">Run <span class="kbd">Ctrl+Enter</span></button>
                </div>

                <textarea id="sql-input" class="sql-textarea"
                          placeholder="SELECT * FROM APPLICATION_DETAILS LIMIT 100"
                          rows="6" spellcheck="false"></textarea>

                <div id="sql-status" class="sql-status hidden"></div>
                <div id="sql-results" class="sql-results"></div>
            </div>
        `;
    },
    async mount() {
        // Reset per-view state
        sqlState.allRows = [];
        sqlState.rawData = null;
        sqlState.requestUrl = null;
        sqlState.currentPage = 1;
        sqlState.pageSize = DEFAULT_PAGE_SIZE;
        sqlState.isTableView = false;

        const runBtn = document.getElementById('btn-run-sql');
        const textarea = document.getElementById('sql-input');
        const resultsEl = document.getElementById('sql-results');

        // Guardrails: check active profile before allowing a run
        let active;
        try { active = await getActiveProfile(); } catch { active = null; }

        if (!active) {
            resultsEl.innerHTML = `
                <p class="muted">No active profile.
                <a href="#/profiles">Add and activate one</a> to enable SQL runner.</p>`;
            runBtn.disabled = true;
            textarea.disabled = true;
            return;
        }

        // Constrain the env selector to only envs the profile can actually reach.
        // Both envs hit the same runtime executeSQLs endpoint — just different base URLs
        // and typically different ?db= values. Each env needs its own DB name field
        // (falls back to dbServiceName for older / same-name projects).
        const hasDesignDb = !!(active.sqlDbName || active.dbServiceName);
        const hasSandboxDb = !!(active.sandboxSqlDbName || active.dbServiceName);
        const canSandbox = !!active.sandboxBaseUrl && hasSandboxDb;
        const canDesign = !!active.designBaseUrl && hasDesignDb;
        const envSelect = document.getElementById('sql-env');
        envSelect.innerHTML = '';
        if (canSandbox) envSelect.appendChild(makeOption('sandbox', 'Sandbox'));
        if (canDesign) envSelect.appendChild(makeOption('design', 'Design'));
        if (envSelect.options.length === 0) {
            resultsEl.innerHTML = `
                <p class="muted">Active profile can't run SQL — needs at least one base URL
                (<code>sandboxBaseUrl</code> or <code>designBaseUrl</code>) plus a matching
                DB name (<code>sqlDbName</code> for design, <code>sandboxSqlDbName</code> for
                sandbox, or <code>dbServiceName</code> as a fallback).
                <a href="#/profiles">Edit the profile</a>.</p>`;
            runBtn.disabled = true;
            textarea.disabled = true;
            return;
        }

        runBtn.addEventListener('click', runSql);
        textarea.addEventListener('keydown', (e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
                e.preventDefault();
                runSql();
            }
        });
    },
};

function makeOption(value, label) {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    return opt;
}

async function runSql() {
    const env = document.getElementById('sql-env').value;
    const sql = document.getElementById('sql-input').value.trim();
    const runBtn = document.getElementById('btn-run-sql');
    const resultsEl = document.getElementById('sql-results');

    if (!sql) {
        showStatus('Enter a SQL query first.', 'error');
        return;
    }

    runBtn.disabled = true;
    const originalHtml = runBtn.innerHTML;
    runBtn.textContent = 'Running…';
    showStatus('Running query…', 'info');
    resultsEl.innerHTML = '';

    const started = Date.now();
    try {
        const result = await executeSql({ env, sql });
        const elapsed = Date.now() - started;

        if (!result.success) {
            const detail = result.error || 'Unknown error';
            showStatus(`Failed${result.status ? ` (HTTP ${result.status})` : ''}: ${detail}`, 'error');
            resultsEl.innerHTML = '';
            if (result.body) {
                resultsEl.innerHTML = `<pre class="sql-raw">${escapeHtml(String(result.body))}</pre>`;
            }
            if (result.requestUrl) {
                appendRequestUrlToggle(resultsEl, result.requestUrl);
            }
            if (result.status === 401) {
                window.dispatchEvent(new CustomEvent('token-expired'));
            }
            return;
        }

        renderResults(result.data, result.requestUrl);
        const rowCount = countRows(result.data);
        showStatus(`Success — ${rowCount} row${rowCount === 1 ? '' : 's'} in ${elapsed} ms`, 'success');
    } catch (err) {
        showStatus(`Failed: ${err.message}`, 'error');
    } finally {
        runBtn.disabled = false;
        runBtn.innerHTML = originalHtml;
    }
}

function showStatus(msg, kind) {
    const el = document.getElementById('sql-status');
    el.textContent = msg;
    el.className = `sql-status ${kind}`;
    el.classList.remove('hidden');
}

function countRows(data) {
    return Array.isArray(data) ? data.length : 0;
}

/* ----------- Rendering ----------- */

function renderResults(data, requestUrl) {
    sqlState.rawData = data;   // preserved verbatim for the "Show raw response" toggle
    sqlState.requestUrl = requestUrl;
    sqlState.currentPage = 1;

    const rows = unwrapFawbResponse(data);
    if (rows.length > 0) {
        sqlState.allRows = rows;
        sqlState.isTableView = true;
    } else {
        sqlState.allRows = [];
        sqlState.isTableView = false;
    }
    fullRender();
}

/**
 * FAWB's runtime executeSQLs returns an array of per-row envelopes:
 *   [{"sql": null, "response": "{\"col\":val,...}"}, ...]
 * The `response` field is a JSON-encoded STRING that needs a second JSON.parse.
 * Same shape for both design and sandbox now that they share the endpoint.
 *
 * Returns a flat list of row objects; returns [] if the shape doesn't match
 * (caller falls back to raw JSON display).
 */
function unwrapFawbResponse(data) {
    if (!Array.isArray(data) || data.length === 0) return [];
    const rows = [];
    for (const env of data) {
        if (!env || typeof env !== 'object') continue;

        // Envelope shape check — must have a `response` field to be one of ours
        if (!('response' in env)) return [];  // unrecognised — fall through to raw

        let parsed = env.response;
        if (typeof parsed === 'string') {
            try {
                parsed = JSON.parse(parsed);
            } catch {
                parsed = { _rawString: env.response };  // keep something displayable
            }
        }

        if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
            rows.push(parsed);
        } else if (Array.isArray(parsed)) {
            // Unusual: `response` was itself an array of rows. Flatten.
            for (const r of parsed) {
                if (r && typeof r === 'object' && !Array.isArray(r)) rows.push(r);
            }
        }
    }
    return rows;
}

// Full render: builds the toolbar, table+pagination container, and debug toggles.
// Subsequent pagination interactions only replace #sql-table-and-pagination so
// the debug toggles keep their open/closed state.
function fullRender() {
    const el = document.getElementById('sql-results');
    const rawJson = JSON.stringify(sqlState.rawData, null, 2);
    const toolbar = sqlState.isTableView ? renderToolbarHtml() : '';
    el.innerHTML = `
        ${toolbar}
        <div id="sql-table-and-pagination"></div>
        <div id="sql-debug">
            <details class="sql-raw-toggle">
                <summary>Show raw response JSON</summary>
                <pre class="sql-raw">${escapeHtml(rawJson)}</pre>
            </details>
            ${sqlState.requestUrl ? `
                <details class="sql-raw-toggle">
                    <summary>Show request URL</summary>
                    <div class="sql-request-url">
                        <a href="${escapeHtml(sqlState.requestUrl)}" target="_blank" rel="noopener">
                            ${escapeHtml(sqlState.requestUrl)}
                        </a>
                    </div>
                </details>
            ` : ''}
        </div>
    `;
    if (sqlState.isTableView) wireToolbar();
    renderTablePart();
}

function renderToolbarHtml() {
    return `
        <div class="sql-results-toolbar">
            <div class="export-dropdown">
                <button class="btn btn-small secondary export-trigger" id="btn-export-trigger" type="button" aria-haspopup="true" aria-expanded="false" title="Export">
                    <svg class="icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
                        <polyline points="7 10 12 15 17 10"/>
                        <line x1="12" y1="15" x2="12" y2="3"/>
                    </svg>
                    <span>Export</span>
                    <span class="chevron" aria-hidden="true">▾</span>
                </button>
                <div class="export-menu hidden" id="export-menu" role="menu">
                    <button data-format="csv" type="button" role="menuitem">CSV</button>
                    <button data-format="xlsx" type="button" role="menuitem">Excel (.xlsx)</button>
                    <button data-format="json" type="button" role="menuitem">JSON</button>
                </div>
            </div>
        </div>
    `;
}

function wireToolbar() {
    const trigger = document.getElementById('btn-export-trigger');
    const menu = document.getElementById('export-menu');
    if (!trigger || !menu) return;

    const open = () => { menu.classList.remove('hidden'); trigger.setAttribute('aria-expanded', 'true'); };
    const close = () => { menu.classList.add('hidden'); trigger.setAttribute('aria-expanded', 'false'); };

    trigger.addEventListener('click', (e) => {
        e.stopPropagation();
        if (menu.classList.contains('hidden')) open(); else close();
    });
    menu.querySelectorAll('button[data-format]').forEach(item => {
        item.addEventListener('click', () => {
            const format = item.dataset.format;
            close();
            exportResults(format, trigger);
        });
    });
    // Close on outside click
    document.addEventListener('click', function outsideClose(e) {
        if (!menu.contains(e.target) && !trigger.contains(e.target)) close();
    });
    // Close on Escape
    document.addEventListener('keydown', function escClose(e) {
        if (e.key === 'Escape') close();
    });
}

async function exportResults(format, btn) {
    if (!sqlState.isTableView || sqlState.allRows.length === 0) {
        alert('Nothing to export.');
        return;
    }
    const originalText = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Exporting…';
    try {
        const columns = computeColumns(sqlState.allRows);
        const filename = `sql-result-${nowStamp()}`;
        if (format === 'csv') {
            downloadBlob(toCsv(columns, sqlState.allRows), `${filename}.csv`, 'text/csv;charset=utf-8');
        } else if (format === 'json') {
            downloadBlob(JSON.stringify(sqlState.allRows, null, 2), `${filename}.json`, 'application/json');
        } else if (format === 'xlsx') {
            await exportXlsx(columns, sqlState.allRows, filename);
        }
    } catch (err) {
        alert(`Export failed: ${err.message}`);
    } finally {
        btn.disabled = false;
        btn.textContent = originalText;
    }
}

function computeColumns(rows) {
    const cols = [];
    const seen = new Set();
    for (const row of rows) {
        for (const k of Object.keys(row)) {
            if (!seen.has(k)) { seen.add(k); cols.push(k); }
        }
    }
    return cols;
}

function toCsv(columns, rows) {
    const escape = (v) => {
        if (v === null || v === undefined) return '';
        const s = typeof v === 'object' ? JSON.stringify(v) : String(v);
        return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
    };
    const lines = [columns.map(escape).join(',')];
    for (const row of rows) {
        lines.push(columns.map(c => escape(row[c])).join(','));
    }
    // BOM helps Excel display UTF-8 correctly on Windows
    return '﻿' + lines.join('\r\n');
}

async function exportXlsx(columns, rows, filenameBase) {
    const res = await fetch('/api/sql/export/xlsx', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ columns, rows }),
    });
    if (!res.ok) {
        const err = await res.text().catch(() => `HTTP ${res.status}`);
        throw new Error(err);
    }
    const blob = await res.blob();
    downloadBlobObject(blob, `${filenameBase}.xlsx`);
}

function downloadBlob(content, filename, mimeType) {
    downloadBlobObject(new Blob([content], { type: mimeType }), filename);
}

function downloadBlobObject(blob, filename) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // Slight delay before revoking so the browser has consumed the URL
    setTimeout(() => URL.revokeObjectURL(url), 500);
}

function nowStamp() {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

// Renders only the table + pagination — cheap enough to re-run on every
// page/size change without touching the debug toggles.
function renderTablePart() {
    const container = document.getElementById('sql-table-and-pagination');
    if (!container) return;

    if (!sqlState.isTableView) {
        if (Array.isArray(sqlState.rawData) && sqlState.rawData.length === 0) {
            container.innerHTML = '<p class="muted">Query returned 0 rows.</p>';
        } else {
            container.innerHTML = '<p class="muted">Unrecognised response shape — see raw payload below.</p>';
        }
        return;
    }

    const total = sqlState.allRows.length;
    const totalPages = Math.max(1, Math.ceil(total / sqlState.pageSize));
    if (sqlState.currentPage > totalPages) sqlState.currentPage = totalPages;
    if (sqlState.currentPage < 1) sqlState.currentPage = 1;

    const start = (sqlState.currentPage - 1) * sqlState.pageSize;
    const end = Math.min(start + sqlState.pageSize, total);
    const pageRows = sqlState.allRows.slice(start, end);

    container.innerHTML = renderTableHtml(pageRows) + renderPaginationHtml(start, end, total, totalPages);
    wirePagination(totalPages);

    // Scroll the table body back to the top when the page changes
    const wrapper = container.querySelector('.sql-table-wrapper');
    if (wrapper) wrapper.scrollTop = 0;
}

function renderTableHtml(rows) {
    // Union of keys across visible rows (so late-appearing columns aren't dropped)
    const cols = [];
    const seen = new Set();
    for (const row of rows) {
        for (const k of Object.keys(row)) {
            if (!seen.has(k)) { seen.add(k); cols.push(k); }
        }
    }
    return `
        <div class="sql-table-wrapper">
            <table class="sql-table">
                <thead>
                    <tr>${cols.map(c => `<th>${escapeHtml(c)}</th>`).join('')}</tr>
                </thead>
                <tbody>
                    ${rows.map(row => `
                        <tr>${cols.map(c => `<td>${formatCell(row[c])}</td>`).join('')}</tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

function renderPaginationHtml(startIdx, endIdx, total, totalPages) {
    const cur = sqlState.currentPage;
    const size = sqlState.pageSize;
    const sizeOptions = PAGE_SIZE_OPTIONS.map(n =>
        `<option value="${n}" ${size === n ? 'selected' : ''}>${n}</option>`
    ).join('');
    return `
        <div class="pagination">
            <div class="pagination-controls">
                <button class="btn-icon" id="page-first" ${cur === 1 ? 'disabled' : ''} title="First page" aria-label="First page">«</button>
                <button class="btn-icon" id="page-prev" ${cur === 1 ? 'disabled' : ''} title="Previous page" aria-label="Previous page">‹</button>
                <input type="number" id="page-input" value="${cur}" min="1" max="${totalPages}" aria-label="Jump to page">
                <span class="pagination-label">of ${totalPages}</span>
                <button class="btn-icon" id="page-next" ${cur === totalPages ? 'disabled' : ''} title="Next page" aria-label="Next page">›</button>
                <button class="btn-icon" id="page-last" ${cur === totalPages ? 'disabled' : ''} title="Last page" aria-label="Last page">»</button>
            </div>
            <div class="pagination-info">
                Rows <strong>${startIdx + 1}</strong>–<strong>${endIdx}</strong> of <strong>${total}</strong>
            </div>
            <div class="pagination-size">
                <label for="page-size">Rows per page:</label>
                <select id="page-size">${sizeOptions}</select>
            </div>
        </div>
    `;
}

function wirePagination(totalPages) {
    const goTo = (n) => {
        sqlState.currentPage = Math.min(Math.max(1, n), totalPages);
        renderTablePart();
    };
    const on = (id, handler) => {
        const el = document.getElementById(id);
        if (el) el.addEventListener('click', handler);
    };

    on('page-first', () => goTo(1));
    on('page-prev', () => goTo(sqlState.currentPage - 1));
    on('page-next', () => goTo(sqlState.currentPage + 1));
    on('page-last', () => goTo(totalPages));

    const input = document.getElementById('page-input');
    if (input) {
        input.addEventListener('change', () => {
            const n = parseInt(input.value, 10);
            if (Number.isNaN(n)) { input.value = sqlState.currentPage; return; }
            goTo(n);
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); input.blur(); }
        });
    }

    const sizeSelect = document.getElementById('page-size');
    if (sizeSelect) {
        sizeSelect.addEventListener('change', () => {
            const n = parseInt(sizeSelect.value, 10);
            if (Number.isNaN(n) || n <= 0) return;
            // Keep the current top row visible when the page size changes
            const currentStart = (sqlState.currentPage - 1) * sqlState.pageSize;
            sqlState.pageSize = n;
            sqlState.currentPage = Math.floor(currentStart / n) + 1;
            renderTablePart();
        });
    }
}

function appendRequestUrlToggle(container, url) {
    const html = `
        <details class="sql-raw-toggle">
            <summary>Show request URL</summary>
            <div class="sql-request-url">
                <a href="${escapeHtml(url)}" target="_blank" rel="noopener">
                    ${escapeHtml(url)}
                </a>
            </div>
        </details>
    `;
    container.insertAdjacentHTML('beforeend', html);
}

function formatCell(v) {
    if (v === null || v === undefined) return '<span class="cell-null">null</span>';
    if (typeof v === 'object') return escapeHtml(JSON.stringify(v));
    return escapeHtml(String(v));
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
