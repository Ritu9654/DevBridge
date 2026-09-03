const THEME_KEY = 'devbridge.theme';

export function getTheme() {
    try {
        return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light';
    } catch {
        return 'light';
    }
}

export function applyTheme(theme) {
    const t = theme === 'dark' ? 'dark' : 'light';
    if (t === 'dark') {
        document.documentElement.dataset.theme = 'dark';
    } else {
        delete document.documentElement.dataset.theme;
    }
    try { localStorage.setItem(THEME_KEY, t); } catch { /* ignore */ }
    window.dispatchEvent(new CustomEvent('theme-changed', { detail: { theme: t } }));
}

export function toggleTheme() {
    applyTheme(getTheme() === 'dark' ? 'light' : 'dark');
}
