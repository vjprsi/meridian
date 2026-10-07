"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { identity, unwrap, type OauthScope } from "@/api/client";
import { useCatalog } from "@/lib/spec";
import { Input, cx } from "./ui";

export interface ScopeOption { scope: string; domain: string; description: string; sensitive?: boolean; consent?: boolean }

/**
 * The scope catalogue: identity-service's OAuth scope list (GET /v1/oauth-scopes) when reachable, otherwise the
 * domain:read / domain:write scopes declared in the synced contracts.
 */
export function useScopeCatalogue() {
  const catalog = useCatalog();
  const live = useQuery({
    queryKey: ["oauth-scopes"],
    queryFn: async () => {
      const all: OauthScope[] = [];
      let cursor: string | undefined;
      for (let i = 0; i < 10; i++) {
        const page = unwrap(await identity.GET("/v1/oauth-scopes", { params: { query: { limit: 200, cursor } } }));
        all.push(...page.data);
        if (!page.has_more || !page.next_cursor) break;
        cursor = page.next_cursor;
      }
      return all;
    },
    retry: false,
    staleTime: 300_000,
  });
  const options = useMemo<ScopeOption[]>(() => {
    if (live.data && live.data.length > 0) {
      return live.data.map((s) => ({ scope: s.scope, domain: s.domain, description: s.description,
        sensitive: s.is_sensitive, consent: s.requires_customer_consent }));
    }
    return (catalog.data?.scopes ?? []).map((s) => ({ scope: s.scope, domain: s.domain, description: s.description }));
  }, [live.data, catalog.data]);
  return { options, source: live.data && live.data.length > 0 ? "identity-service" : "contracts", isLoading: catalog.isLoading };
}

/** Grid of domains with read / write checkboxes (plus any non-standard scopes from the live catalogue). */
export function ScopePicker({ value, onChange, allowed }: { value: string[]; onChange: (v: string[]) => void; allowed?: string[] }) {
  const { options, source } = useScopeCatalogue();
  const [filter, setFilter] = useState("");
  const opts = allowed ? options.filter((o) => allowed.includes(o.scope)) : options;
  const byDomain = new Map<string, ScopeOption[]>();
  for (const o of opts) {
    if (filter && !`${o.scope} ${o.description}`.toLowerCase().includes(filter.toLowerCase())) continue;
    byDomain.set(o.domain, [...(byDomain.get(o.domain) ?? []), o]);
  }
  const toggle = (s: string) => onChange(value.includes(s) ? value.filter((x) => x !== s) : [...value, s]);
  return (
    <div className="rounded-[var(--radius)] border border-border">
      <div className="flex items-center gap-2 border-b border-border p-2">
        <Input placeholder="Filter scopes…" value={filter} onChange={(e) => setFilter(e.target.value)} className="flex-1 py-1" />
        <span className="text-[11px] text-muted">{value.length} selected · from {source}</span>
      </div>
      <div className="max-h-64 overflow-y-auto">
        {[...byDomain.entries()].map(([domain, list]) => (
          <div key={domain} className="flex flex-wrap items-center gap-x-4 gap-y-1 border-b border-border px-3 py-1.5 last:border-0">
            <span className="w-28 text-xs font-medium">{domain}</span>
            {list.map((o) => (
              <label key={o.scope} title={o.description} className={cx("flex items-center gap-1.5 text-xs", o.sensitive && "text-warn")}>
                <input type="checkbox" checked={value.includes(o.scope)} onChange={() => toggle(o.scope)} />
                <span className="font-mono">{o.scope.split(":").slice(1).join(":") || o.scope}</span>
                {o.sensitive && <span className="text-[10px]">(sensitive)</span>}
              </label>
            ))}
          </div>
        ))}
        {byDomain.size === 0 && <p className="p-3 text-xs text-muted">No scopes.</p>}
      </div>
    </div>
  );
}
