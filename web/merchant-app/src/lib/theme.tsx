"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

export type ThemeChoice = "system" | "light" | "dark";
const KEY = "meridian.merchant.theme";

/**
 * Runs before first paint (inlined in the root layout) so a stored light/dark choice never flashes the other theme.
 * "system" leaves data-theme unset and the prefers-color-scheme media query decides.
 */
export const THEME_BOOT_SCRIPT = `try{var t=localStorage.getItem("${KEY}");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

const ThemeContext = createContext<{ theme: ThemeChoice; setTheme: (t: ThemeChoice) => void }>({
  theme: "system",
  setTheme: () => {},
});

export function useTheme() {
  return useContext(ThemeContext);
}

function apply(t: ThemeChoice) {
  const el = document.documentElement;
  if (t === "system") delete el.dataset.theme;
  else el.dataset.theme = t;
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeChoice>("system");
  useEffect(() => {
    let stored: ThemeChoice = "system";
    try {
      const v = localStorage.getItem(KEY);
      if (v === "light" || v === "dark") stored = v;
    } catch { /* storage blocked: follow the system */ }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setThemeState(stored);
  }, []);
  const setTheme = useCallback((t: ThemeChoice) => {
    setThemeState(t);
    apply(t);
    try {
      if (t === "system") localStorage.removeItem(KEY);
      else localStorage.setItem(KEY, t);
    } catch { /* ignore */ }
  }, []);
  return <ThemeContext.Provider value={{ theme, setTheme }}>{children}</ThemeContext.Provider>;
}
