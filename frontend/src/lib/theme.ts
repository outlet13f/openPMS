// Theme (S37 화면): "system" follows the operating system (tokens.css media query), light/dark pin it.
// The choice is also kept in this browser so the first paint already has it (before /auth/me answers).
import type { Theme } from "@/api";

const KEY = "openpms.theme";

export function applyTheme(theme: Theme) {
  const root = document.documentElement;
  if (theme === "system") delete root.dataset.theme;
  else root.dataset.theme = theme;
  try {
    localStorage.setItem(KEY, theme);
  } catch {
    /* storage unavailable: the server value still applies once loaded */
  }
}

/** Before the app renders: the theme this browser used last. */
export function applyStoredTheme() {
  try {
    const t = localStorage.getItem(KEY);
    if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
  } catch {
    /* ignore */
  }
}
