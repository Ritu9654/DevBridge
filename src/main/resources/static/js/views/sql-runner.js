import { executeSql, getActiveProfile, listDataModelTables, getDataModelTable } from '../api.js';

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
    sql: '',                    // the SQL that produced the current result (for table-name inference)
    env: null,                  // env of the current result
    sortBy: null,               // { col: string, dir: 'asc' | 'desc' }
    filterText: '',             // client-side substring filter
    hiddenColumns: new Set(),   // column names currently hidden
};

/* ---------- Query history (localStorage) ---------- */
const HISTORY_KEY = 'devbridge.sql.history';
const HISTORY_CAP = 50;

function readHistory() {
    try {
        const raw = localStorage.getItem(HISTORY_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? parsed : [];
    } catch { return []; }
}
function pushHistory(entry) {
    const list = readHistory();
    list.unshift({ ...entry, ts: Date.now() });
    if (list.length > HISTORY_CAP) list.length = HISTORY_CAP;
    try { localStorage.setItem(HISTORY_KEY, JSON.stringify(list)); } catch { /* full — ignore */ }
}
function clearHistory() {
    localStorage.removeItem(HISTORY_KEY);
}

/* ---------- Saved queries (name + purpose, persistent) ---------- */
const SAVED_KEY = 'devbridge.sql.saved';

function readSaved() {
    try {
        const raw = localStorage.getItem(SAVED_KEY);
        const parsed = raw ? JSON.parse(raw) : [];
        return Array.isArray(parsed) ? parsed : [];
    } catch { return []; }
}
function writeSaved(list) {
    try { localStorage.setItem(SAVED_KEY, JSON.stringify(list)); } catch { /* full — ignore */ }
}
function saveQuery(entry) {
    const list = readSaved();
    list.unshift({ ...entry, id: uuidLike(), createdAt: Date.now() });
    writeSaved(list);
}
function deleteSavedQuery(id) {
    const list = readSaved().filter(q => q.id !== id);
    writeSaved(list);
}
function uuidLike() {
    if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    return 'q-' + Math.random().toString(36).slice(2) + '-' + Date.now().toString(36);
}

/* ---------- Draft auto-save (survives page refresh) ---------- */
const DRAFT_KEY = 'devbridge.sql.draft';

function saveDraft(sql, env) {
    try { localStorage.setItem(DRAFT_KEY, JSON.stringify({ sql, env, ts: Date.now() })); }
    catch { /* full — ignore */ }
}
function restoreDraft(textareaEl, envSelectEl) {
    if (!textareaEl) return;
    let draft;
    try {
        const raw = localStorage.getItem(DRAFT_KEY);
        draft = raw ? JSON.parse(raw) : null;
    } catch { draft = null; }
    if (!draft || !draft.sql) return;
    textareaEl.value = draft.sql;
    if (envSelectEl && draft.env) {
        const opt = [...envSelectEl.options].find(o => o.value === draft.env);
        if (opt) envSelectEl.value = draft.env;
    }
}

/* ---------- Autocomplete (tables + columns from dataModel) ---------- */
const autocompleteState = {
    items: [],      // { name, kind: 'table' | 'column', hint?: string }
    open: false,
    index: 0,
    matches: [],
    wordStart: 0,
    wordEnd: 0,
};

async function loadAutocompleteData(profileId) {
    if (!profileId) return;
    const summaries = await listDataModelTables(profileId);
    if (!Array.isArray(summaries)) return;
    const items = [];
    const seenCols = new Set();
    for (const t of summaries) {
        if (!t || !t.name) continue;
        items.push({ name: t.name, kind: 'table', hint: t.entityName && t.entityName !== t.name ? t.entityName : '' });
        if (Array.isArray(t.columnNames)) {
            for (const col of t.columnNames) {
                if (!col) continue;
                // De-dup column names (they appear in multiple tables). Attach
                // the first table we see it in as a hint for the user.
                if (!seenCols.has(col.toLowerCase())) {
                    seenCols.add(col.toLowerCase());
                    items.push({ name: col, kind: 'column', hint: t.name });
                }
            }
        }
    }
    autocompleteState.items = items;
}

// Chars that can appear in an identifier — anything else terminates the word
function isIdentChar(c) {
    return /[A-Za-z0-9_]/.test(c);
}

function currentWord(textarea) {
    const val = textarea.value;
    const caret = textarea.selectionStart;
    let start = caret;
    while (start > 0 && isIdentChar(val[start - 1])) start--;
    let end = caret;
    while (end < val.length && isIdentChar(val[end])) end++;
    return { text: val.slice(start, end), start, end };
}

function wireAutocomplete(textarea) {
    const popup = document.getElementById('sql-autocomplete');
    if (!popup) return;

    textarea.addEventListener('keydown', (e) => {
        // Ctrl-Space (or Cmd-Space on macOS) forces the popup open
        if ((e.ctrlKey || e.metaKey) && e.key === ' ') {
            e.preventDefault();
            openAutocomplete(textarea, /*force=*/ true);
            return;
        }
        if (!autocompleteState.open) return;

        if (e.key === 'ArrowDown') {
            e.preventDefault();
            autocompleteState.index = Math.min(autocompleteState.matches.length - 1, autocompleteState.index + 1);
            renderAutocompletePopup(popup, textarea);
            return;
        }
        if (e.key === 'ArrowUp') {
            e.preventDefault();
            autocompleteState.index = Math.max(0, autocompleteState.index - 1);
            renderAutocompletePopup(popup, textarea);
            return;
        }
        if (e.key === 'Enter' || e.key === 'Tab') {
            if (autocompleteState.matches.length > 0) {
                e.preventDefault();
                acceptAutocomplete(textarea);
                return;
            }
        }
        if (e.key === 'Escape') {
            e.preventDefault();
            closeAutocomplete();
            return;
        }
    });

    // On input, refresh matches if popup is open, or auto-open when user
    // starts typing an identifier that has candidates
    textarea.addEventListener('input', () => {
        const word = currentWord(textarea);
        if (autocompleteState.open) {
            if (!word.text) { closeAutocomplete(); return; }
            openAutocomplete(textarea, false);
        } else if (word.text.length >= 2 && autocompleteState.items.length > 0) {
            // Only auto-open when there are 2+ chars typed to avoid noise
            openAutocomplete(textarea, false);
        }
    });

    // Close on blur (with small delay so click on popup item works)
    textarea.addEventListener('blur', () => {
        setTimeout(closeAutocomplete, 150);
    });
    // Close on scroll of results (popup position would drift)
    document.addEventListener('scroll', closeAutocomplete, true);
}

function openAutocomplete(textarea, force) {
    if (autocompleteState.items.length === 0) return;
    const word = currentWord(textarea);
    if (!force && !word.text) { closeAutocomplete(); return; }
    const q = word.text.toLowerCase();
    // Match: prefix first, then substring
    const prefixMatches = [];
    const subMatches = [];
    for (const item of autocompleteState.items) {
        const nlow = item.name.toLowerCase();
        if (!q) { prefixMatches.push(item); continue; }
        if (nlow.startsWith(q)) prefixMatches.push(item);
        else if (nlow.includes(q)) subMatches.push(item);
    }
    const matches = prefixMatches.concat(subMatches).slice(0, 30);
    if (matches.length === 0) { closeAutocomplete(); return; }
    autocompleteState.matches = matches;
    autocompleteState.index = 0;
    autocompleteState.wordStart = word.start;
    autocompleteState.wordEnd = word.end;
    autocompleteState.open = true;
    const popup = document.getElementById('sql-autocomplete');
    renderAutocompletePopup(popup, textarea);
    positionAutocompletePopup(popup, textarea);
    popup.classList.remove('hidden');
}

function renderAutocompletePopup(popup, textarea) {
    popup.innerHTML = autocompleteState.matches.map((item, i) => `
        <div class="sql-ac-item ${i === autocompleteState.index ? 'active' : ''}" data-idx="${i}" role="option">
            <span class="sql-ac-kind sql-ac-kind-${item.kind}">${item.kind === 'table' ? 'T' : 'c'}</span>
            <span class="sql-ac-name">${escapeHtml(item.name)}</span>
            ${item.hint ? `<span class="sql-ac-hint">${escapeHtml(item.hint)}</span>` : ''}
        </div>
    `).join('');
    // Scroll active into view
    const active = popup.querySelector('.sql-ac-item.active');
    if (active) active.scrollIntoView({ block: 'nearest' });
    // Click to accept
    popup.querySelectorAll('.sql-ac-item').forEach(el => {
        el.addEventListener('mousedown', (e) => {
            // mousedown (not click) so it fires before the textarea's blur
            e.preventDefault();
            autocompleteState.index = parseInt(el.dataset.idx, 10);
            acceptAutocomplete(textarea);
        });
    });
}

function acceptAutocomplete(textarea) {
    const pick = autocompleteState.matches[autocompleteState.index];
    if (!pick) { closeAutocomplete(); return; }
    const val = textarea.value;
    const before = val.slice(0, autocompleteState.wordStart);
    const after = val.slice(autocompleteState.wordEnd);
    const insertion = pick.name;
    textarea.value = before + insertion + after;
    const newCaret = before.length + insertion.length;
    textarea.selectionStart = textarea.selectionEnd = newCaret;
    closeAutocomplete();
    textarea.focus();
    saveDraft(textarea.value, document.getElementById('sql-env').value);
}

function closeAutocomplete() {
    autocompleteState.open = false;
    const popup = document.getElementById('sql-autocomplete');
    if (popup) popup.classList.add('hidden');
}

// Position the popup at the caret using a mirror-div. We compute where the
// caret is inside the textarea by rendering a hidden div with the same
// styles and content up to the caret, then reading a marker's bounding rect.
function positionAutocompletePopup(popup, textarea) {
    const rect = textarea.getBoundingClientRect();
    const style = window.getComputedStyle(textarea);
    const mirror = document.createElement('div');
    const copyProps = [
        'boxSizing', 'width', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
        'borderTopWidth', 'borderRightWidth', 'borderBottomWidth', 'borderLeftWidth',
        'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'lineHeight',
        'letterSpacing', 'textTransform', 'wordSpacing', 'whiteSpace', 'wordWrap',
    ];
    copyProps.forEach(p => { mirror.style[p] = style[p]; });
    mirror.style.position = 'absolute';
    mirror.style.visibility = 'hidden';
    mirror.style.top = '0';
    mirror.style.left = '0';
    mirror.style.whiteSpace = 'pre-wrap';
    mirror.style.wordWrap = 'break-word';

    const val = textarea.value;
    const caret = textarea.selectionStart;
    mirror.textContent = val.substring(0, caret);
    const span = document.createElement('span');
    span.textContent = val.substring(caret, caret + 1) || '.';
    mirror.appendChild(span);
    document.body.appendChild(mirror);

    const spanRect = span.getBoundingClientRect();
    const mirrorRect = mirror.getBoundingClientRect();
    const relX = spanRect.left - mirrorRect.left;
    const relY = spanRect.top - mirrorRect.top;
    document.body.removeChild(mirror);

    // Position: caret x/y inside textarea + textarea's viewport offset - scroll
    let x = rect.left + relX - textarea.scrollLeft;
    let y = rect.top + relY - textarea.scrollTop + parseFloat(style.lineHeight || style.fontSize);
    // Keep inside viewport (approximate popup dims 260x260)
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    if (x + 280 > vw) x = vw - 290;
    if (y + 260 > vh) y = rect.top + relY - textarea.scrollTop - 260;
    popup.style.left = Math.max(8, x) + 'px';
    popup.style.top = Math.max(8, y) + 'px';
}

/* ---------- SQL formatter (keyword-based) ---------- */
// Simple keyword-driven reformatter. Puts common clause-starters on their own
// line, indents 2-space, and lightly cleans whitespace. Not a full parser — it
// deliberately leaves string literals and comments intact, and doesn't try to
// re-flow SELECT column lists. Good enough for daily "make this readable".
/* ---------- Line-comment toggle (Ctrl+/ shortcut) ----------
 * Standard editor behavior. Get the line range covered by the current
 * selection (or the cursor's single line if no selection). If ANY of those
 * lines already starts with "-- ", uncomment all of them; otherwise comment
 * them all. Preserves selection after the edit.
 */
function toggleLineComment(textarea) {
    const val = textarea.value;
    const selStart = textarea.selectionStart;
    const selEnd = textarea.selectionEnd;
    // Expand selection to whole lines
    const lineStart = val.lastIndexOf('\n', selStart - 1) + 1;
    const nextNewline = val.indexOf('\n', selEnd);
    const lineEnd = nextNewline < 0 ? val.length : nextNewline;
    const before = val.slice(0, lineStart);
    const after = val.slice(lineEnd);
    const middle = val.slice(lineStart, lineEnd);
    const lines = middle.split('\n');
    // Decide: comment vs uncomment based on whether every non-blank line is
    // already commented. If yes → uncomment. If not → comment all.
    const nonBlankLines = lines.filter(l => l.trim().length > 0);
    const allCommented = nonBlankLines.length > 0 && nonBlankLines.every(l => /^\s*--\s?/.test(l));
    let newLines;
    if (allCommented) {
        // Remove leading `-- ` (and optional space) from each line
        newLines = lines.map(l => l.replace(/^(\s*)--\s?/, '$1'));
    } else {
        // Prefix `-- ` to each non-blank line, preserving indentation
        newLines = lines.map(l => {
            if (l.trim().length === 0) return l;
            const indentMatch = l.match(/^(\s*)/);
            const indent = indentMatch ? indentMatch[1] : '';
            return indent + '-- ' + l.slice(indent.length);
        });
    }
    const newMiddle = newLines.join('\n');
    textarea.value = before + newMiddle + after;
    // Restore an approximate selection covering the same line range
    const newSelStart = lineStart;
    const newSelEnd = lineStart + newMiddle.length;
    textarea.selectionStart = newSelStart;
    textarea.selectionEnd = newSelEnd;
}

function formatSql(src) {
    if (!src) return src;
    // Extract string literals and line comments so we don't touch their contents
    const placeholders = [];
    const stash = (s) => {
        const token = `__DEVBRIDGE_STASH_${placeholders.length}__`;
        placeholders.push(s);
        return token;
    };
    let s = src
        .replace(/--[^\n]*/g, m => stash(m))
        .replace(/\/\*[\s\S]*?\*\//g, m => stash(m))
        .replace(/'(?:''|[^'])*'/g, m => stash(m))
        .replace(/"(?:""|[^"])*"/g, m => stash(m));

    // Collapse whitespace
    s = s.replace(/\s+/g, ' ').trim();

    // Break before top-level clauses
    const clauses = [
        'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT',
        'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'JOIN',
        'ON', 'AND', 'OR',
        'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE FROM',
        'UNION ALL', 'UNION',
    ];
    // Longest first so "INNER JOIN" wins over "JOIN"
    clauses.sort((a, b) => b.length - a.length);
    for (const kw of clauses) {
        const re = new RegExp('\\b' + kw.replace(/ /g, '\\s+') + '\\b', 'gi');
        s = s.replace(re, '\n' + kw);
    }

    // Indent subordinate clauses (AND, OR, ON) once
    s = s.split('\n').map((line, i) => {
        const trimmed = line.trim();
        if (i === 0) return trimmed;
        if (/^(AND|OR|ON)\b/i.test(trimmed)) return '  ' + trimmed;
        return trimmed;
    }).join('\n');

    // Restore stashed literals/comments
    s = s.replace(/__DEVBRIDGE_STASH_(\d+)__/g, (_, i) => placeholders[Number(i)]);
    return s;
}

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
                    <button class="btn secondary" id="btn-explain-sql" type="button" title="Run EXPLAIN on the current query">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
                        Explain
                    </button>
                    <button class="btn secondary" id="btn-format-sql" type="button" title="Reformat the SQL with line breaks and indentation">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="17" y1="10" x2="3" y2="10"/><line x1="21" y1="6" x2="3" y2="6"/><line x1="21" y1="14" x2="3" y2="14"/><line x1="17" y1="18" x2="3" y2="18"/></svg>
                        Format
                    </button>
                    <button class="btn secondary" id="btn-save-sql" type="button" title="Save this query with a name and purpose">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
                        Save
                    </button>
                    <div class="sql-history-wrap">
                        <button class="btn secondary" id="btn-saved" type="button" aria-haspopup="true" aria-expanded="false" title="Show saved queries">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
                            Saved
                        </button>
                        <div class="sql-history-menu hidden" id="sql-saved-menu" role="menu"></div>
                    </div>
                    <div class="sql-history-wrap">
                        <button class="btn secondary" id="btn-history" type="button" aria-haspopup="true" aria-expanded="false" title="Show recent queries">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><polyline points="3 3 3 8 8 8"/><polyline points="12 7 12 12 15 15"/></svg>
                            History
                        </button>
                        <div class="sql-history-menu hidden" id="sql-history-menu" role="menu"></div>
                    </div>
                </div>

                <div class="sql-editor-wrap">
                    <textarea id="sql-input" class="sql-textarea"
                              placeholder="SELECT * FROM APPLICATION_DETAILS LIMIT 100&#10;or use :params like&#10;SELECT * FROM APPLICATION WHERE id = :appId"
                              rows="6" spellcheck="false" autocomplete="off"></textarea>
                    <div id="sql-autocomplete" class="sql-autocomplete hidden" role="listbox"></div>
                </div>

                <div id="sql-params" class="sql-params hidden"></div>

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
        sqlState.sql = '';
        sqlState.env = null;
        sqlState.sortBy = null;
        sqlState.filterText = '';
        sqlState.hiddenColumns = new Set();

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
                return;
            }
            // Ctrl+/ (or Cmd+/) — toggle `-- ` line comment on the current
            // line or every line in the current selection. Standard editor
            // shortcut. Works with our multi-statement splitter: commented
            // lines are skipped, so wrapping a whole statement in `-- ` on
            // each line lets you keep it around and only run the rest.
            if ((e.ctrlKey || e.metaKey) && e.key === '/') {
                e.preventDefault();
                toggleLineComment(textarea);
                saveDraft(textarea.value, document.getElementById('sql-env').value);
                return;
            }
        });

        // Restore draft (SQL text + env)
        restoreDraft(textarea, document.getElementById('sql-env'));

        // Auto-save draft on textarea input + env change
        let draftTimer = null;
        const saveDraftDebounced = () => {
            clearTimeout(draftTimer);
            draftTimer = setTimeout(() => saveDraft(textarea.value, document.getElementById('sql-env').value), 200);
        };
        textarea.addEventListener('input', saveDraftDebounced);
        document.getElementById('sql-env').addEventListener('change', saveDraftDebounced);

        // Format SQL button
        const fmtBtn = document.getElementById('btn-format-sql');
        if (fmtBtn) fmtBtn.addEventListener('click', () => {
            const src = textarea.value;
            if (!src.trim()) return;
            textarea.value = formatSql(src);
            saveDraft(textarea.value, document.getElementById('sql-env').value);
            textarea.focus();
            refreshParamsPanel();
        });

        // Explain button — wraps current SQL with EXPLAIN
        const explainBtn = document.getElementById('btn-explain-sql');
        if (explainBtn) explainBtn.addEventListener('click', runExplain);

        // Named params: refresh panel whenever textarea content changes
        const refreshParams = () => refreshParamsPanel();
        textarea.addEventListener('input', refreshParams);
        // Initial render for any restored draft that already has :params
        refreshParamsPanel();

        // Autocomplete: load table + column names from the profile's dataModel
        loadAutocompleteData(active.id).catch(() => { /* dataModel not uploaded — autocomplete just stays empty */ });
        wireAutocomplete(textarea);

        // Load full dataModel (with FK relations) for FK jump + reverse FK.
        // Async, doesn't block anything — features degrade gracefully if unavailable.
        ensureDataModelLoaded(active.id).catch(() => { /* fine */ });

        const historyBtn = document.getElementById('btn-history');
        if (historyBtn) historyBtn.addEventListener('click', toggleHistoryMenu);
        const savedBtn = document.getElementById('btn-saved');
        if (savedBtn) savedBtn.addEventListener('click', toggleSavedMenu);
        const saveBtn = document.getElementById('btn-save-sql');
        if (saveBtn) saveBtn.addEventListener('click', () => openSaveQueryModal(
                textarea.value, document.getElementById('sql-env').value));
        // Close history / saved on outside click / Escape
        document.addEventListener('click', (e) => {
            ['sql-history-menu', 'sql-saved-menu'].forEach(id => {
                const menu = document.getElementById(id);
                const btnId = id === 'sql-history-menu' ? 'btn-history' : 'btn-saved';
                const btn = document.getElementById(btnId);
                if (!menu || !btn) return;
                if (menu.classList.contains('hidden')) return;
                if (!menu.contains(e.target) && !btn.contains(e.target)) {
                    menu.classList.add('hidden');
                    btn.setAttribute('aria-expanded', 'false');
                }
            });
        });
        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                ['sql-history-menu', 'sql-saved-menu'].forEach(id => {
                    const menu = document.getElementById(id);
                    if (menu && !menu.classList.contains('hidden')) menu.classList.add('hidden');
                });
            }
        });
    },
};

