(() => {
  "use strict";

  const STORAGE_KEY = "jiagu.theme";
  const root = document.documentElement;
  const toggle = document.querySelector(".theme-toggle");

  function applyTheme(theme) {
    const dark = theme !== "light";
    root.dataset.theme = dark ? "dark" : "light";
    document.querySelector('meta[name="color-scheme"]').content = dark ? "dark light" : "light dark";
    if (!toggle) return;
    toggle.textContent = dark ? "☀" : "☾";
    const label = dark ? "切换到浅色模式" : "切换到暗黑模式";
    toggle.title = label;
    toggle.setAttribute("aria-label", label);
    toggle.setAttribute("aria-pressed", String(dark));
  }

  applyTheme(root.dataset.theme);
  toggle?.addEventListener("click", () => {
    const theme = root.dataset.theme === "dark" ? "light" : "dark";
    try { localStorage.setItem(STORAGE_KEY, theme); } catch (_) { /* Storage may be disabled. */ }
    applyTheme(theme);
  });
})();
