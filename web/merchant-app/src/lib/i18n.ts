"use client";

import { useCallback, useSyncExternalStore } from "react";
import { en, type MessageKey } from "@/messages/en";
import { sw } from "@/messages/sw";

/** English and Kiswahili; the choice is kept in localStorage (never anything else about the user). */
export type Lang = "en" | "sw";
export type { MessageKey };
export type T = (k: MessageKey, vars?: Record<string, string | number>) => string;

const dicts: Record<Lang, Record<MessageKey, string>> = { en, sw };
const KEY = "meridian.merchant.lang";
const listeners = new Set<() => void>();

function current(): Lang {
  if (typeof window === "undefined") return "en";
  try {
    const v = localStorage.getItem(KEY);
    if (v === "en" || v === "sw") return v;
  } catch { /* ignore */ }
  return navigator.language?.toLowerCase().startsWith("sw") ? "sw" : "en";
}

export function setLang(l: Lang) {
  try { localStorage.setItem(KEY, l); } catch { /* ignore */ }
  document.documentElement.lang = l;
  listeners.forEach((f) => f());
}

export function useLang(): Lang {
  return useSyncExternalStore((cb) => { listeners.add(cb); return () => listeners.delete(cb); }, current, () => "en");
}

export function translate(lang: Lang, k: MessageKey, vars?: Record<string, string | number>): string {
  let s = dicts[lang][k] ?? en[k];
  if (vars) for (const [name, v] of Object.entries(vars)) s = s.replaceAll(`{${name}}`, String(v));
  return s;
}

export function useT(): T {
  const lang = useLang();
  return useCallback((k, vars) => translate(lang, k, vars), [lang]);
}

/** Has a message for this key (problem codes are looked up dynamically). */
export function hasKey(k: string): k is MessageKey {
  return k in en;
}

/** Locale for number and date formatting. */
export function localeOf(l: Lang): string {
  return l === "sw" ? "sw-KE" : "en-KE";
}