function toggleHistoryMenu() {
    const menu = document.getElementById('sql-history-menu');
    const btn = document.getElementById('btn-history');
    if (!menu) return;
    if (menu.classList.contains('hidden')) {
        renderHistoryMenu(menu);
        menu.classList.remove('hidden');
        if (btn) btn.setAttribute('aria-expanded', 'true');
    } else {
        menu.classList.add('hidden');
        if (btn) btn.setAttribute('aria-expanded', 'false');
    }
}

function toggleSavedMenu() {
    const menu = document.getElementById('sql-saved-menu');
    const btn = document.getElementById('btn-saved');
    if (!menu) return;
    if (menu.classList.contains('hidden')) {
        renderSavedMenu(menu);
        menu.classList.remove('hidden');
        if (btn) btn.setAttribute('aria-expanded', 'true');
    } else {
        menu.classList.add('hidden');
        if (btn) btn.setAttribute('aria-expanded', 'false');
    }
}

function renderSavedMenu(menu) {
    const items = readSaved();
    if (!items.length) {
        menu.innerHTML = `<div class="sql-history-empty">No saved queries yet — click <strong>Save</strong> above to store the current one.</div>`;
        return;
    }
    const rows = items.map((s) => `
        <div class="sql-history-item sql-saved-item" data-id="${escapeHtml(s.id)}" role="menuitem">
            <div class="sql-history-item-top">
                <span class="sql-history-env">${escapeHtml(s.env || '?')}</span>
                <span class="sql-saved-name"><strong>${escapeHtml(s.name || '(unnamed)')}</strong></span>
                <button class="sql-history-clear sql-saved-delete" data-id="${escapeHtml(s.id)}" type="button" title="Delete this saved query">×</button>
            </div>
            ${s.purpose ? `<div class="sql-saved-purpose">${escapeHtml(s.purpose)}</div>` : ''}
            <div class="sql-history-sql">${escapeHtml((s.sql || '').split('\n')[0].slice(0, 90))}${(s.sql || '').length > 90 ? '…' : ''}</div>
        </div>
    `).join('');
    menu.innerHTML = `
        <div class="sql-history-header">
            <strong>Saved queries</strong>
            <span class="muted">${items.length} total</span>
        </div>
        <div class="sql-history-list">${rows}</div>
    `;
    // Clicking a saved item (but not the delete button) loads it into the textarea
    menu.querySelectorAll('.sql-saved-item').forEach(item => {
        item.addEventListener('click', (e) => {
            if (e.target.closest('.sql-saved-delete')) return;
            const id = item.dataset.id;
            const entry = readSaved().find(x => x.id === id);
            if (!entry) return;
            const textarea = document.getElementById('sql-input');
            const envSelect = document.getElementById('sql-env');
            if (textarea) textarea.value = entry.sql || '';
            if (envSelect && entry.env) {
                const opt = [...envSelect.options].find(o => o.value === entry.env);
                if (opt) envSelect.value = entry.env;
            }
            menu.classList.add('hidden');
            const savedBtn = document.getElementById('btn-saved');
            if (savedBtn) savedBtn.setAttribute('aria-expanded', 'false');
            if (textarea) textarea.focus();
        });
    });
    menu.querySelectorAll('.sql-saved-delete').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteSavedQuery(btn.dataset.id);
            renderSavedMenu(menu);
        });
    });
}

function openSaveQueryModal(sql, env) {
    if (!sql || !sql.trim()) {
        (window.showToast || alert)('Textarea is empty — nothing to save.', 'info');
        return;
    }
    closeSaveQueryModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-save-modal';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-panel sql-save-panel">
            <h3>Save query</h3>
            <p class="modal-help">Give this query a name and a short description so you can find it later. Stored in your browser only.</p>
            <label class="form-row">
                <span>Name</span>
                <input type="text" id="sql-save-name" placeholder="e.g., Applications pending review" autocomplete="off" spellcheck="false" required>
            </label>
            <label class="form-row">
                <span>Purpose (optional)</span>
                <textarea id="sql-save-purpose" placeholder="What does this query answer? When would you use it?" rows="3"></textarea>
            </label>
            <div class="form-row">
                <span>SQL</span>
                <pre class="sql-save-preview">${escapeHtml(sql)}</pre>
            </div>
            <div class="modal-actions">
                <button class="btn" data-action="save" type="button">Save</button>
                <button class="btn secondary" data-action="close" type="button">Cancel</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    const nameEl = overlay.querySelector('#sql-save-name');
    const purposeEl = overlay.querySelector('#sql-save-purpose');
    overlay.querySelector('button[data-action="save"]').addEventListener('click', () => {
        const name = (nameEl.value || '').trim();
        if (!name) { nameEl.focus(); return; }
        saveQuery({ name, purpose: (purposeEl.value || '').trim(), sql, env });
        closeSaveQueryModal();
        (window.showToast || alert)(`Saved "${name}".`, 'success', 4000);
    });
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeSaveQueryModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeSaveQueryModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeSaveQueryModal(); document.removeEventListener('keydown', esc); }
    });
    setTimeout(() => nameEl && nameEl.focus(), 30);
}

function closeSaveQueryModal() {
    const existing = document.getElementById('sql-save-modal');
    if (existing) existing.remove();
}

function renderHistoryMenu(menu) {
    const items = readHistory();
    if (!items.length) {
        menu.innerHTML = `<div class="sql-history-empty">No queries yet — run one and it will appear here.</div>`;
        return;
    }
    const rows = items.map((h, i) => {
        const firstLine = String(h.sql || '').split('\n')[0].trim().slice(0, 90);
        const badge = h.ok
            ? `<span class="sql-history-badge ok">${h.rowCount ?? 0} rows</span>`
            : `<span class="sql-history-badge fail">failed</span>`;
        return `
            <button class="sql-history-item" data-idx="${i}" type="button" role="menuitem">
                <div class="sql-history-item-top">
                    <span class="sql-history-env">${escapeHtml(h.env || '?')}</span>
                    ${badge}
                    <span class="sql-history-ts" title="${new Date(h.ts).toISOString()}">${relativeTime(h.ts)}</span>
                </div>
                <div class="sql-history-sql">${escapeHtml(firstLine)}${(h.sql || '').length > 90 ? '…' : ''}</div>
            </button>
        `;
    }).join('');
    menu.innerHTML = `
        <div class="sql-history-header">
            <strong>Recent queries</strong>
            <button class="sql-history-clear" type="button">Clear all</button>
        </div>
        <div class="sql-history-list">${rows}</div>
    `;
    menu.querySelectorAll('.sql-history-item').forEach(btn => {
        btn.addEventListener('click', () => {
            const idx = parseInt(btn.dataset.idx, 10);
            const entry = readHistory()[idx];
            if (!entry) return;
            const textarea = document.getElementById('sql-input');
            const envSelect = document.getElementById('sql-env');
            if (textarea) textarea.value = entry.sql || '';
            if (envSelect && entry.env) {
                const opt = [...envSelect.options].find(o => o.value === entry.env);
                if (opt) envSelect.value = entry.env;
            }
            menu.classList.add('hidden');
            const historyBtn = document.getElementById('btn-history');
            if (historyBtn) historyBtn.setAttribute('aria-expanded', 'false');
            if (textarea) textarea.focus();
        });
    });
    const clearBtn = menu.querySelector('.sql-history-clear');
    if (clearBtn) clearBtn.addEventListener('click', () => {
        clearHistory();
        renderHistoryMenu(menu);
    });
}

