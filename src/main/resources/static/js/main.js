// Entry point — bootstraps hash-based routing, backend health check,
// active-profile display, sidebar profile switcher, token modal, and toasts.
import { routes } from './router.js';
import { toggleTheme } from './theme.js';
import {
    getHealth,
    getActiveProfile,
    listProfiles,
    activateProfile,
    getAuthStatus,
    setAuthToken,
    clearAuthToken,
    fetchAuthToken,
    getStoredToken,
    clearStoredToken,
    restoreTokenToBackend,
} from './api.js';

function render() {
    const hash = window.location.hash.slice(1) || '/';
    const route = routes[hash] || routes['/'];

    document.getElementById('view-title').textContent = route.title;
    document.getElementById('view-container').innerHTML = route.render();

    document.querySelectorAll('.nav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.route === hash);
    });

    if (typeof route.mount === 'function') route.mount();
}

async function checkBackend() {
    const statusEl = document.getElementById('status');
    const statusText = document.getElementById('status-text');
    try {
        const health = await getHealth();
        statusEl.classList.remove('error');
        statusEl.classList.add('ok');
        statusText.textContent = `Backend v${health.version}`;
    } catch (err) {
        statusEl.classList.remove('ok');
        statusEl.classList.add('error');
        statusText.textContent = 'Backend unreachable';
        console.error('Health check failed', err);
    }
}

async function refreshActiveProfile() {
    const wrap = document.getElementById('active-profile');
    const nameEl = document.getElementById('switcher-name');
    try {
        const active = await getActiveProfile();
        if (active && active.name) {
            wrap.classList.remove('empty');
            nameEl.textContent = active.name;
        } else {
            wrap.classList.add('empty');
            nameEl.textContent = 'None selected';
        }
    } catch {
        wrap.classList.add('empty');
        nameEl.textContent = 'None selected';
    }
}

/* ----------- Profile switcher (sidebar footer dropdown) ----------- */

async function openProfileSwitcher() {
    const menu = document.getElementById('switcher-menu');
    const [profiles, active] = await Promise.all([listProfiles(), getActiveProfile()]);
    const activeId = active ? active.id : null;

    if (profiles.length === 0) {
        menu.innerHTML = `<a href="#/profiles" class="switcher-item switcher-add" role="menuitem">+ Add a profile</a>`;
    } else {
        const items = profiles.map(p => `
            <button class="switcher-item ${p.id === activeId ? 'active' : ''}"
                    data-id="${p.id}" type="button" role="menuitem">
                <span class="switcher-item-name">${escapeHtml(p.name || '(unnamed)')}</span>
                ${p.id === activeId ? '<span class="switcher-hint">Active</span>' : ''}
            </button>
        `).join('');
        menu.innerHTML = items + `<a href="#/profiles" class="switcher-item switcher-add" role="menuitem">Manage profiles →</a>`;
        menu.querySelectorAll('button[data-id]').forEach(btn => {
            btn.addEventListener('click', async () => {
                try {
                    await activateProfile(btn.dataset.id);
                    closeProfileSwitcher();
                    window.dispatchEvent(new CustomEvent('profile-changed'));
                } catch (err) {
                    showToast('Activate failed: ' + err.message, 'error');
                }
            });
        });
    }

    menu.classList.remove('hidden');
    document.getElementById('profile-switcher').setAttribute('aria-expanded', 'true');
}

function closeProfileSwitcher() {
    const menu = document.getElementById('switcher-menu');
    if (menu) menu.classList.add('hidden');
    const btn = document.getElementById('profile-switcher');
    if (btn) btn.setAttribute('aria-expanded', 'false');
}

/* ----------- Token modal ----------- */

async function refreshTokenStatus() {
    const btn = document.getElementById('token-status');
    const text = document.getElementById('token-status-text');
    try {
        const status = await getAuthStatus();
        if (status && status.set) {
            btn.classList.add('set');
            text.textContent = 'Token set';
        } else {
            btn.classList.remove('set');
            text.textContent = 'Token not set';
        }
    } catch {
        btn.classList.remove('set');
        text.textContent = 'Token not set';
    }
}

