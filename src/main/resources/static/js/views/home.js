import { getActiveProfile } from '../api.js';

export const homeView = {
    title: 'Home',
    render() {
        return `
            <div class="home-hero">
                <h2>DevBridge</h2>
                <p class="home-tagline">Everything you need to poke at a FAWB project — schema, SQL, and app data — in one browser tab.</p>
                <p class="home-active" id="home-active-line"></p>
            </div>

            <h3>Modules</h3>
            <div class="quick-grid">
                <a href="#/profiles" class="quick-card">
                    <span class="quick-card-icon">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
                    </span>
                    <span class="quick-card-title">Project Profiles</span>
                    <span class="quick-card-desc">Add per-project URLs, service names, OAuth credentials, and upload the dataModel JSON.</span>
                </a>
                <a href="#/db-explorer" class="quick-card">
                    <span class="quick-card-icon">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v6c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 11v6c0 1.66 4 3 9 3s9-1.34 9-3v-6"/></svg>
                    </span>
                    <span class="quick-card-title">DB Explorer</span>
                    <span class="quick-card-desc">Browse the loaded dataModel — tables, columns, relations, and a full ERD canvas with search-to-highlight.</span>
                </a>
                <a href="#/sql-runner" class="quick-card">
                    <span class="quick-card-icon">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>
                    </span>
                    <span class="quick-card-title">SQL Runner</span>
                    <span class="quick-card-desc">Run SQL against design or sandbox. Export to CSV, Excel, or JSON.</span>
                </a>
                <a href="#/import-app" class="quick-card">
                    <span class="quick-card-icon">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                    </span>
                    <span class="quick-card-title">Import App</span>
                    <span class="quick-card-desc">Fetch an application from design and reproduce it in sandbox — preview the plan before writing.</span>
                </a>
                <a href="#/delete-app" class="quick-card">
                    <span class="quick-card-icon">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>
                    </span>
                    <span class="quick-card-title">Delete App</span>
                    <span class="quick-card-desc">Reverse-walk the tree and remove an application's rows across all affected tables. Coming soon.</span>
                </a>
            </div>

            <h3>Getting started</h3>
            <ol class="home-steps">
                <li><a href="#/profiles">Add a profile</a> for your project — sandbox / design URLs, DB service name, and OAuth credentials so DevBridge can auto-fetch your bearer token every 30 minutes.</li>
                <li>Upload the project's published <code>dataModel.json</code> — powers DB Explorer and drives the import walker.</li>
                <li>Activate the profile from the sidebar dropdown and pick any module.</li>
            </ol>
        `;
    },
    async mount() {
        const line = document.getElementById('home-active-line');
        if (!line) return;
        try {
            const active = await getActiveProfile();
            if (active && active.name) {
                line.innerHTML = `Active profile: <strong>${escapeHtml(active.name)}</strong>.`;
            } else {
                line.innerHTML = `No active profile. <a href="#/profiles">Add or activate one</a> to enable the modules.`;
            }
        } catch {
            line.innerHTML = `Backend unreachable — check the status indicator in the sidebar.`;
        }
    },
};

function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}