function relativeTime(ts) {
    if (!ts) return '';
    const diff = Date.now() - ts;
    const sec = Math.floor(diff / 1000);
    if (sec < 60) return `${sec}s ago`;
    const min = Math.floor(sec / 60);
    if (min < 60) return `${min}m ago`;
    const hr = Math.floor(min / 60);
    if (hr < 24) return `${hr}h ago`;
    const d = Math.floor(hr / 24);
    if (d < 30) return `${d}d ago`;
    return new Date(ts).toLocaleDateString();
}

function makeOption(value, label) {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    return opt;
}

async function runSql() {
    const env = document.getElementById('sql-env').value;
    const rawSql = document.getElementById('sql-input').value.trim();
    const runBtn = document.getElementById('btn-run-sql');
    const resultsEl = document.getElementById('sql-results');

    if (!rawSql) {
        showStatus('Enter a SQL query first.', 'error');
        return;
    }

    // Substitute named parameters (:param) before execution, using values
    // from the params panel below the textarea.
    const paramNames = findNamedParams(rawSql);
    const sql = paramNames.length
        ? substituteNamedParams(rawSql, collectParamValues(paramNames))
        : rawSql;

    // Multi-statement path: split on top-level `;` (quote-aware). If two or
    // more statements land, run each in sequence and render stacked results.
    const stmts = splitStatements(sql);
    if (stmts.length > 1) {
        return runMultiStatements(stmts, env);
    }

    runBtn.disabled = true;
    const originalHtml = runBtn.innerHTML;
    runBtn.textContent = 'Running…';
    showStatus('Running query…', 'info');
    resultsEl.innerHTML = '';

    const started = Date.now();
    let historyEntry = { sql, env, ok: false, elapsedMs: 0, rowCount: 0 };
    try {
        const result = await executeSql({ env, sql });
        const elapsedMs = Date.now() - started;
        const elapsed = formatDuration(elapsedMs);
        historyEntry.elapsedMs = elapsedMs;

        if (!result.success) {
            const detail = result.error || 'Unknown error';
            showStatus(`Failed after ${elapsed}${result.status ? ` (HTTP ${result.status})` : ''}: ${detail}`, 'error');
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

        renderResults(result.data, result.requestUrl, sql, env);
        const rowCount = countRows(result.data);
        historyEntry.ok = true;
        historyEntry.rowCount = rowCount;
        showStatus(`Success — ${rowCount} row${rowCount === 1 ? '' : 's'} in ${elapsed}`, 'success');
    } catch (err) {
        historyEntry.elapsedMs = Date.now() - started;
        const elapsed = formatDuration(historyEntry.elapsedMs);
        showStatus(`Failed after ${elapsed}: ${err.message}`, 'error');
    } finally {
        pushHistory(historyEntry);
        runBtn.disabled = false;
        runBtn.innerHTML = originalHtml;
    }
}

// Split SQL by top-level `;`, respecting single/double-quoted strings and
// line/block comments. Empty statements (from trailing `;`) are dropped.
function splitStatements(src) {
    const out = [];
    let cur = '';
    let i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i];
        const c2 = i + 1 < n ? src[i + 1] : '';
        // Line comment
        if (c === '-' && c2 === '-') {
            while (i < n && src[i] !== '\n') { cur += src[i++]; }
            continue;
        }
        // Block comment
        if (c === '/' && c2 === '*') {
            cur += c; cur += c2; i += 2;
            while (i < n && !(src[i] === '*' && src[i + 1] === '/')) { cur += src[i++]; }
            if (i < n) { cur += '*/'; i += 2; }
            continue;
        }
        // Quoted strings — skip over them character by character
        if (c === "'" || c === '"') {
            const quote = c;
            cur += c; i++;
            while (i < n) {
                const q = src[i];
                cur += q;
                if (q === '\\' && i + 1 < n) { cur += src[i + 1]; i += 2; continue; }
                i++;
                if (q === quote) break;
            }
            continue;
        }
        if (c === ';') {
            const trimmed = cur.trim();
            if (trimmed) out.push(trimmed);
            cur = '';
            i++;
            continue;
        }
        cur += c;
        i++;
    }
    const trailing = cur.trim();
    if (trailing) out.push(trailing);
    return out;
}

async function runMultiStatements(stmts, env) {
    const runBtn = document.getElementById('btn-run-sql');
    const resultsEl = document.getElementById('sql-results');
    runBtn.disabled = true;
    const originalHtml = runBtn.innerHTML;
    runBtn.textContent = 'Running…';
    showStatus(`Running ${stmts.length} statements…`, 'info');
    resultsEl.innerHTML = `<div id="multi-results"></div>`;
    const container = document.getElementById('multi-results');

    // Reset single-result table state so any stale table doesn't confuse the layout
    sqlState.isTableView = false;
    sqlState.allRows = [];
    sqlState.rawData = null;

    const started = Date.now();
    let totalRows = 0;
    let ok = true;
    let failedIdx = -1;
    let failMsg = null;
    for (let i = 0; i < stmts.length; i++) {
        const stmt = stmts[i];
        const block = document.createElement('details');
        block.className = 'multi-stmt';
        block.open = true;   // default expanded so results are visible immediately
        block.innerHTML = `
            <summary class="multi-stmt-head">
                <span class="multi-stmt-chevron" aria-hidden="true">▾</span>
                <span class="multi-stmt-num">${i + 1}</span>
                <code class="multi-stmt-sql">${escapeHtml(stmt.length > 200 ? stmt.slice(0, 200) + '…' : stmt)}</code>
                <span class="multi-stmt-status">running…</span>
            </summary>
            <div class="multi-stmt-body"></div>
        `;
        container.appendChild(block);
        const statusEl = block.querySelector('.multi-stmt-status');
        const bodyEl = block.querySelector('.multi-stmt-body');
        const stmtStarted = Date.now();
        try {
            const result = await executeSql({ env, sql: stmt });
            const stmtElapsed = formatDuration(Date.now() - stmtStarted);
            if (!result.success) {
                ok = false;
                failedIdx = i;
                failMsg = result.error || 'Unknown error';
                statusEl.className = 'multi-stmt-status fail';
                statusEl.textContent = `failed in ${stmtElapsed}`;
                bodyEl.innerHTML = `<pre class="sql-raw">${escapeHtml(String(result.body || failMsg))}</pre>`;
                break;
            }
            const rows = unwrapFawbResponse(result.data);
            totalRows += rows.length;
            statusEl.className = 'multi-stmt-status ok';
            statusEl.textContent = `${rows.length} row${rows.length === 1 ? '' : 's'} in ${stmtElapsed}`;
            bodyEl.innerHTML = renderSimpleTable(rows);
            // Wire right-click + cell inspector for this statement's rows.
            // Table-name for INSERT/WHERE is inferred from THIS statement's
            // FROM clause (not sqlState.sql, which is whatever the last
            // single-statement query was).
            wireMultiStmtRows(bodyEl, rows, inferTableName(stmt));
        } catch (err) {
            ok = false;
            failedIdx = i;
            failMsg = err.message;
            statusEl.className = 'multi-stmt-status fail';
            statusEl.textContent = `error`;
            bodyEl.innerHTML = `<pre class="sql-raw">${escapeHtml(err.message)}</pre>`;
            break;
        }
    }
    const totalElapsed = formatDuration(Date.now() - started);
    if (ok) {
        showStatus(`All ${stmts.length} statements OK — ${totalRows} total row${totalRows === 1 ? '' : 's'} in ${totalElapsed}`, 'success');
    } else {
        showStatus(`Statement ${failedIdx + 1} failed after ${totalElapsed}: ${failMsg}`, 'error');
    }
    pushHistory({ sql: stmts.join(';\n'), env, ok, elapsedMs: Date.now() - started, rowCount: totalRows });
    runBtn.disabled = false;
    runBtn.innerHTML = originalHtml;
}

function renderSimpleTable(rows) {
    if (!rows || rows.length === 0) {
        return '<p class="muted multi-stmt-no-rows">No rows returned.</p>';
    }
    // Union of keys
    const cols = [];
    const seen = new Set();
    for (const r of rows) for (const k of Object.keys(r)) if (!seen.has(k)) { seen.add(k); cols.push(k); }
    // Cap displayed rows to keep the DOM light — 100 is plenty for the
    // "run several probes" use case; the toolbar-driven single-statement
    // path is where you go for full pagination + export.
    const capped = rows.slice(0, 100);
    const capNote = rows.length > 100
        ? `<p class="multi-stmt-cap">Showing first 100 of ${rows.length} rows — run the statement alone for pagination + export.</p>`
        : '';
    // Same tr / td attributes as the single-statement path so right-click
    // context menu + cell inspector wire up uniformly. Rows are attached
    // to the tr as a JS property via wireMultiStmtRows() below.
    return `
        <div class="sql-multi-tools">
            <div class="columns-dropdown multi-columns-dropdown">
                <button class="btn btn-small secondary" data-multi-action="columns-trigger" type="button" title="Show / hide columns">
                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="9" y1="3" x2="9" y2="21"/><line x1="15" y1="3" x2="15" y2="21"/></svg>
                    Columns
                    <span class="chevron" aria-hidden="true">▾</span>
                </button>
                <div class="columns-menu hidden" data-multi-columns-menu></div>
            </div>
            <button class="btn btn-small secondary" data-multi-action="stats" type="button" title="Per-column statistics">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/></svg>
                Stats
            </button>
            <button class="btn btn-small secondary" data-multi-action="duplicates" type="button" title="Find duplicate rows by chosen columns">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                Duplicates
            </button>
        </div>
        ${capNote}
        <div class="sql-table-wrapper">
            <table class="sql-table" data-multi-cols="${escapeHtml(cols.join('|'))}">
                <thead><tr>${cols.map(c => `<th data-col="${escapeHtml(c)}">${escapeHtml(c)}</th>`).join('')}</tr></thead>
                <tbody>
                    ${capped.map((row, i) => `
                        <tr data-multi-row-idx="${i}">${cols.map(c => `<td class="sql-cell" data-col="${escapeHtml(c)}" title="Click to inspect">${formatCell(row[c])}</td>`).join('')}</tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

// Wire right-click + cell-inspector on a freshly-rendered multi-statement
// table. Rows and the table name (inferred from this statement's FROM) are
// captured in closure so each block is self-contained.
function wireMultiStmtRows(bodyEl, rows, tableName) {
    if (!bodyEl || !rows) return;

    // Per-block Columns dropdown — hidden columns tracked per block in a Set
    // captured in closure. Toggling adds/removes a display:none class on
    // the corresponding th/td, so no re-render is needed.
    const table = bodyEl.querySelector('table.sql-table[data-multi-cols]');
    const colsAttr = table ? table.dataset.multiCols : '';
    const blockCols = colsAttr ? colsAttr.split('|') : [];
    const hiddenCols = new Set();
    const colTrigger = bodyEl.querySelector('button[data-multi-action="columns-trigger"]');
    const colMenu = bodyEl.querySelector('[data-multi-columns-menu]');
    const renderMenu = () => {
        if (!colMenu) return;
        colMenu.innerHTML = `
            <div class="columns-menu-header">
                <button class="columns-toggle-all" data-multi-cols-action="all" type="button">Show all</button>
                <button class="columns-toggle-all" data-multi-cols-action="none" type="button">Hide all</button>
            </div>
            <div class="columns-menu-list">
                ${blockCols.map(c => `
                    <label class="columns-menu-item">
                        <input type="checkbox" data-col="${escapeHtml(c)}" ${hiddenCols.has(c) ? '' : 'checked'}>
                        <span>${escapeHtml(c)}</span>
                    </label>
                `).join('')}
            </div>
        `;
        colMenu.querySelectorAll('input[type="checkbox"][data-col]').forEach(cb => {
            cb.addEventListener('change', () => {
                const col = cb.dataset.col;
                if (cb.checked) hiddenCols.delete(col);
                else hiddenCols.add(col);
                applyHiddenCols();
            });
        });
        colMenu.querySelectorAll('.columns-toggle-all').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.dataset.multiColsAction === 'all') hiddenCols.clear();
                else blockCols.forEach(c => hiddenCols.add(c));
                renderMenu();
                applyHiddenCols();
            });
        });
    };
    const applyHiddenCols = () => {
        if (!table) return;
        table.querySelectorAll('th[data-col], td[data-col]').forEach(cell => {
            cell.classList.toggle('col-hidden', hiddenCols.has(cell.dataset.col));
        });
    };
    if (colTrigger && colMenu) {
        colTrigger.addEventListener('click', (e) => {
            e.stopPropagation();
            if (colMenu.classList.contains('hidden')) {
                renderMenu();
                colMenu.classList.remove('hidden');
            } else {
                colMenu.classList.add('hidden');
            }
        });
        // Close on outside click
        document.addEventListener('click', (e) => {
            if (!colMenu.contains(e.target) && !colTrigger.contains(e.target)) {
                colMenu.classList.add('hidden');
            }
        });
    }

    // Per-block Stats/Duplicates buttons — operate on THIS statement's rows,
    // not the outer sqlState.allRows.
    bodyEl.querySelectorAll('button[data-multi-action]').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const action = btn.dataset.multiAction;
            if (action === 'stats') openColumnStatsModal(rows);
            else if (action === 'duplicates') openDuplicatesModal(rows);
            // 'columns-trigger' handled separately above
        });
    });
    bodyEl.querySelectorAll('tr[data-multi-row-idx]').forEach(tr => {
        const idx = parseInt(tr.dataset.multiRowIdx, 10);
        const row = rows[idx];
        if (!row) return;
        // Right-click → row context menu
        tr.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            e.stopPropagation();
            openRowContextMenu(e.clientX, e.clientY, row, tableName);
        });
        // Left-click on a cell → cell inspector
        tr.querySelectorAll('td.sql-cell').forEach(td => {
            td.addEventListener('click', (e) => {
                if (e.button !== 0) return;
                const sel = window.getSelection();
                if (sel && !sel.isCollapsed && sel.toString().trim().length > 0) return;
                const col = td.dataset.col;
                if (!col) return;
                openCellInspector(col, row[col]);
            });
        });
    });
}

