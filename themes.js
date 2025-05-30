console.log('themes.js loaded');

const themeVarsList = document.getElementById('theme-vars-list');
const saveThemeBtn = document.getElementById('save-theme-btn');
const themeNameInput = document.getElementById('theme-name');
const themesList = document.getElementById('themes-list');

const defaultThemeColors = {
    '--bg-color': '#0a0e1a',
    '--card-bg': 'rgba(20, 25, 40, 0.7)',
    '--text-color': '#e0e6ff',
    '--header-bg': 'rgba(30, 35, 50, 0.8)',
    '--transaction-bg': 'rgba(25, 30, 45, 0.8)',
    '--primary-color': '#3b82f6',
    '--accent-color': '#7c3aed',
    '--muted-success': '#4a7f60',
    '--muted-error': '#8b4a4a',
    '--success-color': '#4ade80',
    '--error-color': '#f87171'
};

const lightThemeColors = {
    '--bg-color': '#f0f2f5',
    '--card-bg': 'rgba(255, 255, 255, 0.9)',
    '--text-color': '#1a1a1a',
    '--header-bg': 'rgba(245, 245, 245, 0.9)',
    '--transaction-bg': 'rgba(240, 240, 240, 0.9)',
    '--primary-color': '#3b82f6',
    '--accent-color': '#7c3aed',
    '--muted-success': '#4a7f60',
    '--muted-error': '#8b4a4a',
    '--success-color': '#4ade80',
    '--error-color': '#f87171'
};

function initializeThemeVars() {
    if (!themeVarsList) return;
    themeVarsList.innerHTML = '';
    const baseColors = darkMode ? defaultThemeColors : lightThemeColors;
    Object.keys(baseColors).forEach(varName => {
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'input';
        input.placeholder = varName;
        input.value = baseColors[varName];
        input.dataset.var = varName;
        themeVarsList.appendChild(input);
    });
}

function saveTheme() {
    if (!themeNameInput || !themeVarsList) return;
    const themeName = themeNameInput.value.trim();
    if (!themeName) {
        alert('Please enter a theme name');
        return;
    }

    const theme = {};
    themeVarsList.querySelectorAll('input').forEach(input => {
        theme[input.dataset.var] = input.value;
    });

    let themes = JSON.parse(localStorage.getItem('customThemes') || '{}');
    themes[themeName] = theme;
    localStorage.setItem('customThemes', JSON.stringify(themes));
    updateThemesList();
    applyTheme(themeName);
}

function applyTheme(themeName) {
    const themes = JSON.parse(localStorage.getItem('customThemes') || '{}');
    const theme = themes[themeName];
    if (theme) {
        Object.entries(theme).forEach(([key, value]) => {
            document.documentElement.style.setProperty(key, value);
        });
    } else if (themeName === 'light') {
        Object.entries(lightThemeColors).forEach(([key, value]) => {
            document.documentElement.style.setProperty(key, value);
        });
    } else {
        Object.entries(defaultThemeColors).forEach(([key, value]) => {
            document.documentElement.style.setProperty(key, value);
        });
    }
    darkMode = themeName !== 'light';
    localStorage.setItem('darkMode', darkMode);
    document.body.classList.toggle('dark-mode', darkMode);
    document.body.classList.toggle('light-mode', !darkMode);
    if (elements.themeToggle) {
        elements.themeToggle.innerHTML = darkMode ? '<i class="material-icons-outlined">light_mode</i>' : '<i class="material-icons-outlined">dark_mode</i>';
    }
}

function updateThemesList() {
    if (!themesList) return;
    themesList.innerHTML = '';
    const themes = JSON.parse(localStorage.getItem('customThemes') || '{}');
    ['light', ...Object.keys(themes)].forEach(themeName => {
        const btn = document.createElement('button');
        btn.className = 'btn btn--primary';
        btn.textContent = themeName;
        btn.addEventListener('click', () => applyTheme(themeName));
        themesList.appendChild(btn);
    });
}

if (saveThemeBtn) {
    saveThemeBtn.addEventListener('click', () => {
        console.log('Save Theme clicked');
        saveTheme();
    });
}

let darkMode = localStorage.getItem('darkMode') === 'true';
initializeThemeVars();
updateThemesList();
applyTheme(darkMode ? 'dark' : 'light');