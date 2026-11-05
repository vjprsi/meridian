"use client";

import type { ReactNode } from "react";
import { Badge, Card, DateTime, Money } from "@/components/ui";

/**
 * Grouped read-only detail view. Renders an entity's full field set in labelled sections
 * (not a hand-picked subset) so operators see the complete record. Each row is a
 * {@link Row}; empty values show an em-dash so the field's existence is still visible.
 */
export type RowKind = "text" | "date" | "datetime" | "bool" | "money" | "badge" | "pct" | "mono";

export interface Row {
  label: string;
  value: unknown;
  kind?: RowKind;
  currency?: string | null;     // for kind "money"
}

export interface DetailSection {
  title: string;
  rows: Row[];
  /** Drop the whole section when every row is empty (useful for business-only blocks). */
  hideWhenEmpty?: boolean;
}

function isEmpty(v: unknown) {
  return v === undefined || v === null || v === "" || (Array.isArray(v) && v.length === 0);
}

function render(r: Row): ReactNode {
  if (isEmpty(r.value) && r.kind !== "bool") return <span className="text-muted">—</span>;
  switch (r.kind) {
    case "date": return <DateTime value={String(r.value)} />;
    case "datetime": return <DateTime value={String(r.value)} />;
    case "bool": return <Badge value={r.value ? "YES" : "NO"} tone={r.value ? "good" : undefined} />;
    case "money": return <Money amount={r.value as string} currency={r.currency} />;
    case "badge": return <Badge value={String(r.value)} />;
    case "pct": return <span className="num">{String(r.value)}%</span>;
    case "mono": return <span className="font-mono text-xs">{String(r.value)}</span>;
    default: return <span>{String(r.value)}</span>;
  }
}

export function DetailSections({ sections, className }: { sections: DetailSection[]; className?: string }) {
  return (
    <div className={className ?? "grid grid-cols-1 gap-6 lg:grid-cols-2"}>
      {sections.map((s) => {
        if (s.hideWhenEmpty && s.rows.every((r) => isEmpty(r.value))) return null;
        return (
          <Card key={s.title} title={s.title}>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-sm">
              {s.rows.map((r) => (
                <div key={r.label} className="contents">
                  <dt className="text-muted">{r.label}</dt>
                  <dd className="text-right">{render(r)}</dd>
                </div>
              ))}
            </dl>
          </Card>
        );
      })}
    </div>
  );
}
