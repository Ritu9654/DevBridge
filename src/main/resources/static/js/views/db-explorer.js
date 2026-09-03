import {
    getActiveProfile,
    listDataModelTables,
    getDataModelTable,
    getDataModelGraph,
} from '../api.js';

const CYTOSCAPE_CDN = 'https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js';

const state = {
    profileId: null,
    profileName: null,
    tables: [],           // TableSummary[] from /data-model/tables (for the list tab)
    filter: '',
    searchScope: 'all',   // 'all' | 'table' | 'field'
    selectedName: null,   // entityName currently shown on the right
    activeTab: 'list',    // 'list' | 'graph'
    graph: null,          // { nodes, edges } from /data-model/graph
    cy: null,             // Cytoscape instance once created
    cyLoading: null,      // in-flight Promise for the CDN script load
};

export const dbExplorerView = {
    title: 'DB Explorer',
    render() {
        return `
            <h2>DB Explorer</h2>
            <p>Read-only view of the active profile's dataModel. Toggle between the tabular list and the full ERD canvas.</p>

            <div class="dbe-tabs" role="tablist">
                <button class="dbe-tab active" id="dbe-tab-list" role="tab" aria-selected="true" type="button">Table structure</button>
                <button class="dbe-tab" id="dbe-tab-graph" role="tab" aria-selected="false" type="button">DB structure (ERD)</button>
            </div>

            <div id="dbe-status" class="sql-status hidden"></div>

            <!-- Tab 1: table list + detail -->
            <div class="dbe-layout" id="dbe-tab-panel-list">
                <aside class="dbe-tables card">
                    <div class="dbe-search-row">
                        <input type="text" id="dbe-search" class="dbe-search" placeholder="Filter tables…" autocomplete="off" spellcheck="false">
                        <div class="dbe-scope" role="tablist" aria-label="Search scope">
                            <button type="button" class="dbe-scope-btn active" data-scope="all" role="tab" aria-selected="true" title="Search table names and field names">All</button>
                            <button type="button" class="dbe-scope-btn" data-scope="table" role="tab" aria-selected="false" title="Search table names only">Table</button>
                            <button type="button" class="dbe-scope-btn" data-scope="field" role="tab" aria-selected="false" title="Search field/column names only">Field</button>
                        </div>
                        <span class="dbe-count" id="dbe-count">—</span>
                    </div>
                    <div class="dbe-list" id="dbe-list"></div>
                </aside>
                <section class="dbe-detail card" id="dbe-detail">
                    <p class="muted">Select a table on the left to view its columns and relations.</p>
                </section>
            </div>

            <!-- Tab 2: ERD canvas -->
            <div class="dbe-erd hidden" id="dbe-tab-panel-graph">
                <div class="dbe-erd-toolbar card">
                    <input type="text" id="dbe-erd-search" class="dbe-search" placeholder="Search a table to highlight on the canvas…" autocomplete="off" spellcheck="false">
                    <span class="dbe-erd-hint" id="dbe-erd-hint">Drag to pan · scroll to zoom · click a node for details</span>
                    <button class="btn btn-small secondary" id="dbe-erd-relayout" type="button" title="Re-run the auto-layout">
                        Re-layout
                    </button>
                    <button class="btn btn-small secondary" id="dbe-erd-fit" type="button" title="Fit graph to viewport">
                        Fit
                    </button>
                </div>
                <div class="dbe-erd-canvas card" id="dbe-erd-canvas">
                    <div class="dbe-erd-loading" id="dbe-erd-loading">Loading canvas…</div>
                </div>
            </div>
        `;
    },

    async mount() {
        state.tables = [];
        state.filter = '';
        state.searchScope = 'all';
        state.selectedName = null;
        state.activeTab = 'list';
        state.graph = null;
        state.cy = null;

        let active = null;
        try { active = await getActiveProfile(); } catch { /* ignore */ }
        if (!active) {
            showStatus('No active profile. <a href="#/profiles">Add and activate one</a>, then upload its dataModel.', 'error');
            return;
        }
        state.profileId = active.id;
        state.profileName = active.name;

        try {
            state.tables = await listDataModelTables(active.id);
        } catch (err) {
            const msg = err && err.message ? err.message : String(err);
            showStatus(`Could not load tables: ${msg}. Upload a dataModel from <a href="#/profiles">Project Profiles</a> first.`, 'error');
            return;
        }
        if (!state.tables || state.tables.length === 0) {
            showStatus(`No tables in the dataModel for <strong>${escapeHtml(active.name)}</strong>.`, 'error');
            return;
        }

        renderList();
        wireListTab();
        wireTabs();
        wireErdToolbar();
    },
};