// Render a millisecond count as the most readable form:
//   <1s -> "823 ms"
//   <60s -> "4.2s"
//   <60m -> "3m 12s"
//   else -> "1h 4m 12s"
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

function renderResults(data, requestUrl, sql, env) {
    sqlState.rawData = data;   // preserved verbatim for the "Show raw response" toggle
    sqlState.requestUrl = requestUrl;
    sqlState.sql = sql || '';
    sqlState.env = env || null;
    sqlState.currentPage = 1;
    // Reset per-result table state so a new query starts clean
    sqlState.sortBy = null;
    sqlState.filterText = '';
    sqlState.hiddenColumns = new Set();

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
            <div class="sql-toolbar-left">
                <div class="sql-filter">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
                    <input type="text" id="sql-filter-input" class="sql-filter-input"
                           placeholder="Filter rows…" value="${escapeHtml(sqlState.filterText || '')}"
                           spellcheck="false" autocomplete="off">
                </div>
                <div class="columns-dropdown">
                    <button class="btn btn-small secondary" id="btn-columns-trigger" type="button" aria-haspopup="true" aria-expanded="false" title="Show / hide columns">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="18" height="18" rx="2"/><line x1="9" y1="3" x2="9" y2="21"/><line x1="15" y1="3" x2="15" y2="21"/></svg>
                        <span>Columns</span>
                        <span class="chevron" aria-hidden="true">▾</span>
                    </button>
                    <div class="columns-menu hidden" id="columns-menu" role="menu"></div>
                </div>
                <button class="btn btn-small secondary" id="btn-stats" type="button" title="Per-column statistics (distinct, nulls, min/max)">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><line x1="12" y1="20" x2="12" y2="10"/><line x1="18" y1="20" x2="18" y2="4"/><line x1="6" y1="20" x2="6" y2="16"/></svg>
                    Stats
                </button>
                <button class="btn btn-small secondary" id="btn-duplicates" type="button" title="Find duplicate rows by chosen columns">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    Duplicates
                </button>
            </div>
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
    if (trigger && menu) {
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
        document.addEventListener('click', function outsideClose(e) {
            if (!menu.contains(e.target) && !trigger.contains(e.target)) close();
        });
        document.addEventListener('keydown', function escClose(e) {
            if (e.key === 'Escape') close();
        });
    }

    // Filter input — debounced to avoid re-rendering the table on every keystroke of a big result set
    const filterInput = document.getElementById('sql-filter-input');
    if (filterInput) {
        let debounceTimer = null;
        filterInput.addEventListener('input', () => {
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(() => {
                sqlState.filterText = filterInput.value || '';
                sqlState.currentPage = 1;
                renderTablePart();
            }, 120);
        });
    }

    // Columns dropdown
    const colTrigger = document.getElementById('btn-columns-trigger');
    const colMenu = document.getElementById('columns-menu');
    if (colTrigger && colMenu) {
        const openCol = () => {
            renderColumnsMenu(colMenu);
            colMenu.classList.remove('hidden');
            colTrigger.setAttribute('aria-expanded', 'true');
        };
        const closeCol = () => {
            colMenu.classList.add('hidden');
            colTrigger.setAttribute('aria-expanded', 'false');
        };
        colTrigger.addEventListener('click', (e) => {
            e.stopPropagation();
            if (colMenu.classList.contains('hidden')) openCol(); else closeCol();
        });
        document.addEventListener('click', function outsideColClose(e) {
            if (!colMenu.contains(e.target) && !colTrigger.contains(e.target)) closeCol();
        });
    }

    // Stats button
    const statsBtn = document.getElementById('btn-stats');
    if (statsBtn) statsBtn.addEventListener('click', () => openColumnStatsModal(sqlState.allRows));

    // Duplicates button
    const dupBtn = document.getElementById('btn-duplicates');
    if (dupBtn) dupBtn.addEventListener('click', () => openDuplicatesModal(sqlState.allRows));
}

function renderColumnsMenu(menu) {
    const cols = computeColumns(sqlState.allRows);
    if (!cols.length) {
        menu.innerHTML = `<div class="sql-history-empty">No columns.</div>`;
        return;
    }
    menu.innerHTML = `
        <div class="columns-menu-header">
            <button class="columns-toggle-all" data-action="all" type="button">Show all</button>
            <button class="columns-toggle-all" data-action="none" type="button">Hide all</button>
        </div>
        <div class="columns-menu-list">
            ${cols.map(c => `
                <label class="columns-menu-item">
                    <input type="checkbox" data-col="${escapeHtml(c)}" ${sqlState.hiddenColumns.has(c) ? '' : 'checked'}>
                    <span>${escapeHtml(c)}</span>
                </label>
            `).join('')}
        </div>
    `;
    menu.querySelectorAll('input[type="checkbox"][data-col]').forEach(cb => {
        cb.addEventListener('change', () => {
            const col = cb.dataset.col;
            if (cb.checked) sqlState.hiddenColumns.delete(col);
            else sqlState.hiddenColumns.add(col);
            renderTablePart();
        });
    });
    menu.querySelectorAll('.columns-toggle-all').forEach(btn => {
        btn.addEventListener('click', () => {
            if (btn.dataset.action === 'all') sqlState.hiddenColumns.clear();
            else cols.forEach(c => sqlState.hiddenColumns.add(c));
            renderColumnsMenu(menu);
            renderTablePart();
        });
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

    // Pipeline: filter -> sort -> paginate. All operate on sqlState.allRows.
    const filtered = applyFilter(sqlState.allRows, sqlState.filterText);
    const sorted = applySort(filtered, sqlState.sortBy);

    const total = sorted.length;
    const totalPages = Math.max(1, Math.ceil(total / sqlState.pageSize));
    if (sqlState.currentPage > totalPages) sqlState.currentPage = totalPages;
    if (sqlState.currentPage < 1) sqlState.currentPage = 1;

    const start = (sqlState.currentPage - 1) * sqlState.pageSize;
    const end = Math.min(start + sqlState.pageSize, total);
    const pageRows = sorted.slice(start, end);
    sqlState.currentPageRows = pageRows;   // for context-menu lookup by data-row-idx

    container.innerHTML = renderTableHtml(pageRows, sqlState.allRows.length)
        + renderPaginationHtml(start, end, total, totalPages);
    wirePagination(totalPages);
    wireSortHeaders();
    wireRowContextMenu();
    wireCellInspector();

    // Scroll the table body back to the top when the page changes
    const wrapper = container.querySelector('.sql-table-wrapper');
    if (wrapper) wrapper.scrollTop = 0;
}

function applyFilter(rows, filterText) {
    const q = String(filterText || '').trim().toLowerCase();
    if (!q) return rows;
    // Search across ALL columns, including hidden ones — hiding a column
    // is about visibility, not scope. Users often hide noise columns but
    // still want to find rows by their values.
    return rows.filter(row => {
        for (const v of Object.values(row)) {
            const s = v == null ? '' : (typeof v === 'object' ? JSON.stringify(v) : String(v));
            if (s.toLowerCase().includes(q)) return true;
        }
        return false;
    });
}

function applySort(rows, sortBy) {
    if (!sortBy || !sortBy.col) return rows;
    const { col, dir } = sortBy;
    const mult = dir === 'desc' ? -1 : 1;
    const copy = rows.slice();
    copy.sort((a, b) => compareCell(a[col], b[col]) * mult);
    return copy;
}

function compareCell(a, b) {
    // Nulls sort last within the current direction (ASC: nulls at end; DESC: also at end after flip)
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    // Numeric compare when both sides are numbers or numeric strings
    const na = typeof a === 'number' ? a : Number(a);
    const nb = typeof b === 'number' ? b : Number(b);
    if (!Number.isNaN(na) && !Number.isNaN(nb) && String(na) === String(a).trim() && String(nb) === String(b).trim()) {
        return na - nb;
    }
    const sa = typeof a === 'object' ? JSON.stringify(a) : String(a);
    const sb = typeof b === 'object' ? JSON.stringify(b) : String(b);
    return sa.localeCompare(sb, undefined, { numeric: true, sensitivity: 'base' });
}

function visibleColumns(rows) {
    return computeColumns(rows).filter(c => !sqlState.hiddenColumns.has(c));
}

function renderTableHtml(pageRows, totalRowCount) {
    // Column list from the FULL dataset so sort/filter/pagination don't collapse it,
    // filtered by hidden set.
    const cols = visibleColumns(sqlState.allRows);
    const filterActive = String(sqlState.filterText || '').trim().length > 0;
    const filterNote = filterActive
        ? `<div class="sql-filter-note">Showing filtered view (${pageRows.length} on this page of ${totalRowCount} total)</div>`
        : '';
    return `
        ${filterNote}
        <div class="sql-table-wrapper">
            <table class="sql-table">
                <thead>
                    <tr>${cols.map(c => renderSortableHeader(c)).join('')}</tr>
                </thead>
                <tbody>
                    ${pageRows.map((row, i) => `
                        <tr data-row-idx="${i}">${cols.map(c => `<td class="sql-cell" data-col="${escapeHtml(c)}" title="Click to inspect">${formatCell(row[c])}</td>`).join('')}</tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

function renderSortableHeader(col) {
    const sort = sqlState.sortBy;
    const active = sort && sort.col === col;
    const arrow = !active ? '<span class="sort-arrow" aria-hidden="true">↕</span>'
        : sort.dir === 'asc' ? '<span class="sort-arrow active" aria-hidden="true">▲</span>'
        : '<span class="sort-arrow active" aria-hidden="true">▼</span>';
    return `<th class="sortable ${active ? 'sorted' : ''}" data-col="${escapeHtml(col)}" title="Click to sort">${escapeHtml(col)} ${arrow}</th>`;
}

function wireSortHeaders() {
    const container = document.getElementById('sql-table-and-pagination');
    if (!container) return;
    container.querySelectorAll('th.sortable[data-col]').forEach(th => {
        th.addEventListener('click', () => {
            const col = th.dataset.col;
            const cur = sqlState.sortBy;
            if (!cur || cur.col !== col) {
                sqlState.sortBy = { col, dir: 'asc' };
            } else if (cur.dir === 'asc') {
                sqlState.sortBy = { col, dir: 'desc' };
            } else {
                sqlState.sortBy = null;   // third click clears sort
            }
            sqlState.currentPage = 1;
            renderTablePart();
        });
    });
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

/* ---------- Right-click context menu on rows ---------- */

// Per-tr / per-td listener attach. Called on each renderTablePart. Because
// innerHTML replaces the table, each render gets a fresh set of trs and tds
// with no stale listeners — safe to always re-attach.
function wireRowContextMenu() {
    const container = document.getElementById('sql-table-and-pagination');
    if (!container) return;
    container.querySelectorAll('tr[data-row-idx]').forEach(tr => {
        tr.addEventListener('contextmenu', (e) => {
            e.preventDefault();
            e.stopPropagation();
            const idx = parseInt(tr.dataset.rowIdx, 10);
            const row = (sqlState.currentPageRows || [])[idx];
            console.debug('[sql-runner] right-click on tr idx=', idx, 'row=', row);
            if (!row) return;
            openRowContextMenu(e.clientX, e.clientY, row);
        });
    });
}

// Global handler state: one menu at a time, one set of persistent close listeners.
let rowMenuGlobalHandlersInstalled = false;

function openRowContextMenu(x, y, row, tableNameOverride) {
    closeRowContextMenu();
    const tableName = tableNameOverride || inferTableName(sqlState.sql);
    const menu = document.createElement('div');
    menu.id = 'sql-row-context-menu';
    menu.className = 'sql-row-context-menu';

    // Build menu content based on what's available
    const parts = [
        `<button data-action="copy-insert" type="button">Copy as INSERT${tableName ? ` (${escapeHtml(tableName)})` : ''}</button>`,
        `<button data-action="copy-where" type="button">Copy as WHERE clause</button>`,
        `<button data-action="copy-json" type="button">Copy row as JSON</button>`,
    ];

    // Compare rows
    if (compareState.markedRow && compareState.markedRow !== row) {
        parts.push(`<div class="sql-row-menu-sep"></div>`);
        parts.push(`<button data-action="compare-with-marked" type="button">Compare with marked row</button>`);
        parts.push(`<button data-action="clear-marked" type="button" class="sql-row-menu-subtle">Clear marked row</button>`);
    } else {
        parts.push(`<div class="sql-row-menu-sep"></div>`);
        parts.push(`<button data-action="mark-for-compare" type="button">Mark row for compare</button>`);
    }

    // FK jump — one entry per outgoing FK column that has a value
    const outFks = tableName ? outgoingFks(tableName) : null;
    const jumpEntries = [];
    if (outFks && outFks.length > 0) {
        for (const fk of outFks) {
            const v = row[fk.column];
            if (v != null && v !== '') {
                jumpEntries.push({ fk, value: v });
            }
        }
    }
    if (jumpEntries.length > 0) {
        parts.push(`<div class="sql-row-menu-sep"></div>`);
        parts.push(`<div class="sql-row-menu-heading">Jump to referenced row</div>`);
        for (let i = 0; i < jumpEntries.length; i++) {
            const { fk, value } = jumpEntries[i];
            const label = `${fk.column} → ${fk.targetTable} (${String(value).slice(0, 32)})`;
            parts.push(`<button data-action="fk-jump" data-jump-idx="${i}" type="button">${escapeHtml(label)}</button>`);
        }
    }

    // Reverse FK — only if dataModel knows something references this table
    // and we can identify the row's PK
    const rowTable = tableName ? String(tableName).toUpperCase() : null;
    const rowTableDetail = rowTable && dataModelState.tablesByUpperName
        ? dataModelState.tablesByUpperName.get(rowTable) : null;
    const rowPkCol = rowTableDetail && rowTableDetail.primaryKey && Array.isArray(rowTableDetail.primaryKey.columns)
        && rowTableDetail.primaryKey.columns.length === 1
        ? rowTableDetail.primaryKey.columns[0] : null;
    const rowPkValue = rowPkCol ? row[rowPkCol] : null;
    const hasReverse = tableName && incomingFks(tableName) && incomingFks(tableName).length > 0;
    if (hasReverse && rowPkValue != null && rowPkValue !== '') {
        parts.push(`<div class="sql-row-menu-sep"></div>`);
        parts.push(`<button data-action="find-referencing" type="button">Find rows referencing this</button>`);
    }

    menu.innerHTML = parts.join('');
    document.body.appendChild(menu);

    // Position, keeping the menu inside the viewport
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const mw = menu.offsetWidth;
    const mh = menu.offsetHeight;
    const px = (x + mw > vw) ? Math.max(0, vw - mw - 8) : x;
    const py = (y + mh > vh) ? Math.max(0, vh - mh - 8) : y;
    menu.style.left = px + 'px';
    menu.style.top = py + 'px';

    // Wire each button. mousedown + stopPropagation so the document-level
    // closer doesn't see the event before the action runs. We fire the action
    // on mousedown (not click) to sidestep any race where mousedown-closers
    // remove the menu before click can be delivered.
    menu.querySelectorAll('button[data-action]').forEach(btn => {
        btn.addEventListener('mousedown', (e) => {
            e.stopPropagation();
            e.preventDefault();
            const action = btn.dataset.action;
            // Copy family — clipboard actions
            if (action === 'copy-insert' || action === 'copy-where' || action === 'copy-json') {
                let text;
                if (action === 'copy-insert') text = rowToInsertSql(row, tableName || 'TABLE_NAME');
                else if (action === 'copy-where') text = rowToWhereClause(row);
                else text = JSON.stringify(row, null, 2);
                closeRowContextMenu();
                if (text) copyToClipboard(text, labelForAction(action));
                return;
            }
            // Compare rows
            if (action === 'mark-for-compare') {
                compareState.markedRow = row;
                compareState.markedTableName = tableName;
                (window.showToast || alert)('Row marked. Right-click another row → Compare with marked row.', 'info', 4000);
                closeRowContextMenu();
                return;
            }
            if (action === 'compare-with-marked') {
                closeRowContextMenu();
                openCompareRowsModal(compareState.markedRow, row);
                compareState.markedRow = null;
                compareState.markedTableName = null;
                return;
            }
            if (action === 'clear-marked') {
                compareState.markedRow = null;
                compareState.markedTableName = null;
                closeRowContextMenu();
                (window.showToast || alert)('Marked row cleared.', 'info', 2000);
                return;
            }
            // FK jump
            if (action === 'fk-jump') {
                const idx = parseInt(btn.dataset.jumpIdx, 10);
                closeRowContextMenu();
                if (!Number.isNaN(idx) && jumpEntries[idx]) {
                    jumpToReferencedRow(jumpEntries[idx].fk, jumpEntries[idx].value);
                }
                return;
            }
            // Reverse FK
            if (action === 'find-referencing') {
                closeRowContextMenu();
                findRowsReferencingThis(tableName, rowPkCol, rowPkValue);
                return;
            }
        });
        // Also on click as a fallback for any environment where mousedown is
        // swallowed by an ancestor (e.g., some browser extensions).
        btn.addEventListener('click', (e) => { e.stopPropagation(); });
    });

    // Install one-time global close handlers (idempotent — install once per session)
    installRowMenuGlobalHandlers();
}

function installRowMenuGlobalHandlers() {
    if (rowMenuGlobalHandlersInstalled) return;
    rowMenuGlobalHandlersInstalled = true;
    // Close on any click outside the menu
    document.addEventListener('mousedown', (e) => {
        const menu = document.getElementById('sql-row-context-menu');
        if (!menu) return;
        if (!menu.contains(e.target)) closeRowContextMenu();
    });
    // Close on Escape
    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape') return;
        const menu = document.getElementById('sql-row-context-menu');
        if (menu) closeRowContextMenu();
    });
    // Close on window scroll (menu position would drift)
    window.addEventListener('scroll', () => {
        const menu = document.getElementById('sql-row-context-menu');
        if (menu) closeRowContextMenu();
    }, true);
}

function closeRowContextMenu() {
    const existing = document.getElementById('sql-row-context-menu');
    if (existing) existing.remove();
}

function labelForAction(action) {
    if (action === 'copy-insert') return 'INSERT statement';
    if (action === 'copy-where') return 'WHERE clause';
    return 'row JSON';
}

async function copyToClipboard(text, label) {
    try {
        await navigator.clipboard.writeText(text);
        (window.showToast || (msg => console.log(msg)))(`${label} copied to clipboard.`, 'success', 3000);
    } catch {
        // Fallback: create a hidden textarea and use execCommand
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); (window.showToast || alert)(`${label} copied.`, 'success', 3000); }
        catch { (window.showToast || alert)(`Copy failed — select and copy manually.`, 'error'); }
        document.body.removeChild(ta);
    }
}

