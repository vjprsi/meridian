import type { SearchEntry } from "./spec";

/* Client-side full-text search over operations and events (index built by scripts/sync-specs.mjs). */

export interface Indexed { e: SearchEntry; hay: string }

export function buildIndex(entries: SearchEntry[]): Indexed[] {
  return entries.map((e) => ({
    e,
    hay: [e.id, e.m, e.p, e.s, e.t, e.d, e.sc, e.sv].join(" ").toLowerCase()
      // split camelCase operationIds and dotted event names so "deposit account" finds depositAccountsCreate
      + " " + e.id.replace(/([a-z])([A-Z])/g, "$1 $2").replace(/[._]/g, " ").toLowerCase(),
  }));
}

/** Every query token must occur; matches in the id, path or summary rank higher than tag/domain matches. */
export function searchIndex(index: Indexed[], query: string, limit = 50): SearchEntry[] {
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!tokens.length) return [];
  const scored: { e: SearchEntry; score: number }[] = [];
  for (const { e, hay } of index) {
    if (!tokens.every((t) => hay.includes(t))) continue;
    let score = 0;
    const id = e.id.toLowerCase();
    const path = e.p.toLowerCase();
    const summary = e.s.toLowerCase();
    for (const t of tokens) {
      if (id === t) score += 50;
      if (id.startsWith(t)) score += 10;
      if (id.includes(t)) score += 6;
      if (path.includes(t)) score += 5;
      if (summary.includes(t)) score += 4;
      if (e.t.toLowerCase().includes(t)) score += 2;
    }
    if (e.m === "GET" || e.m === "POST") score += 0.5;
    score -= path.length / 200;
    scored.push({ e, score });
  }
  return scored.sort((a, b) => b.score - a.score).slice(0, limit).map((s) => s.e);
}

export function hrefFor(e: SearchEntry): string {
  if (e.k === "ev") return `/events?channel=${encodeURIComponent(e.d)}#${encodeURIComponent(e.id)}`;
  return `/reference/${e.d}?tag=${encodeURIComponent(e.t)}#${e.id}`;
}