/* ---------------- Tab switching ---------------- */

function wireTabs() {
    document.getElementById('dbe-tab-list').addEventListener('click', () => switchTab('list'));
    document.getElementById('dbe-tab-graph').addEventListener('click', () => switchTab('graph'));
}

async function switchTab(tab) {
    if (state.activeTab === tab) return;
    state.activeTab = tab;

    document.getElementById('dbe-tab-list').classList.toggle('active', tab === 'list');
    document.getElementById('dbe-tab-list').setAttribute('aria-selected', tab === 'list');
    document.getElementById('dbe-tab-graph').classList.toggle('active', tab === 'graph');
    document.getElementById('dbe-tab-graph').setAttribute('aria-selected', tab === 'graph');

    document.getElementById('dbe-tab-panel-list').classList.toggle('hidden', tab !== 'list');
    document.getElementById('dbe-tab-panel-graph').classList.toggle('hidden', tab !== 'graph');

    if (tab === 'graph' && !state.cy) {
        await initErd();
    }
}

/* ---------------- Tab 1: list ---------------- */

function wireListTab() {
    const search = document.getElementById('dbe-search');
    search.addEventListener('input', () => {
        state.filter = search.value.trim().toLowerCase();
        renderList();
    });
    document.querySelectorAll('.dbe-scope-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const scope = btn.dataset.scope;
            if (!scope || scope === state.searchScope) return;
            state.searchScope = scope;
            document.querySelectorAll('.dbe-scope-btn').forEach(b => {
                const on = b.dataset.scope === scope;
                b.classList.toggle('active', on);
                b.setAttribute('aria-selected', on ? 'true' : 'false');
            });
            renderList();
        });
    });
    search.focus();
}

function renderList() {
    const list = document.getElementById('dbe-list');
    const count = document.getElementById('dbe-count');
    const filtered = applyFilter(state.tables, state.filter, state.searchScope);
    count.textContent = `${filtered.length} / ${state.tables.length}`;
    if (filtered.length === 0) {
        list.innerHTML = `<p class="muted" style="padding:12px;">No matches.</p>`;
        return;
    }
    list.innerHTML = filtered.map(({ table: t, matchedColumns }) => `
        <button class="dbe-list-item ${t.entityName === state.selectedName ? 'active' : ''}"
                data-entity="${escapeHtml(t.entityName)}" type="button">
            <div class="dbe-list-item-title">
                <span class="dbe-entity">${escapeHtml(t.entityName || '(no name)')}</span>
                ${t.type === 'VIEW' ? '<span class="badge badge-muted">VIEW</span>' : ''}
                ${t.compositePk ? '<span class="badge badge-muted">composite PK</span>' : ''}
            </div>
            <div class="dbe-list-item-sub">
                <code>${escapeHtml(t.name || '')}</code>
                &middot; ${t.columnCount} cols
                &middot; ${t.relationCount} rels
            </div>
            ${matchedColumns && matchedColumns.length ? `
                <div class="dbe-list-item-match">
                    matched columns: ${matchedColumns.slice(0, 4).map(c => `<code>${escapeHtml(c)}</code>`).join(', ')}${matchedColumns.length > 4 ? ` +${matchedColumns.length - 4}` : ''}
                </div>
            ` : ''}
        </button>
    `).join('');
    list.querySelectorAll('.dbe-list-item').forEach(btn => {
        btn.addEventListener('click', () => selectTable(btn.dataset.entity));
    });
}

/**
 * Filter across three axes: physical table name, entityName, and any column
 * (DB name or fieldName). Returns wrapper objects so the renderer can show
 * WHICH columns matched — otherwise "device" would seem to match tables
 * randomly when it's actually finding a column named device_id.
 *
 * scope: 'all' matches name+entity OR columns; 'table' restricts to name+entity;
 * 'field' restricts to columns.
 */