// Best-effort table name extraction from the SQL that produced the row.
// Matches `FROM <backtick or bare identifier>`. Not perfect for complex JOINs,
// but covers the common single-table SELECT case that this feature targets.
function inferTableName(sql) {
    if (!sql) return null;
    const m = sql.match(/\bfrom\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    return m ? m[1] : null;
}

function rowToInsertSql(row, tableName) {
    const cols = Object.keys(row);
    if (cols.length === 0) return '';
    const colList = cols.map(quoteIdent).join(', ');
    const valList = cols.map(c => sqlLiteral(row[c])).join(', ');
    return `INSERT INTO ${quoteIdent(tableName)} (${colList}) VALUES (${valList});`;
}

function rowToWhereClause(row) {
    const cols = Object.keys(row);
    if (cols.length === 0) return '';
    return cols.map(c => {
        const v = row[c];
        if (v === null || v === undefined) return `${quoteIdent(c)} IS NULL`;
        return `${quoteIdent(c)} = ${sqlLiteral(v)}`;
    }).join('\n  AND ');
}

// Render a JS value as a MariaDB-safe SQL literal. Handles null, numbers,
// booleans, strings (escaped), objects (JSON.stringify + quoted string).
// Dates are ambiguous in raw result sets — we treat epoch-ms numbers as
// numbers (the DB likely stores them as BIGINT); ISO date strings pass
// through as quoted strings, which MariaDB parses correctly.
function sqlLiteral(v) {
    if (v === null || v === undefined) return 'NULL';
    if (typeof v === 'boolean') return v ? '1' : '0';
    if (typeof v === 'number') return Number.isFinite(v) ? String(v) : 'NULL';
    if (typeof v === 'object') return quoteString(JSON.stringify(v));
    return quoteString(String(v));
}

function quoteString(s) {
    // Escape single quotes and backslashes. MariaDB single-quote strings
    // with backslash-escape enabled by default.
    return "'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'") + "'";
}

function quoteIdent(name) {
    // Backtick-quote identifiers; escape any embedded backticks.
    return '`' + String(name).replace(/`/g, '``') + '`';
}

/* ---------- Cell inspector modal ---------- */

function wireCellInspector() {
    const container = document.getElementById('sql-table-and-pagination');
    if (!container) return;
    container.querySelectorAll('td.sql-cell').forEach(td => {
        td.addEventListener('click', (e) => {
            if (e.button !== 0) return;
            const sel = window.getSelection();
            if (sel && !sel.isCollapsed && sel.toString().trim().length > 0) return;
            const tr = td.parentElement;
            const idx = parseInt(tr && tr.dataset.rowIdx, 10);
            const col = td.dataset.col;
            const row = (sqlState.currentPageRows || [])[idx];
            if (!row || !col) return;
            openCellInspector(col, row[col]);
        });
    });
}

function openCellInspector(colName, value) {
    closeCellInspector();
    const overlay = document.createElement('div');
    overlay.id = 'sql-cell-inspector';
    overlay.className = 'modal-overlay';

    const isNull = value === null || value === undefined;
    let display = '';
    let isJson = false;
    if (isNull) {
        display = 'NULL';
    } else if (typeof value === 'object') {
        display = JSON.stringify(value, null, 2);
        isJson = true;
    } else if (typeof value === 'string') {
        // Try JSON pretty-print if the string is valid JSON
        try {
            const parsed = JSON.parse(value);
            if (parsed && typeof parsed === 'object') {
                display = JSON.stringify(parsed, null, 2);
                isJson = true;
            } else {
                display = String(value);
            }
        } catch {
            display = String(value);
        }
    } else {
        display = String(value);
    }

    const typeLabel = isNull ? 'null'
        : typeof value === 'object' ? 'object'
        : typeof value === 'string' && isJson ? 'string (JSON)'
        : typeof value;
    const byteLen = isNull ? 0 : new TextEncoder().encode(String(display)).length;

    overlay.innerHTML = `
        <div class="modal-panel sql-cell-panel">
            <h3>${escapeHtml(colName)}</h3>
            <div class="sql-cell-meta">
                <span class="sql-cell-tag">${escapeHtml(typeLabel)}</span>
                <span class="sql-cell-tag">${byteLen.toLocaleString()} bytes</span>
                ${isJson ? '<span class="sql-cell-tag sql-cell-tag-primary">pretty-printed</span>' : ''}
            </div>
            <pre class="sql-cell-value">${escapeHtml(display)}</pre>
            <div class="modal-actions">
                <button class="btn" data-action="copy" type="button">Copy value</button>
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);

    const closeAction = overlay.querySelector('button[data-action="close"]');
    const copyBtn = overlay.querySelector('button[data-action="copy"]');
    closeAction.addEventListener('click', closeCellInspector);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeCellInspector(); });
    copyBtn.addEventListener('click', () => {
        copyToClipboard(isNull ? '' : display, 'Cell value');
    });
    // Escape closes
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeCellInspector(); document.removeEventListener('keydown', esc); }
    });
}

function closeCellInspector() {
    const existing = document.getElementById('sql-cell-inspector');
    if (existing) existing.remove();
}

/* ---------- Column stats modal ---------- */

function openColumnStatsModal(rows) {
    if (!rows || rows.length === 0) {
        (window.showToast || alert)('No result rows — run a query first.', 'info');
        return;
    }
    const cols = computeColumns(rows);
    const stats = cols.map(col => computeColumnStat(rows, col));

    closeColumnStatsModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-stats-modal';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-panel sql-stats-panel">
            <h3>Column statistics — ${rows.length} row${rows.length === 1 ? '' : 's'}</h3>
            <p class="modal-help">Computed from the current result set (client-side).</p>
            <div class="sql-table-wrapper">
                <table class="sql-table sql-stats-table">
                    <thead>
                        <tr>
                            <th>Column</th>
                            <th>Total</th>
                            <th>Distinct</th>
                            <th>Nulls</th>
                            <th>Min</th>
                            <th>Max</th>
                            <th>Avg</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${stats.map(s => `
                            <tr>
                                <td><strong>${escapeHtml(s.col)}</strong></td>
                                <td>${s.total}</td>
                                <td>${s.distinct}</td>
                                <td>${s.nulls}</td>
                                <td>${s.min == null ? '—' : escapeHtml(String(s.min))}</td>
                                <td>${s.max == null ? '—' : escapeHtml(String(s.max))}</td>
                                <td>${s.avg == null ? '—' : escapeHtml(s.avg)}</td>
                            </tr>
                        `).join('')}
                    </tbody>
                </table>
            </div>
            <div class="modal-actions">
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeColumnStatsModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeColumnStatsModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeColumnStatsModal(); document.removeEventListener('keydown', esc); }
    });
}

function closeColumnStatsModal() {
    const existing = document.getElementById('sql-stats-modal');
    if (existing) existing.remove();
}

function computeColumnStat(rows, col) {
    let total = rows.length, nulls = 0;
    const distinctVals = new Set();
    let numericCount = 0, sum = 0;
    let minVal = null, maxVal = null;
    for (const row of rows) {
        const v = row[col];
        if (v === null || v === undefined || v === '') { nulls++; continue; }
        distinctVals.add(typeof v === 'object' ? JSON.stringify(v) : String(v));
        const n = typeof v === 'number' ? v : Number(v);
        const isNumeric = !Number.isNaN(n) && String(n).trim() === String(v).trim();
        if (isNumeric) {
            numericCount++;
            sum += n;
            if (minVal == null || n < minVal) minVal = n;
            if (maxVal == null || n > maxVal) maxVal = n;
        } else {
            // Non-numeric min/max by lexicographic
            const s = String(v);
            if (minVal == null || (typeof minVal === 'string' && s < minVal)) minVal = s;
            if (maxVal == null || (typeof maxVal === 'string' && s > maxVal)) maxVal = s;
        }
    }
    const avg = numericCount > 0 ? (sum / numericCount).toFixed(2) : null;
    return { col, total, distinct: distinctVals.size, nulls, min: minVal, max: maxVal, avg };
}

/* ---------- Find duplicates modal ---------- */

function openDuplicatesModal(rows) {
    if (!rows || rows.length === 0) {
        (window.showToast || alert)('No result rows — run a query first.', 'info');
        return;
    }
    const cols = computeColumns(rows);
    closeDuplicatesModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-dup-modal';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-panel sql-dup-panel">
            <h3>Find duplicates</h3>
            <p class="modal-help">Pick one or more columns. Rows whose <em>combined</em> values on those columns repeat will be listed.</p>
            <div class="sql-dup-cols">
                ${cols.map(c => `
                    <label class="columns-menu-item">
                        <input type="checkbox" data-col="${escapeHtml(c)}">
                        <span>${escapeHtml(c)}</span>
                    </label>
                `).join('')}
            </div>
            <div class="modal-actions">
                <button class="btn" data-action="find" type="button">Find duplicates</button>
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
            <div class="sql-dup-result" id="sql-dup-result"></div>
        </div>
    `;
    document.body.appendChild(overlay);
    const findBtn = overlay.querySelector('button[data-action="find"]');
    findBtn.addEventListener('click', () => {
        const chosen = [...overlay.querySelectorAll('input[type="checkbox"][data-col]:checked')].map(cb => cb.dataset.col);
        if (chosen.length === 0) { (window.showToast || alert)('Pick at least one column.', 'error'); return; }
        renderDuplicates(rows, chosen, overlay.querySelector('#sql-dup-result'));
    });
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeDuplicatesModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeDuplicatesModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeDuplicatesModal(); document.removeEventListener('keydown', esc); }
    });
}

