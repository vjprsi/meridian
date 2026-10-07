"use client";

import Link from "next/link";
import { useCatalog } from "@/lib/spec";

/** Scope catalogue derived from the contracts' oauth2 security schemes (one read + one write scope per domain). */
export function ScopeTable() {
  const catalog = useCatalog();
  if (!catalog.data) return <p className="text-sm text-muted">Loading scope catalogue…</p>;
  return (
    <table>
      <thead><tr><th>Scope</th><th>Grants</th><th>Domain</th></tr></thead>
      <tbody>
        {catalog.data.scopes.map((s) => (
          <tr key={s.scope}>
            <td><code>{s.scope}</code></td>
            <td>{s.description}</td>
            <td><Link href={`/reference/${s.domain}`}>{s.domain}</Link></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