function applyFilter(tables, filter, scope = 'all') {
    if (!filter) {
        return tables.map(t => ({ table: t, matchedColumns: [] }));
    }
    const q = filter.toLowerCase();
    const includeName = scope === 'all' || scope === 'table';
    const includeField = scope === 'all' || scope === 'field';
    const nameMatches = [];       // matched via table name or entityName
    const columnOnlyMatches = []; // matched only via column names
    for (const t of tables) {
        const name = (t.name || '').toLowerCase();
        const entity = (t.entityName || '').toLowerCase();
        const nameOrEntityMatch = includeName && (name.includes(q) || entity.includes(q));
        const matchedColumns = [];
        if (includeField && Array.isArray(t.columnNames)) {
            for (const c of t.columnNames) {
                if (c && c.toLowerCase().includes(q)) matchedColumns.push(c);
            }
        }
        if (nameOrEntityMatch) {
            nameMatches.push({ table: t, matchedColumns });
        } else if (matchedColumns.length > 0) {
            columnOnlyMatches.push({ table: t, matchedColumns });
        }
    }
    // Name/entity hits are the primary intent — surface them first, column-only hits after.
    return [...nameMatches, ...columnOnlyMatches];
}

async function selectTable(entityName) {
    if (!entityName) return;
    // If we were on the ERD tab, switch back to list so the detail is visible
    if (state.activeTab !== 'list') switchTab('list');
    state.selectedName = entityName;
    renderList();

    const pane = document.getElementById('dbe-detail');
    pane.innerHTML = `<p class="muted">Loading ${escapeHtml(entityName)}…</p>`;
    try {
        const table = await getDataModelTable(state.profileId, entityName);
        pane.innerHTML = renderDetail(table);
    } catch (err) {
        pane.innerHTML = `<p class="muted">Failed to load table: ${escapeHtml(err.message || String(err))}</p>`;
    }
}