function closeDuplicatesModal() {
    const existing = document.getElementById('sql-dup-modal');
    if (existing) existing.remove();
}

function renderDuplicates(rows, keyCols, target) {
    // Group rows by the composite key
    const groups = new Map();
    for (const row of rows) {
        const key = keyCols.map(c => {
            const v = row[c];
            return v == null ? '\0' : (typeof v === 'object' ? JSON.stringify(v) : String(v));
        }).join('');
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(row);
    }
    const dupGroups = [...groups.entries()].filter(([, g]) => g.length > 1);
    if (dupGroups.length === 0) {
        target.innerHTML = `<p class="muted">No duplicates found on <code>${keyCols.map(escapeHtml).join(', ')}</code>.</p>`;
        return;
    }
    const totalDupRows = dupGroups.reduce((n, [, g]) => n + g.length, 0);
    // Show all columns in a table, group by key
    const allCols = computeColumns(rows);
    const rowsHtml = dupGroups.flatMap(([key, group]) =>
        group.map((row, i) => `
            <tr class="${i === 0 ? 'dup-group-start' : ''}">
                <td class="dup-group-marker">${i === 0 ? group.length + '×' : ''}</td>
                ${allCols.map(c => `<td>${formatCell(row[c])}</td>`).join('')}
            </tr>
        `)
    ).join('');
    target.innerHTML = `
        <p class="muted"><strong>${dupGroups.length}</strong> duplicate group${dupGroups.length === 1 ? '' : 's'} — <strong>${totalDupRows}</strong> row${totalDupRows === 1 ? '' : 's'} total.</p>
        <div class="sql-table-wrapper">
            <table class="sql-table">
                <thead><tr><th>Group</th>${allCols.map(c => `<th>${escapeHtml(c)}</th>`).join('')}</tr></thead>
                <tbody>${rowsHtml}</tbody>
            </table>
        </div>
    `;
}

/* ---------- Compare two rows ---------- */

const compareState = { markedRow: null, markedTableName: null };

function openCompareRowsModal(rowA, rowB) {
    closeCompareRowsModal();
    const allCols = new Set();
    for (const k of Object.keys(rowA)) allCols.add(k);
    for (const k of Object.keys(rowB)) allCols.add(k);
    const cols = [...allCols];
    const overlay = document.createElement('div');
    overlay.id = 'sql-compare-modal';
    overlay.className = 'modal-overlay';
    const rowsHtml = cols.map(c => {
        const a = rowA[c];
        const b = rowB[c];
        const eq = compareEqual(a, b);
        return `
            <tr class="${eq ? '' : 'compare-diff'}">
                <td><strong>${escapeHtml(c)}</strong></td>
                <td>${formatCell(a)}</td>
                <td>${formatCell(b)}</td>
                <td class="compare-status">${eq ? '=' : '≠'}</td>
            </tr>
        `;
    }).join('');
    const differCount = cols.filter(c => !compareEqual(rowA[c], rowB[c])).length;
    overlay.innerHTML = `
        <div class="modal-panel sql-compare-panel">
            <h3>Compare rows — ${differCount} column${differCount === 1 ? '' : 's'} differ</h3>
            <p class="modal-help">Rows are compared column-by-column; differing values are highlighted.</p>
            <div class="sql-table-wrapper">
                <table class="sql-table">
                    <thead>
                        <tr><th>Column</th><th>Row A (marked)</th><th>Row B (current)</th><th></th></tr>
                    </thead>
                    <tbody>${rowsHtml}</tbody>
                </table>
            </div>
            <div class="modal-actions">
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeCompareRowsModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeCompareRowsModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeCompareRowsModal(); document.removeEventListener('keydown', esc); }
    });
}

function closeCompareRowsModal() {
    const existing = document.getElementById('sql-compare-modal');
    if (existing) existing.remove();
}

function compareEqual(a, b) {
    if (a === b) return true;
    if (a == null && b == null) return true;
    if (a == null || b == null) return false;
    if (typeof a === 'object' || typeof b === 'object') {
        return JSON.stringify(a) === JSON.stringify(b);
    }
    return String(a) === String(b);
}

/* ---------- FK jump + reverse FK (dataModel-driven) ---------- */

// Cached dataModel FK info, populated on demand
const dataModelState = {
    profileId: null,
    tablesByUpperName: null,   // Map<UPPER, TableDetail> where TableDetail = {name, entityName, columns[], relations[]}
    reverseFks: null,          // Map<targetUpper, Array<{fromTable, fromColumn}>>
    loading: null,             // Promise<void> to dedupe concurrent loads
};

async function ensureDataModelLoaded(profileId) {
    if (!profileId) return;
    if (dataModelState.profileId === profileId && dataModelState.tablesByUpperName) return;
    if (dataModelState.loading) return dataModelState.loading;

    dataModelState.loading = (async () => {
        try {
            const summaries = await listDataModelTables(profileId);
            if (!Array.isArray(summaries) || summaries.length === 0) return;
            // Fetch full detail per table. Each detail has `relations` where
            // cardinality ManyToOne/OneToOne describes outgoing FKs from this
            // table (targetTable + mappings[]). Column-level `foreignKey` is
            // just a boolean flag; the actual target lives in relations.
            const details = await Promise.all(summaries.slice(0, 500).map(t =>
                getDataModelTable(profileId, t.name).catch(() => null)
            ));
            const tablesByUpperName = new Map();
            const reverseFks = new Map();
            for (const t of details) {
                if (!t || !t.name) continue;
                tablesByUpperName.set(t.name.toUpperCase(), t);
                if (!Array.isArray(t.relations)) continue;
                for (const r of t.relations) {
                    if (!r || !r.targetTable) continue;
                    const card = String(r.cardinality || '').toLowerCase();
                    // Outgoing FK relations only: ManyToOne, OneToOne
                    if (card !== 'manytoone' && card !== 'onetoone') continue;
                    // We only care about the first mapping's source column
                    // (multi-column FKs are extremely rare here — pick the
                    // primary pair). Fallback to a plausible default.
                    const mapping = Array.isArray(r.mappings) && r.mappings.length > 0
                        ? r.mappings[0] : null;
                    const fromColumn = mapping ? mapping.sourceColumn : null;
                    const targetColumn = mapping ? (mapping.targetColumn || 'id') : 'id';
                    if (!fromColumn) continue;
                    const targetUpper = r.targetTable.toUpperCase();
                    if (!reverseFks.has(targetUpper)) reverseFks.set(targetUpper, []);
                    reverseFks.get(targetUpper).push({
                        fromTable: t.name,
                        fromColumn,
                        targetColumn,
                    });
                }
            }
            dataModelState.profileId = profileId;
            dataModelState.tablesByUpperName = tablesByUpperName;
            dataModelState.reverseFks = reverseFks;
        } catch (err) {
            console.debug('[sql-runner] dataModel load failed:', err);
        } finally {
            dataModelState.loading = null;
        }
    })();
    return dataModelState.loading;
}

// Returns [{ column, targetTable, targetColumn }] for outgoing FKs of the
// given table (if the dataModel knows this table), null otherwise.
function outgoingFks(tableName) {
    if (!tableName || !dataModelState.tablesByUpperName) return null;
    const t = dataModelState.tablesByUpperName.get(String(tableName).toUpperCase());
    if (!t || !Array.isArray(t.relations)) return null;
    const out = [];
    const seenColumns = new Set();
    for (const r of t.relations) {
        if (!r || !r.targetTable) continue;
        const card = String(r.cardinality || '').toLowerCase();
        if (card !== 'manytoone' && card !== 'onetoone') continue;
        const mapping = Array.isArray(r.mappings) && r.mappings.length > 0
            ? r.mappings[0] : null;
        if (!mapping || !mapping.sourceColumn) continue;
        // De-dup by source column — multiple relations sometimes share one FK column
        if (seenColumns.has(mapping.sourceColumn)) continue;
        seenColumns.add(mapping.sourceColumn);
        out.push({
            column: mapping.sourceColumn,
            targetTable: r.targetTable,
            targetColumn: mapping.targetColumn || 'id',
        });
    }
    return out;
}

function incomingFks(tableName) {
    if (!tableName || !dataModelState.reverseFks) return null;
    return dataModelState.reverseFks.get(String(tableName).toUpperCase()) || [];
}

async function jumpToReferencedRow(fk, fkValue) {
    if (fkValue == null || fkValue === '') {
        (window.showToast || alert)('Cell value is null — nothing to jump to.', 'info');
        return;
    }
    const env = sqlState.env || document.getElementById('sql-env')?.value || 'sandbox';
    const sql = `SELECT * FROM \`${fk.targetTable}\` WHERE \`${fk.targetColumn}\` = ${sqlLiteral(fkValue)} LIMIT 20`;
    await openInlineQueryResult(env, sql, `Row referenced by ${fk.column} → ${fk.targetTable}.${fk.targetColumn} = ${fkValue}`, fk.targetTable);
}

async function findRowsReferencingThis(tableName, pkColumn, pkValue) {
    const refs = incomingFks(tableName);
    if (!refs || refs.length === 0) {
        (window.showToast || alert)(`No tables reference ${tableName} in the dataModel.`, 'info');
        return;
    }
    if (pkValue == null || pkValue === '') {
        (window.showToast || alert)('Row has no PK value to reverse-look up.', 'error');
        return;
    }
    const env = sqlState.env || document.getElementById('sql-env')?.value || 'sandbox';
    // Run one query per referencing table so we get a clean per-table result
    // (UNION would collapse them into one shape, which most callers don't want).
    closeReverseFkModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-reverse-fk-modal';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-panel sql-compare-panel">
            <h3>Rows referencing ${escapeHtml(tableName)}.${escapeHtml(pkColumn)} = ${escapeHtml(String(pkValue))}</h3>
            <p class="modal-help">Running ${refs.length} quer${refs.length === 1 ? 'y' : 'ies'}, one per referencing table.</p>
            <div id="sql-reverse-fk-body"></div>
            <div class="modal-actions">
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeReverseFkModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeReverseFkModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeReverseFkModal(); document.removeEventListener('keydown', esc); }
    });

    const body = overlay.querySelector('#sql-reverse-fk-body');
    for (const ref of refs) {
        const block = document.createElement('details');
        block.className = 'multi-stmt';
        block.open = true;
        block.innerHTML = `
            <summary class="multi-stmt-head">
                <span class="multi-stmt-chevron" aria-hidden="true">▾</span>
                <code class="multi-stmt-sql">${escapeHtml(ref.fromTable)}.${escapeHtml(ref.fromColumn)}</code>
                <span class="multi-stmt-status">running…</span>
            </summary>
            <div class="multi-stmt-body"></div>
        `;
        body.appendChild(block);
        const statusEl = block.querySelector('.multi-stmt-status');
        const bodyEl = block.querySelector('.multi-stmt-body');
        try {
            const sql = `SELECT * FROM \`${ref.fromTable}\` WHERE \`${ref.fromColumn}\` = ${sqlLiteral(pkValue)} LIMIT 100`;
            const result = await executeSql({ env, sql });
            if (!result.success) {
                statusEl.className = 'multi-stmt-status fail';
                statusEl.textContent = 'failed';
                bodyEl.innerHTML = `<pre class="sql-raw">${escapeHtml(String(result.body || result.error || 'error'))}</pre>`;
                continue;
            }
            const rows = unwrapFawbResponse(result.data);
            statusEl.className = rows.length > 0 ? 'multi-stmt-status ok' : 'multi-stmt-status';
            statusEl.textContent = `${rows.length} row${rows.length === 1 ? '' : 's'}`;
            bodyEl.innerHTML = renderSimpleTable(rows);
            wireMultiStmtRows(bodyEl, rows, ref.fromTable);
        } catch (err) {
            statusEl.className = 'multi-stmt-status fail';
            statusEl.textContent = 'error';
            bodyEl.innerHTML = `<pre class="sql-raw">${escapeHtml(err.message)}</pre>`;
        }
    }
}

function closeReverseFkModal() {
    const existing = document.getElementById('sql-reverse-fk-modal');
    if (existing) existing.remove();
}