async function openTokenModal() {
    const modal = document.getElementById('token-modal');
    const textarea = document.getElementById('token-input');
    const title = document.getElementById('token-modal-title');
    const help = document.getElementById('token-modal-help');
    const fetchPanel = document.getElementById('token-fetch-panel');
    const fetchHint = document.getElementById('token-fetch-hint');

    let active = null;
    try { active = await getActiveProfile(); } catch { /* ignore */ }

    if (active && active.name) {
        title.textContent = `FAWB auth token — ${active.name}`;
    } else {
        title.textContent = 'FAWB auth token (no active profile)';
    }

    // Show the auto-fetch panel only when the profile has all three OAuth fields set
    const canAutoFetch = !!(active && active.tokenUrl && active.tokenClientId && active.tokenClientSecret);
    if (fetchPanel) fetchPanel.classList.toggle('hidden', !canAutoFetch);
    if (fetchHint) fetchHint.style.display = canAutoFetch ? 'none' : '';

    // Show whether an existing token is being replaced
    try {
        const status = await getAuthStatus();
        if (status && status.set) {
            help.innerHTML = `A token is already stored for this profile. <strong>Pasting a new one will replace it.</strong>`;
        } else {
            help.innerHTML = `Paste your <code>WM_AUTH_TOKEN</code> from an active FAWB browser session. Stored in browser storage per profile; cleared when the token expires or you clear it.`;
        }
    } catch { /* leave default */ }

    textarea.value = '';
    modal.classList.remove('hidden');
    // If auto-fetch is available, focus the fetch button (user's likely intent);
    // otherwise focus the paste box.
    setTimeout(() => {
        const btn = document.getElementById('btn-fetch-token');
        if (canAutoFetch && btn) btn.focus();
        else textarea.focus();
    }, 10);
}

function closeTokenModal() {
    document.getElementById('token-modal').classList.add('hidden');
    document.getElementById('token-input').value = '';
}

async function saveToken() {
    const token = document.getElementById('token-input').value.trim();
    if (!token) {
        showToast('Paste a token first.', 'error');
        return;
    }
    try {
        await setAuthToken(token);
        closeTokenModal();
        window.dispatchEvent(new CustomEvent('token-changed'));
        showToast('Token saved.', 'success');
    } catch (err) {
        showToast('Failed to save token: ' + err.message, 'error');
    }
}

async function clearToken() {
    if (!confirm('Clear the stored token for the active profile?')) return;
    try {
        await clearAuthToken();
        cancelTokenRefresh();
        closeTokenModal();
        window.dispatchEvent(new CustomEvent('token-changed'));
        showToast('Token cleared.', 'info');
    } catch (err) {
        showToast('Failed to clear token: ' + err.message, 'error');
    }
}

async function autoFetchToken() {
    const btn = document.getElementById('btn-fetch-token');
    const originalHtml = btn ? btn.innerHTML : '';
    if (btn) {
        btn.disabled = true;
        btn.textContent = 'Fetching…';
    }
    try {
        const result = await fetchAuthToken();
        closeTokenModal();
        window.dispatchEvent(new CustomEvent('token-changed'));
        const expiryNote = result && result.expiresIn ? ` (expires in ${result.expiresIn}s)` : '';
        showToast(`Token fetched${expiryNote}. Auto-refresh scheduled.`, 'success');
        if (result && result.expiresIn) scheduleTokenRefresh(result.expiresIn);
    } catch (err) {
        showToast('Fetch failed: ' + err.message, 'error', 8000);
    } finally {
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = originalHtml;
        }
    }
}

/* ----------- Proactive token refresh (no clicks) -----------
 * Schedules a silent re-fetch at ~90% of the token's TTL, so the token is always
 * fresh before FAWB starts rejecting it with 401. Kicks in whenever we get a
 * fresh token (fetch, expired-recovery, page load with credentials configured).
 * Cancelled on profile switch or manual clear.
 */
let tokenRefreshTimer = null;

function scheduleTokenRefresh(expiresInSeconds) {
    cancelTokenRefresh();
    if (!expiresInSeconds || expiresInSeconds < 60) return;   // ignore absurd TTLs
    // *900 = *0.9*1000: refresh at 90% of TTL. Floor of 30s prevents thrashing.
    const refreshInMs = Math.max(30_000, Math.floor(expiresInSeconds * 900));
    tokenRefreshTimer = setTimeout(() => { refreshTokenSilently(); }, refreshInMs);
    console.debug(`[token] auto-refresh scheduled in ${(refreshInMs / 1000).toFixed(0)}s`);
}

function cancelTokenRefresh() {
    if (tokenRefreshTimer) {
        clearTimeout(tokenRefreshTimer);
        tokenRefreshTimer = null;
    }
}

/** Silent re-fetch — no modal, no toast unless it fails. Reschedules on success. */
async function refreshTokenSilently() {
    try {
        const result = await fetchAuthToken();
        window.dispatchEvent(new CustomEvent('token-changed'));
        if (result && result.expiresIn) scheduleTokenRefresh(result.expiresIn);
        console.debug('[token] auto-refresh succeeded');
    } catch (err) {
        // Don't show a modal here — if the stale token 401s on the next real
        // request, the reactive handler will kick in. This keeps auto-refresh
        // truly silent even when it can't reach FICO's token endpoint (VPN dropped, etc.).
        console.warn('[token] auto-refresh failed:', err && err.message ? err.message : err);
    }
}

/** Kick off auto-refresh at startup if the active profile has OAuth credentials configured. */
async function initAutoRefreshFromProfile() {
    cancelTokenRefresh();
    let active = null;
    try { active = await getActiveProfile(); } catch { /* ignore */ }
    if (!active) return;
    const canAutoFetch = !!(active.tokenUrl && active.tokenClientId && active.tokenClientSecret);
    if (!canAutoFetch) return;
    // Refetch now to get a fresh token + accurate expires_in, then let it schedule itself.
    await refreshTokenSilently();
}