function renderDetail(t) {
    const pk = t.primaryKey || {};
    const pkCols = Array.isArray(pk.columns) ? pk.columns : [];
    const gen = pk.generator || {};
    const columns = Array.isArray(t.columns) ? t.columns : [];
    const relations = Array.isArray(t.relations) ? t.relations : [];

    return `
        <div class="dbe-detail-head">
            <h3>${escapeHtml(t.entityName || '(no entity)')}</h3>
            <div class="dbe-detail-sub">
                <code>${escapeHtml(t.name || '')}</code>
                ${t.type ? `&middot; <span class="badge badge-muted">${escapeHtml(t.type)}</span>` : ''}
                ${t.catalog ? `&middot; catalog <code>${escapeHtml(t.catalog)}</code>` : ''}
            </div>
        </div>

        <div class="dbe-section">
            <div class="dbe-section-title">Primary key</div>
            ${pkCols.length === 0
                ? '<p class="muted">None.</p>'
                : `<p>
                    ${pkCols.map(c => `<code>${escapeHtml(c)}</code>`).join(', ')}
                    ${pk.composite ? ' <span class="badge badge-muted">composite</span>' : ''}
                    ${pk.virtual ? ' <span class="badge badge-muted">virtual</span>' : ''}
                    ${gen.generatorType ? ` <span class="badge badge-muted">${escapeHtml(gen.generatorType)}</span>` : ''}
                    ${gen.generatorValue ? ` <code>${escapeHtml(gen.generatorValue)}</code>` : ''}
                </p>`}
        </div>

        <div class="dbe-section">
            <div class="dbe-section-title">Columns <span class="hint-inline">(${columns.length})</span></div>
            ${columns.length === 0 ? '<p class="muted">None.</p>' : `
                <div class="sql-table-wrapper">
                    <table class="sql-table dbe-cols">
                        <thead>
                            <tr>
                                <th>Column</th><th>Field</th><th>SQL type</th><th>Java type</th>
                                <th>Nullable</th><th>PK</th><th>FK</th><th>Generator</th>
                                <th>Insertable</th><th>Updatable</th><th>Mask</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${columns.map(c => `
                                <tr>
                                    <td><code>${escapeHtml(c.name || '')}</code></td>
                                    <td>${escapeHtml(c.fieldName || '')}</td>
                                    <td>${escapeHtml(c.sqlType || '')}</td>
                                    <td class="muted">${escapeHtml(c.javaType || '')}</td>
                                    <td>${boolBadge(c.nullable)}</td>
                                    <td>${c.primaryKey ? '<span class="badge">PK</span>' : ''}</td>
                                    <td>${c.foreignKey ? '<span class="badge badge-muted">FK</span>' : ''}</td>
                                    <td class="muted">${escapeHtml(c.generatorType || '')}</td>
                                    <td>${boolBadge(c.columnValue && c.columnValue.insertable)}</td>
                                    <td>${boolBadge(c.columnValue && c.columnValue.updatable)}</td>
                                    <td>${maskLabel(c.mask)}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            `}
        </div>

        <div class="dbe-section">
            <div class="dbe-section-title">Relations <span class="hint-inline">(${relations.length})</span></div>
            ${relations.length === 0 ? '<p class="muted">None.</p>' : `
                <div class="sql-table-wrapper">
                    <table class="sql-table dbe-rels">
                        <thead>
                            <tr>
                                <th>Name</th><th>Cardinality</th><th>Field</th><th>Target</th>
                                <th>Mapping</th><th>Cascade</th><th>Virtual</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${relations.map(r => `
                                <tr>
                                    <td>${escapeHtml(r.name || '')}</td>
                                    <td><span class="badge badge-muted">${escapeHtml(r.cardinality || '')}</span></td>
                                    <td>${escapeHtml(r.fieldName || '')}</td>
                                    <td>
                                        <button class="dbe-target-link" type="button"
                                                data-target="${escapeHtml(r.targetTable || '')}"
                                                title="Open ${escapeHtml(r.targetTable || '')}">
                                            <code>${escapeHtml(r.targetTable || '')}</code>
                                        </button>
                                    </td>
                                    <td class="muted">${renderMappings(r.mappings)}</td>
                                    <td>${boolBadge(r.cascadeEnabled)}</td>
                                    <td>${r.virtual ? '<span class="badge badge-muted">virtual</span>' : ''}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            `}
        </div>
    `;
}

/** Global click for target-table links inside the detail pane. Idempotent because
 *  the module only loads once. */
document.addEventListener('click', (evt) => {
    const btn = evt.target.closest('.dbe-target-link');
    if (!btn) return;
    const target = btn.dataset.target;
    if (target) selectTable(target);
});

function renderMappings(mappings) {
    if (!Array.isArray(mappings) || mappings.length === 0) return '';
    return mappings.map(m =>
        `<code>${escapeHtml(m.sourceColumn || '')}</code> → <code>${escapeHtml(m.targetColumn || '')}</code>`
    ).join('<br>');
}

/* ---------------- Tab 2: ERD ---------------- */

function wireErdToolbar() {
    document.getElementById('dbe-erd-search').addEventListener('input', (e) => {
        highlightNodes(e.target.value.trim().toLowerCase());
    });
    document.getElementById('dbe-erd-relayout').addEventListener('click', () => {
        if (state.cy) runLayout(state.cy);
    });
    document.getElementById('dbe-erd-fit').addEventListener('click', () => {
        if (state.cy) state.cy.fit(null, 30);
    });
}

async function initErd() {
    const loading = document.getElementById('dbe-erd-loading');
    loading.textContent = 'Loading Cytoscape…';
    try {
        await loadCytoscape();
    } catch (err) {
        loading.innerHTML = `Failed to load Cytoscape from CDN. `
            + `Check your network, or ask to have it bundled locally. `
            + `<pre style="text-align:left;font-size:11px;">${escapeHtml(err.message || String(err))}</pre>`;
        return;
    }
    loading.textContent = 'Fetching graph…';
    try {
        state.graph = await getDataModelGraph(state.profileId);
    } catch (err) {
        loading.textContent = `Could not load graph: ${err.message || err}`;
        return;
    }
    loading.textContent = 'Laying out…';
    // Defer so the "Laying out…" text paints before we hog the main thread
    setTimeout(() => {
        try {
            buildCytoscape();
            loading.remove();
        } catch (err) {
            loading.innerHTML = `Failed to render graph: <pre>${escapeHtml(err.message || String(err))}</pre>`;
        }
    }, 20);
}

function loadCytoscape() {
    if (window.cytoscape) return Promise.resolve();
    if (state.cyLoading) return state.cyLoading;
    state.cyLoading = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = CYTOSCAPE_CDN;
        script.async = true;
        script.onload = () => resolve();
        script.onerror = () => reject(new Error('Script load failed: ' + CYTOSCAPE_CDN));
        document.head.appendChild(script);
    });
    return state.cyLoading;
}

