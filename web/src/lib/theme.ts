// Theme state lives on the root element as data-theme, not in React state: the whole palette swaps in
// CSS with zero component re-renders, and index.html applies it before first paint.

const KEY = "tally-theme";

export type Theme = "light" | "dark";

export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === "dark" ? "dark" : "light";
}

export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme;
  localStorage.setItem(KEY, theme);
}
