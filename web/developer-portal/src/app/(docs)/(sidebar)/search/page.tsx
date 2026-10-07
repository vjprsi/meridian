"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { MethodBadge } from "@/components/code";
import { ErrorBanner, Input, Loading, PageHeader, cx } from "@/components/ui";
import { buildIndex, hrefFor, searchIndex } from "@/lib/search";
import { useSearchIndex } from "@/lib/spec";

function Search() {
  const params = useSearchParams();
  const router = useRouter();
  const q = params.get("q") ?? "";
  const [draft, setDraft] = useState(q);
  const [kind, setKind] = useState<"all" | "op" | "ev">("all");
  const idx = useSearchIndex();
  const index = useMemo(() => (idx.data ? buildIndex(idx.data) : []), [idx.data]);
  const hits = useMemo(() => searchIndex(index, q, 500), [index, q]);
  const shown = hits.filter((h) => kind === "all" || h.k === kind);

  return (
    <>
      <PageHeader title="Search" subtitle={`${idx.data?.length.toLocaleString() ?? "…"} operations and events indexed in your browser`} />
      <form className="mb-4 flex max-w-2xl gap-2" onSubmit={(e) => { e.preventDefault(); router.replace(`/search?q=${encodeURIComponent(draft.trim())}`); }}>
        <Input autoFocus value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="e.g. payment refund, /v1/cards, ledger:write, deposit_account.opened"
          className="flex-1" aria-label="Search query" />
      </form>
      <div className="mb-4 flex gap-1 text-sm">
        {([["all", "All"], ["op", "Operations"], ["ev", "Events"]] as const).map(([k, l]) => (
          <button key={k} type="button" onClick={() => setKind(k)}
            className={cx("rounded-full px-3 py-1", kind === k ? "bg-accent-soft font-medium text-accent" : "text-muted hover:bg-surface-2")}>
            {l} <span className="num text-xs">({k === "all" ? hits.length : hits.filter((h) => h.k === k).length})</span>
          </button>
        ))}
      </div>
      <ErrorBanner error={idx.error} />
      {idx.isLoading ? <Loading /> : !q ? <p className="text-sm text-muted">Type a query: words match operation ids, paths, summaries, resources, scopes and event types.</p> : (
        <ul className="rounded-[var(--radius)] border border-border bg-surface">
          {shown.length === 0 && <li className="p-6 text-center text-sm text-muted">No results for “{q}”.</li>}
          {shown.map((h) => (
            <li key={`${h.k}:${h.d}:${h.id}:${h.m}`} className="border-b border-border last:border-0">
              <Link href={hrefFor(h)} className="flex items-start gap-3 px-4 py-2.5 hover:bg-surface-2">
                <MethodBadge method={h.m} className="mt-0.5" />
                <div className="min-w-0 flex-1">
                  <p className="font-mono text-sm break-all">{h.k === "ev" ? h.id : h.p}</p>
                  <p className="text-xs text-muted">
                    {h.s} · <span className="font-mono">{h.k === "ev" ? h.p : h.id}</span>
                    {h.sc && <> · scope <span className="font-mono">{h.sc}</span></>}
                  </p>
                </div>
                <span className="shrink-0 text-right text-[11px] text-muted">{h.d}<br />{h.t}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense fallback={<Loading />}><Search /></Suspense>;
}
