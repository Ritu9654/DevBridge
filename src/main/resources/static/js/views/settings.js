import { getTheme, applyTheme } from '../theme.js';

function radioMarkup(current) {
    return `
        <label class="settings-radio ${current === 'light' ? 'checked' : ''}" data-theme="light">
            <input type="radio" name="theme" value="light" ${current === 'light' ? 'checked' : ''}>
            <div class="settings-radio-content">
                <span class="settings-radio-title">Light</span>
                <span class="settings-radio-desc">Default. High contrast on bright displays.</span>
            </div>
        </label>
        <label class="settings-radio ${current === 'dark' ? 'checked' : ''}" data-theme="dark">
            <input type="radio" name="theme" value="dark" ${current === 'dark' ? 'checked' : ''}>
            <div class="settings-radio-content">
                <span class="settings-radio-title">Dark</span>
                <span class="settings-radio-desc">Easier on the eyes in low light.</span>
            </div>
        </label>
    `;
}

function syncRadios(theme) {
    const container = document.getElementById('settings-theme-choice');
    if (!container) return;
    container.querySelectorAll('.settings-radio').forEach(el => {
        const on = el.dataset.theme === theme;
        el.classList.toggle('checked', on);
        const input = el.querySelector('input[type="radio"]');
        if (input) input.checked = on;
    });
}

export const settingsView = {
    title: 'Settings',
    render() {
        return `
            <h2>Settings</h2>
            <p class="settings-intro">Per-browser preferences. Persist across sessions on this device.</p>

            <section class="card settings-section">
                <h3>Appearance</h3>
                <p class="settings-section-desc">Theme applies instantly to the whole app.</p>
                <div class="settings-choice" id="settings-theme-choice">
                    ${radioMarkup(getTheme())}
                </div>
            </section>
        `;
    },
    mount() {
        const container = document.getElementById('settings-theme-choice');
        if (!container) return;
        container.querySelectorAll('input[name="theme"]').forEach(input => {
            input.addEventListener('change', () => {
                applyTheme(input.value);
            });
        });
        const handler = (e) => syncRadios(e.detail && e.detail.theme);
        window.addEventListener('theme-changed', handler);
        // Detach on next route change so we don't accumulate listeners.
        window.addEventListener('hashchange', function once() {
            window.removeEventListener('theme-changed', handler);
            window.removeEventListener('hashchange', once);
        });
    },
};
