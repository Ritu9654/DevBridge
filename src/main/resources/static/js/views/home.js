export const homeView = {
    title: 'Home',
    render() {
        return `
            <div class="home-hero">
                <div class="home-hero-inner">
                    <span class="home-hero-badge">Internal tooling · FAWB</span>
                    <h2>DevBridge</h2>
                    <p class="home-tagline">The FAWB engineer's toolkit — schema exploration, environment cloning, and cross-env queries in one browser tab.</p>
                </div>
            </div>

            <section class="home-overview">
                <h3>What is DevBridge?</h3>
                <p>
                    DevBridge is a lightweight web tool that removes the friction from working with FAWB projects.
                    It replaces manual SQL, per-table cleanup, and screenshot-based data sharing with direct actions
                    you can trigger from a single browser tab.
                </p>
                <p>
                    Engineers today lose hours to workarounds that should be one click — spinning up local databases
                    just to read a schema, hand-crafting delete statements across dozens of tables, or exporting query
                    results one row at a time. DevBridge collapses each of those workflows into a single, dependable action.
                </p>
            </section>

            <section class="home-features">
                <h3>Features</h3>
                <div class="feature-grid">
                    ${featureCard({
                        icon: iconGrid(),
                        name: 'DB Viewer',
                        desc: `An interactive canvas of every table, column, and relationship in your FAWB project.
                               Reads directly from the published <code>dataModel.json</code> — no database import,
                               no local setup.`,
                    })}
                    ${featureCard({
                        icon: iconTerminal(),
                        name: 'SQL Runner',
                        desc: `Query any configured environment and get the full result set — no 20-row cap.
                               Export the response in one click as CSV, Excel, or JSON to share with anyone.`,
                    })}
                    ${featureCard({
                        icon: iconDownload(),
                        name: 'Import App',
                        desc: `Reproduce a real application in an empty sandbox. Walks the foreign-key graph
                               automatically, preserves referential integrity, and journals every insert for
                               a clean rollback path.`,
                    })}
                    ${featureCard({
                        icon: iconTrash(),
                        name: 'App Deletion',
                        desc: `Retire an application cleanly across every related table. Give it an
                               <code>appId</code> and it walks the graph in reverse — no orphan rows,
                               no missed dependencies.`,
                    })}
                </div>
            </section>
        `;
    },
    async mount() { /* landing page — nothing to fetch */ },
};

function featureCard({ icon, name, desc }) {
    return `
        <article class="feature-card">
            <div class="feature-card-icon">${icon}</div>
            <h4 class="feature-card-name">${name}</h4>
            <p class="feature-card-desc">${desc}</p>
        </article>
    `;
}

/* ---------- icons ---------- */

function iconGrid() {
    return `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>`;
}
function iconTerminal() {
    return `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/></svg>`;
}
function iconDownload() {
    return `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>`;
}
function iconTrash() {
    return `<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>`;
}