function buildCytoscape() {
    const container = document.getElementById('dbe-erd-canvas');
    // Build cytoscape elements from graph. Skip self-loops and dedupe edges by (source|target|name).
    const seenEdges = new Set();
    const elements = [];
    for (const n of state.graph.nodes) {
        elements.push({
            data: { id: n.id, label: n.id, physicalName: n.name || '',
                    columnCount: n.columnCount, relationCount: n.relationCount,
                    type: n.type || 'TABLE' }
        });
    }
    const nodeIds = new Set(state.graph.nodes.map(n => n.id));
    for (const e of state.graph.edges) {
        if (!e.source || !e.target) continue;
        if (e.source === e.target) continue;                    // no self-loops
        if (!nodeIds.has(e.source) || !nodeIds.has(e.target)) continue;
        const key = e.source + '|' + e.target + '|' + (e.name || e.cardinality || '');
        if (seenEdges.has(key)) continue;
        seenEdges.add(key);
        elements.push({
            data: {
                id: 'e_' + seenEdges.size,
                source: e.source,
                target: e.target,
                cardinality: e.cardinality || '',
                label: e.cardinality || '',
                virtual: !!e.virtual,
            }
        });
    }

    state.cy = window.cytoscape({
        container,
        elements,
        wheelSensitivity: 0.2,
        style: [
            {
                selector: 'node',
                style: {
                    'label': 'data(label)',
                    'font-size': 10,
                    'color': '#0f172a',
                    'text-valign': 'center',
                    'text-halign': 'center',
                    'background-color': '#eef2ff',
                    'border-color': '#4f46e5',
                    'border-width': 1,
                    'shape': 'round-rectangle',
                    'width': 'label',
                    'height': 20,
                    'padding': 6,
                    'text-max-width': 200,
                },
            },
            {
                selector: 'node[type = "VIEW"]',
                style: {
                    'background-color': '#f1f5f9',
                    'border-style': 'dashed',
                },
            },
            {
                selector: 'node.match',
                style: {
                    'background-color': '#fef3c7',
                    'border-color': '#d97706',
                    'border-width': 2,
                    'z-index': 10,
                },
            },
            {
                selector: 'node.dimmed',
                style: {
                    'opacity': 0.2,
                },
            },
            {
                selector: 'edge',
                style: {
                    'curve-style': 'bezier',
                    'target-arrow-shape': 'triangle',
                    'target-arrow-color': '#94a3b8',
                    'line-color': '#cbd5e1',
                    'width': 1,
                    'font-size': 9,
                    'color': '#64748b',
                    'text-rotation': 'autorotate',
                    'text-background-color': '#ffffff',
                    'text-background-opacity': 0.85,
                    'text-background-padding': 2,
                },
            },
            {
                selector: 'edge[virtual]',
                style: { 'line-style': 'dashed' },
            },
            {
                selector: 'edge.dimmed',
                style: { 'opacity': 0.15 },
            },
        ],
    });
    runLayout(state.cy);
    state.cy.on('tap', 'node', (evt) => {
        const id = evt.target.data('id');
        if (id) selectTable(id);
    });
}

function runLayout(cy) {
    cy.layout({
        name: 'cose',
        animate: false,
        idealEdgeLength: 90,
        nodeRepulsion: 8000,
        edgeElasticity: 100,
        nestingFactor: 5,
        gravity: 80,
        numIter: 1500,
        randomize: true,
        fit: true,
        padding: 40,
    }).run();
}

function highlightNodes(query) {
    if (!state.cy) return;
    if (!query) {
        state.cy.nodes().removeClass('match dimmed');
        state.cy.edges().removeClass('dimmed');
        return;
    }
    const matched = state.cy.nodes().filter(n => {
        const id = String(n.data('id') || '').toLowerCase();
        const physical = String(n.data('physicalName') || '').toLowerCase();
        return id.includes(query) || physical.includes(query);
    });
    const matchedIds = new Set(matched.map(n => n.data('id')));
    // Neighboring nodes stay visible so context is preserved; everything else dims
    const neighborhood = matched.closedNeighborhood();
    state.cy.nodes().forEach(n => {
        n.removeClass('match dimmed');
        if (matchedIds.has(n.data('id'))) n.addClass('match');
        else if (!neighborhood.contains(n)) n.addClass('dimmed');
    });
    state.cy.edges().forEach(e => {
        e.removeClass('dimmed');
        if (!neighborhood.contains(e)) e.addClass('dimmed');
    });
    if (matched.length > 0) state.cy.fit(neighborhood, 60);
}

/* ---------------- shared helpers ---------------- */

function boolBadge(v) {
    if (v === true) return '<span class="badge">yes</span>';
    if (v === false) return '<span class="badge badge-muted">no</span>';
    return '';
}

function maskLabel(mask) {
    if (!mask || !mask.type || mask.type === 'NONE') return '';
    return `<span class="badge badge-muted">${escapeHtml(mask.type)}</span>`;
}

function showStatus(html, kind) {
    const el = document.getElementById('dbe-status');
    el.innerHTML = html;
    el.className = `sql-status ${kind}`;
    el.classList.remove('hidden');
}

function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
