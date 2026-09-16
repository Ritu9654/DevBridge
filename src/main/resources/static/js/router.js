// Hash-based route table.
// Each view module exports { title: string, render(): string, mount?(): void }.
import { homeView } from './views/home.js';
import { sqlRunnerView } from './views/sql-runner.js';
import { appImportView } from './views/app-import.js';
import { bulkImportView } from './views/bulk-import.js';
import { deleteAppView } from './views/delete-app.js';
import { dbExplorerView } from './views/db-explorer.js';
import { profilesView } from './views/profiles.js';

export const routes = {
    '/': homeView,
    '/sql-runner': sqlRunnerView,
    '/import-app': appImportView,
    '/bulk-import': bulkImportView,
    '/delete-app': deleteAppView,
    '/db-explorer': dbExplorerView,
    '/profiles': profilesView,
};