async function openInlineQueryResult(env, sql, title, tableName) {
    closeInlineResultModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-inline-result-modal';
    overlay.className = 'modal-overlay';
    overlay.innerHTML = `
        <div class="modal-panel sql-compare-panel">
            <h3>${escapeHtml(title)}</h3>
            <p class="modal-help"><code>${escapeHtml(sql)}</code></p>
            <div id="sql-inline-result-body"><p class="muted">Running…</p></div>
            <div class="modal-actions">
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeInlineResultModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeInlineResultModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeInlineResultModal(); document.removeEventListener('keydown', esc); }
    });
    const body = overlay.querySelector('#sql-inline-result-body');
    try {
        const result = await executeSql({ env, sql });
        if (!result.success) {
            body.innerHTML = `<pre class="sql-raw">${escapeHtml(String(result.body || result.error || 'error'))}</pre>`;
            return;
        }
        const rows = unwrapFawbResponse(result.data);
        if (rows.length === 0) {
            body.innerHTML = `<p class="muted">No rows.</p>`;
            return;
        }
        body.innerHTML = renderSimpleTable(rows);
        wireMultiStmtRows(body, rows, tableName || inferTableName(sql));
    } catch (err) {
        body.innerHTML = `<pre class="sql-raw">${escapeHtml(err.message)}</pre>`;
    }
}

function closeInlineResultModal() {
    const existing = document.getElementById('sql-inline-result-modal');
    if (existing) existing.remove();
}

/* ---------- Named parameters ---------- */
// Detect ":paramName" tokens outside strings/comments. Same tokeniser
// concept as the SQL splitter — walk the string, skip over quoted regions
// and comments so we don't treat colons inside a string as params.

function findNamedParams(src) {
    if (!src) return [];
    const found = new Set();
    let i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i];
        const c2 = i + 1 < n ? src[i + 1] : '';
        if (c === '-' && c2 === '-') {
            while (i < n && src[i] !== '\n') i++;
            continue;
        }
        if (c === '/' && c2 === '*') {
            i += 2;
            while (i < n && !(src[i] === '*' && src[i + 1] === '/')) i++;
            if (i < n) i += 2;
            continue;
        }
        if (c === "'" || c === '"') {
            const quote = c;
            i++;
            while (i < n) {
                if (src[i] === '\\' && i + 1 < n) { i += 2; continue; }
                if (src[i] === quote) { i++; break; }
                i++;
            }
            continue;
        }
        // ":name" — only when preceded by whitespace/punctuation (not a::b)
        if (c === ':' && /[A-Za-z_]/.test(c2)) {
            const prev = i > 0 ? src[i - 1] : ' ';
            if (prev === ':') { i++; continue; }   // "::" is a type-cast, skip
            const start = i + 1;
            let j = start;
            while (j < n && /[A-Za-z0-9_]/.test(src[j])) j++;
            const name = src.substring(start, j);
            if (name) found.add(name);
            i = j;
            continue;
        }
        i++;
    }
    return [...found];
}

// Persist per-query param values so re-running the same query keeps them
const PARAMS_KEY = 'devbridge.sql.params';
function readParamsCache() {
    try { return JSON.parse(localStorage.getItem(PARAMS_KEY) || '{}'); }
    catch { return {}; }
}
function writeParamsCache(obj) {
    try { localStorage.setItem(PARAMS_KEY, JSON.stringify(obj)); } catch { /* full — ignore */ }
}

function refreshParamsPanel() {
    const panel = document.getElementById('sql-params');
    const textarea = document.getElementById('sql-input');
    if (!panel || !textarea) return;
    const params = findNamedParams(textarea.value);
    if (params.length === 0) {
        panel.classList.add('hidden');
        panel.innerHTML = '';
        return;
    }
    const cache = readParamsCache();
    panel.classList.remove('hidden');
    panel.innerHTML = `
        <div class="sql-params-header">
            <strong>Query parameters</strong>
            <span class="muted">detected <code>:${params.length === 1 ? params[0] : `${params.length}`}</code> — values substituted before running</span>
        </div>
        <div class="sql-params-grid">
            ${params.map(p => `
                <label class="sql-params-item">
                    <span class="sql-params-name">:${escapeHtml(p)}</span>
                    <input type="text" data-param="${escapeHtml(p)}" value="${escapeHtml(cache[p] != null ? cache[p] : '')}" autocomplete="off" spellcheck="false">
                </label>
            `).join('')}
        </div>
    `;
    panel.querySelectorAll('input[data-param]').forEach(input => {
        input.addEventListener('input', () => {
            const c = readParamsCache();
            c[input.dataset.param] = input.value;
            writeParamsCache(c);
        });
    });
}

// Substitute :params in the SQL with sqlLiteral values. Reuses the same
// walker as findNamedParams so we don't touch string literals or comments.
function substituteNamedParams(src, values) {
    if (!src) return src;
    let out = '';
    let i = 0;
    const n = src.length;
    while (i < n) {
        const c = src[i];
        const c2 = i + 1 < n ? src[i + 1] : '';
        if (c === '-' && c2 === '-') {
            while (i < n && src[i] !== '\n') { out += src[i++]; }
            continue;
        }
        if (c === '/' && c2 === '*') {
            out += c; out += c2; i += 2;
            while (i < n && !(src[i] === '*' && src[i + 1] === '/')) out += src[i++];
            if (i < n) { out += '*/'; i += 2; }
            continue;
        }
        if (c === "'" || c === '"') {
            const quote = c;
            out += c; i++;
            while (i < n) {
                const q = src[i];
                out += q;
                if (q === '\\' && i + 1 < n) { out += src[i + 1]; i += 2; continue; }
                i++;
                if (q === quote) break;
            }
            continue;
        }
        if (c === ':' && /[A-Za-z_]/.test(c2)) {
            const prev = i > 0 ? src[i - 1] : ' ';
            if (prev === ':') { out += c; i++; continue; }
            const start = i + 1;
            let j = start;
            while (j < n && /[A-Za-z0-9_]/.test(src[j])) j++;
            const name = src.substring(start, j);
            if (Object.prototype.hasOwnProperty.call(values, name)) {
                out += sqlLiteral(values[name]);
            } else {
                out += src.substring(i, j);   // leave untouched
            }
            i = j;
            continue;
        }
        out += c; i++;
    }
    return out;
}

/* ---------- Explain: plain-English description + execution plan ---------- */

async function runExplain() {
    const env = document.getElementById('sql-env').value;
    const raw = document.getElementById('sql-input').value.trim();
    if (!raw) {
        showStatus('Enter a SQL query first.', 'error');
        return;
    }
    // Strip trailing semicolon(s) and substitute params before wrapping in EXPLAIN
    const params = findNamedParams(raw);
    const substituted = params.length ? substituteNamedParams(raw, collectParamValues(params)) : raw;
    const clean = substituted.replace(/;\s*$/, '');

    const explainBtn = document.getElementById('btn-explain-sql');
    if (explainBtn) explainBtn.disabled = true;

    // Show the modal immediately with the plain-English description; run EXPLAIN
    // in the background and fill in the plan below.
    const description = describeSql(clean);
    openExplainModal(clean, description);
    const planBody = document.getElementById('sql-explain-plan');
    if (!planBody) { if (explainBtn) explainBtn.disabled = false; return; }
    planBody.innerHTML = `<p class="muted">Running <code>EXPLAIN</code>…</p>`;

    try {
        const result = await executeSql({ env, sql: `EXPLAIN ${clean}` });
        if (!result.success) {
            planBody.innerHTML = `<pre class="sql-raw">${escapeHtml(String(result.body || result.error || 'error'))}</pre>`;
            return;
        }
        const rows = unwrapFawbResponse(result.data);
        if (rows.length === 0) {
            planBody.innerHTML = `<p class="muted">EXPLAIN returned no rows (statement may not be plannable).</p>`;
            return;
        }
        planBody.innerHTML = renderSimpleTable(rows);
        wireMultiStmtRows(planBody, rows, null);
    } catch (err) {
        planBody.innerHTML = `<pre class="sql-raw">${escapeHtml(err.message)}</pre>`;
    } finally {
        if (explainBtn) explainBtn.disabled = false;
    }
}

function openExplainModal(sql, description) {
    closeExplainModal();
    const overlay = document.createElement('div');
    overlay.id = 'sql-explain-modal';
    overlay.className = 'modal-overlay';
    const flow = buildLogicalFlow(sql);
    const flowHtml = flow.length ? renderLogicalFlow(flow) : '<em class="muted">No logical flow available for this statement.</em>';
    overlay.innerHTML = `
        <div class="modal-panel sql-explain-panel">
            <h3>Query explanation</h3>
            <section class="sql-explain-section">
                <div class="sql-explain-section-label">What this query does</div>
                <div class="sql-explain-description">${description || '<em>Could not describe this statement automatically.</em>'}</div>
            </section>
            <section class="sql-explain-section">
                <div class="sql-explain-section-label">Logical execution order
                    <span class="sql-explain-hint">— SQL runs clauses in this order, not the order you wrote them</span>
                </div>
                <div class="sql-explain-flow">${flowHtml}</div>
            </section>
            <section class="sql-explain-section">
                <div class="sql-explain-section-label">Original SQL</div>
                <pre class="sql-explain-sql">${escapeHtml(sql)}</pre>
            </section>
            <section class="sql-explain-section">
                <div class="sql-explain-section-label">Execution plan (from database)
                    <span class="sql-explain-hint">— shows join order, index usage, row estimates</span>
                </div>
                <div id="sql-explain-plan" class="sql-explain-plan"></div>
            </section>
            <div class="modal-actions">
                <button class="btn secondary" data-action="close" type="button">Close</button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
    overlay.querySelector('button[data-action="close"]').addEventListener('click', closeExplainModal);
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeExplainModal(); });
    document.addEventListener('keydown', function esc(e) {
        if (e.key === 'Escape') { closeExplainModal(); document.removeEventListener('keydown', esc); }
    });
}

/* ---------- Logical execution flow ----------
 * SQL's logical order (per SQL standard):
 *   FROM/JOIN -> WHERE -> GROUP BY -> HAVING -> SELECT -> DISTINCT -> ORDER BY -> LIMIT
 * We build a step-by-step flow for the current statement, only including
 * clauses actually present. Non-SELECT statements get their own compact flow.
 */
function buildLogicalFlow(sql) {
    if (!sql) return [];
    const clean = sql
        .replace(/--[^\n]*/g, ' ')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    const upper = clean.toUpperCase();

    // Multi-statement — return empty; the description already handles them
    if (splitStatements(clean).length > 1) return [];

    // Non-SELECT statements
    if (upper.startsWith('INSERT')) return buildInsertFlow(clean);
    if (upper.startsWith('UPDATE')) return buildUpdateFlow(clean);
    if (upper.startsWith('DELETE')) return buildDeleteFlow(clean);
    if (!upper.startsWith('SELECT') && !upper.startsWith('(SELECT') && !upper.startsWith('WITH')) return [];

    // SELECT flow
    const steps = [];

    // 1. FROM (including JOINs)
    const fromMatch = clean.match(/\bFROM\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    const joinRegex = /\b(INNER\s+JOIN|LEFT\s+(?:OUTER\s+)?JOIN|RIGHT\s+(?:OUTER\s+)?JOIN|FULL\s+(?:OUTER\s+)?JOIN|CROSS\s+JOIN|JOIN)\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/gi;
    const joins = [...clean.matchAll(joinRegex)];
    if (fromMatch) {
        const detail = joins.length === 0
            ? `Read rows from <code>${escapeHtml(fromMatch[1])}</code>`
            : `Read rows from <code>${escapeHtml(fromMatch[1])}</code>, then join with ${joins.map(j => `<code>${escapeHtml(j[2])}</code>`).join(', ')}`;
        steps.push({ step: 'FROM', detail });
    }

    // 2. WHERE
    const whereMatch = clean.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:GROUP\s+BY|ORDER\s+BY|HAVING|LIMIT|UNION|$))/i);
    if (whereMatch) {
        steps.push({ step: 'WHERE', detail: `Filter rows: <code>${escapeHtml(shorten(whereMatch[1].trim(), 100))}</code>` });
    }

    // 3. GROUP BY
    const gbMatch = clean.match(/\bGROUP\s+BY\s+([\s\S]+?)(?=\s+\b(?:ORDER\s+BY|HAVING|LIMIT|UNION|$))/i);
    if (gbMatch) {
        steps.push({ step: 'GROUP BY', detail: `Group rows by <code>${escapeHtml(shorten(gbMatch[1].trim(), 80))}</code>` });
    }

    // 4. HAVING
    const havingMatch = clean.match(/\bHAVING\s+([\s\S]+?)(?=\s+\b(?:ORDER\s+BY|LIMIT|UNION|$))/i);
    if (havingMatch) {
        steps.push({ step: 'HAVING', detail: `Filter groups: <code>${escapeHtml(shorten(havingMatch[1].trim(), 80))}</code>` });
    }

    // 5. SELECT (projection)
    const selMatch = clean.match(/\bSELECT\s+(DISTINCT\s+)?([\s\S]+?)\s+FROM\s+/i);
    if (selMatch) {
        const projection = selMatch[2].trim();
        const proj = projection === '*' ? 'all columns' : `<code>${escapeHtml(shorten(projection, 100))}</code>`;
        // Aggregate functions?
        const aggs = ['COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'GROUP_CONCAT'].filter(kw =>
            new RegExp(`\\b${kw}\\s*\\(`, 'i').test(clean));
        const aggPart = aggs.length ? ` (computes ${aggs.map(a => `<code>${a}</code>`).join(', ')})` : '';
        steps.push({ step: 'SELECT', detail: `Pick output columns: ${proj}${aggPart}` });
    }

    // 6. DISTINCT
    if (/\bSELECT\s+DISTINCT\b/i.test(clean)) {
        steps.push({ step: 'DISTINCT', detail: 'Remove duplicate rows from the projected result' });
    }

    // 7. ORDER BY
    const obMatch = clean.match(/\bORDER\s+BY\s+([\s\S]+?)(?=\s+\b(?:LIMIT|UNION|$))/i);
    if (obMatch) {
        steps.push({ step: 'ORDER BY', detail: `Sort rows by <code>${escapeHtml(shorten(obMatch[1].trim(), 80))}</code>` });
    }

    // 8. LIMIT / OFFSET
    const limitMatch = clean.match(/\bLIMIT\s+(\d+)(?:\s*,\s*(\d+)|\s+OFFSET\s+(\d+))?/i);
    if (limitMatch) {
        const detail = (limitMatch[2] || limitMatch[3])
            ? `Take <strong>${limitMatch[2] || limitMatch[1]}</strong> rows starting from offset <strong>${limitMatch[2] || limitMatch[3]}</strong>`
            : `Take the first <strong>${limitMatch[1]}</strong> row${limitMatch[1] === '1' ? '' : 's'}`;
        steps.push({ step: 'LIMIT', detail });
    }

    // UNION note
    if (/\bUNION(\s+ALL)?\b/i.test(clean)) {
        steps.push({ step: 'UNION', detail: 'Merge results with another SELECT (rows combined into one result set)' });
    }

    return steps;
}

