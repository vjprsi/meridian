"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { en, type MessageKey } from "@/messages/en";
import { sw } from "@/messages/sw";

export type Locale = "en" | "sw";
export const LOCALES: { code: Locale; label: string }[] = [
  { code: "en", label: "English" },
  { code: "sw", label: "Kiswahili" },
];
const CATALOGS: Record<Locale, Record<MessageKey, string>> = { en, sw };
const KEY = "meridian.locale";

export type Translate = (key: MessageKey, vars?: Record<string, string | number>) => string;

interface I18n {
  locale: Locale;
  /** BCP 47 tag for Intl formatting (numbers, currency, dates). */
  intl: string;
  setLocale: (l: Locale) => void;
  t: Translate;
}

function format(template: string, vars?: Record<string, string | number>) {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

const I18nContext = createContext<I18n>({
  locale: "en",
  intl: "en-KE",
  setLocale: () => {},
  t: (k, v) => format(en[k], v),
});

export function useI18n() {
  return useContext(I18nContext);
}

export function useT(): Translate {
  return useContext(I18nContext).t;
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>("en");
  useEffect(() => {
    let l: Locale = "en";
    try {
      const stored = localStorage.getItem(KEY);
      if (stored === "en" || stored === "sw") l = stored;
      else if (navigator.language.toLowerCase().startsWith("sw")) l = "sw";
    } catch { /* storage blocked: default */ }
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLocaleState(l);
  }, []);
  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);
  const setLocale = useCallback((l: Locale) => {
    setLocaleState(l);
    try { localStorage.setItem(KEY, l); } catch { /* ignore */ }
  }, []);
  const value = useMemo<I18n>(() => ({
    locale,
    intl: locale === "sw" ? "sw-KE" : "en-KE",
    setLocale,
    // Unknown keys (e.g. a step type added server-side later) render as "" so callers can fall back.
    t: (k, v) => format(CATALOGS[locale][k] ?? en[k] ?? "", v),
  }), [locale, setLocale]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