/* ----------- Toast notifications ----------- */

function showToast(message, kind = 'info', duration = 5000) {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = `toast toast-${kind}`;
    toast.innerHTML = `<span class="toast-msg">${escapeHtml(message)}</span><button class="toast-close" type="button" aria-label="Dismiss">&times;</button>`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('show'));

    const remove = () => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 250);
    };
    const timer = setTimeout(remove, duration);
    toast.querySelector('.toast-close').addEventListener('click', () => {
        clearTimeout(timer);
        remove();
    });
}
// Expose so views can dispatch toasts
window.showToast = showToast;

/* ----------- Token expiry handling ----------- */

async function handleTokenExpired() {
    // Backend has already cleared the slot on 401 detection; also wipe localStorage
    // so we don't auto-restore an expired token on next page load.
    let active = null;
    try {
        active = await getActiveProfile();
        if (active && active.id) clearStoredToken(active.id);
    } catch { /* ignore */ }

    refreshTokenStatus();

    // If the profile has OAuth credentials, refetch silently — no modal, no paste.
    const canAutoFetch = !!(active && active.tokenUrl && active.tokenClientId && active.tokenClientSecret);
    if (canAutoFetch) {
        try {
            const result = await fetchAuthToken();
            window.dispatchEvent(new CustomEvent('token-changed'));
            if (result && result.expiresIn) scheduleTokenRefresh(result.expiresIn);
            showToast('Token expired — auto-refreshed. Retry your request.', 'info', 5000);
            return;
        } catch (err) {
            showToast('Token expired and auto-refresh failed: ' + err.message
                    + ' — falling back to manual paste.', 'error', 7000);
        }
    } else {
        showToast('Your token has expired. Please paste a fresh one.', 'error', 6000);
    }
    setTimeout(openTokenModal, 500);
}

/* ----------- Token restore on page load ----------- */

async function restoreTokensFromStorage() {
    // For every profile that has a stored token in localStorage, push it back
    // to the backend's in-memory holder. Handles both browser refresh AND
    // Spring Boot restarts (devtools).
    let profiles;
    try {
        profiles = await listProfiles();
    } catch {
        return;
    }
    await Promise.all(profiles.map(async (p) => {
        const stored = getStoredToken(p.id);
        if (stored) {
            try { await restoreTokenToBackend(p.id, stored); } catch { /* ignore per-profile failures */ }
        }
    }));
}

/* ----------- Global event handlers ----------- */

document.addEventListener('click', (e) => {
    // Profile switcher toggle / close-on-outside
    const switcherBtn = document.getElementById('profile-switcher');
    const switcherMenu = document.getElementById('switcher-menu');
    if (switcherBtn && switcherMenu) {
        if (switcherBtn.contains(e.target)) {
            if (switcherMenu.classList.contains('hidden')) openProfileSwitcher();
            else closeProfileSwitcher();
        } else if (!switcherMenu.contains(e.target)) {
            closeProfileSwitcher();
        }
    }

    // Close modals on overlay click (not panel click)
    const tokenModal = document.getElementById('token-modal');
    if (tokenModal && e.target === tokenModal) {
        closeTokenModal();
    }
});

document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        closeProfileSwitcher();
        closeTokenModal();
    }
});

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => (
        { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
    ));
}

/* ----------- Wiring ----------- */

window.addEventListener('hashchange', render);
window.addEventListener('profile-changed', () => {
    refreshActiveProfile();
    refreshTokenStatus();
    // A new profile has its own credentials — kill the old refresh cycle
    // and start a new one if the new profile can auto-fetch.
    initAutoRefreshFromProfile();
});
window.addEventListener('token-changed', refreshTokenStatus);
window.addEventListener('token-expired', handleTokenExpired);
window.addEventListener('open-token-modal', openTokenModal);
window.addEventListener('load', async () => {
    // Wire modal buttons
    document.getElementById('token-status').addEventListener('click', openTokenModal);
    document.getElementById('btn-save-token').addEventListener('click', saveToken);
    document.getElementById('btn-clear-token').addEventListener('click', clearToken);
    document.getElementById('btn-cancel-token').addEventListener('click', closeTokenModal);
    document.getElementById('btn-fetch-token').addEventListener('click', autoFetchToken);

    document.getElementById('theme-toggle').addEventListener('click', toggleTheme);

    render();
    checkBackend();
    // Restore tokens FIRST so the status checks that follow reflect reality
    await restoreTokensFromStorage();
    refreshActiveProfile();
    refreshTokenStatus();
    // If credentials are configured, kick off silent auto-refresh so the token stays fresh
    // without any user action. Not awaited — page render shouldn't wait on FICO's token endpoint.
    initAutoRefreshFromProfile();
});