function buildInsertFlow(sql) {
    const m = sql.match(/\bINSERT\s+INTO\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    if (!m) return [];
    const table = m[1];
    const isSelect = /\bSELECT\b/i.test(sql.slice(m.index + m[0].length));
    if (isSelect) {
        return [
            { step: 'SELECT (source)', detail: 'Evaluate the source SELECT to produce candidate rows' },
            { step: 'INSERT', detail: `Write each produced row into <code>${escapeHtml(table)}</code>` },
        ];
    }
    return [
        { step: 'VALUES', detail: 'Evaluate the literal values / expressions' },
        { step: 'INSERT', detail: `Write into <code>${escapeHtml(table)}</code>` },
    ];
}

function buildUpdateFlow(sql) {
    const tMatch = sql.match(/\bUPDATE\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    if (!tMatch) return [];
    const table = tMatch[1];
    const steps = [
        { step: 'FROM', detail: `Read rows from <code>${escapeHtml(table)}</code>` },
    ];
    const whereMatch = sql.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:LIMIT|$))/i);
    if (whereMatch) {
        steps.push({ step: 'WHERE', detail: `Filter to rows matching <code>${escapeHtml(shorten(whereMatch[1].trim(), 100))}</code>` });
    } else {
        steps.push({ step: 'WHERE', detail: '<strong>No WHERE clause — every row selected.</strong>' });
    }
    const setMatch = sql.match(/\bSET\s+([\s\S]+?)(?=\s+\b(?:WHERE|LIMIT|$))/i);
    if (setMatch) {
        steps.push({ step: 'SET', detail: `Apply <code>${escapeHtml(shorten(setMatch[1].trim(), 100))}</code> to each selected row` });
    }
    steps.push({ step: 'UPDATE', detail: 'Persist the changes to the database' });
    return steps;
}

function buildDeleteFlow(sql) {
    const tMatch = sql.match(/\bDELETE\s+FROM\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    if (!tMatch) return [];
    const table = tMatch[1];
    const steps = [
        { step: 'FROM', detail: `Read rows from <code>${escapeHtml(table)}</code>` },
    ];
    const whereMatch = sql.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:LIMIT|$))/i);
    if (whereMatch) {
        steps.push({ step: 'WHERE', detail: `Filter to rows matching <code>${escapeHtml(shorten(whereMatch[1].trim(), 100))}</code>` });
    } else {
        steps.push({ step: 'WHERE', detail: '<strong>No WHERE clause — every row selected.</strong>' });
    }
    steps.push({ step: 'DELETE', detail: 'Remove the selected rows' });
    return steps;
}

function renderLogicalFlow(steps) {
    if (!steps || steps.length === 0) return '';
    return `
        <ol class="sql-flow">
            ${steps.map((s, i) => `
                <li class="sql-flow-step">
                    <div class="sql-flow-num">${i + 1}</div>
                    <div class="sql-flow-body">
                        <div class="sql-flow-label">${escapeHtml(s.step)}</div>
                        <div class="sql-flow-detail">${s.detail}</div>
                    </div>
                </li>
            `).join('')}
        </ol>
    `;
}

function closeExplainModal() {
    const existing = document.getElementById('sql-explain-modal');
    if (existing) existing.remove();
}

/* ---------- Plain-English SQL describer (heuristic) ----------
 * Not a full parser — regex-based extraction of top-level clauses.
 * Handles common SELECT/INSERT/UPDATE/DELETE patterns cleanly.
 * Complex nested queries or CTEs are described at a high level.
 */
function describeSql(sql) {
    if (!sql) return null;
    // Remove comments so they don't fool the regex
    const clean = sql
        .replace(/--[^\n]*/g, ' ')
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    const upper = clean.toUpperCase();

    // Multi-statement guard — describer works on one statement at a time
    const stmts = splitStatements(clean);
    if (stmts.length > 1) {
        const parts = stmts.map((s, i) => `<div class="sql-explain-multi-item"><strong>#${i + 1}:</strong> ${describeSingleStatement(s)}</div>`);
        return parts.join('');
    }

    return describeSingleStatement(clean);
}

function describeSingleStatement(sql) {
    const upper = sql.toUpperCase().trim();
    if (upper.startsWith('SELECT') || upper.startsWith('(SELECT')) return describeSelect(sql);
    if (upper.startsWith('INSERT')) return describeInsert(sql);
    if (upper.startsWith('UPDATE')) return describeUpdate(sql);
    if (upper.startsWith('DELETE')) return describeDelete(sql);
    if (upper.startsWith('WITH')) return `A WITH (CTE) query: this defines one or more named subqueries and then runs a main query using them. See the full SQL for the specific definitions.`;
    if (upper.startsWith('EXPLAIN')) return `An EXPLAIN request — asks the database for the execution plan of the wrapped statement.`;
    if (upper.startsWith('SHOW')) return `A SHOW command — returns database metadata (tables, columns, indexes, etc.).`;
    if (upper.startsWith('SET')) return `A SET command — assigns a session variable or option.`;
    if (upper.startsWith('CREATE')) return `A CREATE statement — creates a new database object (table, index, view, etc.).`;
    if (upper.startsWith('DROP')) return `A DROP statement — removes a database object. <strong>Irreversible.</strong>`;
    if (upper.startsWith('ALTER')) return `An ALTER statement — modifies an existing database object.`;
    return `A statement starting with <code>${escapeHtml(upper.split(/\s/)[0])}</code>.`;
}

function describeSelect(sql) {
    const parts = [];
    // SELECT list — everything between the first SELECT and the first FROM (naive but fine at top level)
    const selMatch = sql.match(/\bSELECT\s+(DISTINCT\s+)?([\s\S]+?)\s+FROM\s+/i);
    if (selMatch) {
        const distinct = !!selMatch[1];
        const cols = selMatch[2].trim();
        const colDesc = cols === '*' ? 'all columns' : `columns <code>${escapeHtml(shorten(cols, 120))}</code>`;
        parts.push(`Fetches ${distinct ? 'the distinct set of ' : ''}${colDesc}`);
    } else {
        const noFromSelMatch = sql.match(/\bSELECT\s+([\s\S]+?)$/i);
        if (noFromSelMatch) {
            return `Fetches the value(s) <code>${escapeHtml(shorten(noFromSelMatch[1].trim(), 120))}</code> (no FROM clause — likely constants or functions).`;
        }
    }

    // Main table
    const fromMatch = sql.match(/\bFROM\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    if (fromMatch) parts.push(`from table <code>${escapeHtml(fromMatch[1])}</code>`);

    // JOINs
    const joinRegex = /\b(INNER\s+JOIN|LEFT\s+(?:OUTER\s+)?JOIN|RIGHT\s+(?:OUTER\s+)?JOIN|FULL\s+(?:OUTER\s+)?JOIN|CROSS\s+JOIN|JOIN)\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/gi;
    const joins = [...sql.matchAll(joinRegex)];
    if (joins.length) {
        const list = joins.map(j => `<code>${escapeHtml(j[2])}</code> (${j[1].toLowerCase().replace(/\s+/g, ' ')})`).join(', ');
        parts.push(`joined with ${list}`);
    }

    // WHERE clause
    const whereMatch = sql.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:GROUP\s+BY|ORDER\s+BY|HAVING|LIMIT|UNION|$))/i);
    if (whereMatch) parts.push(`filtered by <code>${escapeHtml(shorten(whereMatch[1].trim(), 120))}</code>`);

    // GROUP BY
    const gbMatch = sql.match(/\bGROUP\s+BY\s+([\s\S]+?)(?=\s+\b(?:ORDER\s+BY|HAVING|LIMIT|UNION|$))/i);
    if (gbMatch) parts.push(`grouped by <code>${escapeHtml(shorten(gbMatch[1].trim(), 80))}</code>`);

    // HAVING
    const havingMatch = sql.match(/\bHAVING\s+([\s\S]+?)(?=\s+\b(?:ORDER\s+BY|LIMIT|UNION|$))/i);
    if (havingMatch) parts.push(`with group filter <code>${escapeHtml(shorten(havingMatch[1].trim(), 80))}</code>`);

    // ORDER BY
    const obMatch = sql.match(/\bORDER\s+BY\s+([\s\S]+?)(?=\s+\b(?:LIMIT|UNION|$))/i);
    if (obMatch) parts.push(`ordered by <code>${escapeHtml(shorten(obMatch[1].trim(), 80))}</code>`);

    // LIMIT
    const limitMatch = sql.match(/\bLIMIT\s+(\d+)(?:\s*,\s*(\d+)|\s+OFFSET\s+(\d+))?/i);
    if (limitMatch) {
        if (limitMatch[2] || limitMatch[3]) {
            const offset = limitMatch[2] || limitMatch[3];
            const count = limitMatch[2] ? limitMatch[1] : limitMatch[1];
            parts.push(`limited to <strong>${count}</strong> rows starting at offset <strong>${offset}</strong>`);
        } else {
            parts.push(`limited to <strong>${limitMatch[1]}</strong> row${limitMatch[1] === '1' ? '' : 's'}`);
        }
    }

    // Aggregates in the SELECT list
    const aggregates = [];
    for (const kw of ['COUNT', 'SUM', 'AVG', 'MIN', 'MAX', 'GROUP_CONCAT']) {
        if (new RegExp(`\\b${kw}\\s*\\(`, 'i').test(sql)) aggregates.push(kw);
    }
    if (aggregates.length) {
        parts.push(`using aggregate function${aggregates.length === 1 ? '' : 's'}: ${aggregates.map(a => `<code>${a}</code>`).join(', ')}`);
    }

    // Subquery hint
    const parenSelectCount = (sql.match(/\(\s*SELECT\b/gi) || []).length;
    if (parenSelectCount > 0) {
        parts.push(`contains <strong>${parenSelectCount}</strong> subquer${parenSelectCount === 1 ? 'y' : 'ies'}`);
    }

    // UNION hint
    if (/\bUNION(\s+ALL)?\b/i.test(sql)) {
        parts.push(`combined with UNION (results from multiple SELECTs merged)`);
    }

    if (parts.length === 0) return `A SELECT query. Could not parse details from the top-level structure.`;
    return `<strong>SELECT:</strong> ${parts.join(', ')}.`;
}

function describeInsert(sql) {
    const m = sql.match(/\bINSERT\s+INTO\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*(?:\(([^)]+)\))?/i);
    if (!m) return `An INSERT statement — parse failed.`;
    const cols = m[2] ? m[2].split(',').map(s => s.trim()) : null;
    const table = m[1];
    const isSelect = /\bSELECT\b/i.test(sql.slice(m.index + m[0].length));
    const isMultiValues = /\bVALUES\s*\([^)]*\)\s*,\s*\(/i.test(sql);
    let desc = `<strong>INSERT:</strong> writes a new row into <code>${escapeHtml(table)}</code>`;
    if (cols) desc += `, populating <code>${escapeHtml(shorten(cols.join(', '), 120))}</code>`;
    if (isSelect) desc += `. Values come from a nested SELECT (INSERT-SELECT pattern).`;
    else if (isMultiValues) desc += `. Multiple rows in one statement.`;
    else desc += `.`;
    return desc;
}

function describeUpdate(sql) {
    const tMatch = sql.match(/\bUPDATE\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    const setMatch = sql.match(/\bSET\s+([\s\S]+?)(?=\s+\b(?:WHERE|LIMIT|$))/i);
    const whereMatch = sql.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:LIMIT|$))/i);
    if (!tMatch) return `An UPDATE statement — parse failed.`;
    const parts = [`<strong>UPDATE:</strong> modifies rows in <code>${escapeHtml(tMatch[1])}</code>`];
    if (setMatch) parts.push(`setting <code>${escapeHtml(shorten(setMatch[1].trim(), 120))}</code>`);
    if (whereMatch) {
        parts.push(`filtered by <code>${escapeHtml(shorten(whereMatch[1].trim(), 120))}</code>`);
    } else {
        parts.push(`<strong>with no WHERE clause — affects EVERY row.</strong>`);
    }
    return parts.join(', ') + '.';
}

function describeDelete(sql) {
    const tMatch = sql.match(/\bDELETE\s+FROM\s+`?([A-Za-z_][A-Za-z0-9_]*)`?/i);
    const whereMatch = sql.match(/\bWHERE\s+([\s\S]+?)(?=\s+\b(?:LIMIT|$))/i);
    if (!tMatch) return `A DELETE statement — parse failed.`;
    const parts = [`<strong>DELETE:</strong> removes rows from <code>${escapeHtml(tMatch[1])}</code>`];
    if (whereMatch) parts.push(`matching <code>${escapeHtml(shorten(whereMatch[1].trim(), 120))}</code>`);
    else parts.push(`<strong>with no WHERE clause — removes EVERY row.</strong>`);
    return parts.join(', ') + '.';
}

function shorten(s, max) {
    if (!s) return '';
    if (s.length <= max) return s;
    return s.slice(0, max) + '…';
}

function collectParamValues(names) {
    const out = {};
    const cache = readParamsCache();
    for (const n of names) out[n] = cache[n] != null ? cache[n] : '';
    return out;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
