"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, Select } from "@/components/ui";
import type { JournalEntry, Ledger, LedgerAccount, Page } from "@/lib/types";

interface Line { ledger_account_id: string; direction: "DEBIT" | "CREDIT"; amount: string; description: string }

/** Exact decimal sum in minor units (8 dp) without floating point. */
function toUnits(v: string): bigint {
  const m = /^(\d+)(?:\.(\d{0,8}))?$/.exec(v.trim());
  if (!m) return 0n;
  return BigInt(m[1]) * 100_000_000n + BigInt((m[2] ?? "").padEnd(8, "0"));
}

function PostEntry() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [ledgerId, setLedgerId] = useState(params.get("ledger") ?? "");
  const [description, setDescription] = useState("");
  const [lines, setLines] = useState<Line[]>([
    { ledger_account_id: "", direction: "DEBIT", amount: "", description: "" },
    { ledger_account_id: "", direction: "CREDIT", amount: "", description: "" },
  ]);
  const ledgers = useQuery({ queryKey: ["ledgers"], queryFn: () => call<Page<Ledger>>("GET", "/v1/ledgers?limit=100") });
  const accounts = useQuery({
    queryKey: ["accounts-min", ledgerId], enabled: !!ledgerId,
    queryFn: () => call<Page<LedgerAccount>>("GET", `/v1/ledger-accounts?ledger_id=${ledgerId}&limit=200`),
  });
  const byId = useMemo(() => new Map((accounts.data?.data ?? []).map((a) => [a.id, a])), [accounts.data]);

  const totals = useMemo(() => {
    const t = new Map<string, bigint>();
    for (const l of lines) {
      const ccy = byId.get(l.ledger_account_id)?.currency ?? "?";
      const v = toUnits(l.amount) * (l.direction === "DEBIT" ? 1n : -1n);
      t.set(ccy, (t.get(ccy) ?? 0n) + v);
    }
    return t;
  }, [lines, byId]);
  const balanced = lines.every((l) => l.ledger_account_id && toUnits(l.amount) > 0n) && [...totals.values()].every((v) => v === 0n);

  const post = useMutation({
    mutationFn: () => call<JournalEntry>("POST", "/v1/journal-entries", {
      ledger_id: ledgerId, description,
      entries: lines.map((l) => ({ ...l, currency: byId.get(l.ledger_account_id)?.currency, description: l.description || undefined })),
    }),
    onSuccess: async () => {
      // Balances changed: drop cached ledger views so every screen shows the new state.
      await Promise.all(["accounts", "balance", "entries", "account"].map((k) => qc.invalidateQueries({ queryKey: [k] })));
      router.push(`/ledger/${ledgerId}`);
    },
  });
  const set = (i: number, patch: Partial<Line>) => setLines(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  const accountOptions = ["", ...(accounts.data?.data ?? []).map((a) => a.id!)];

  return (
    <>
      <PageHeader title="Post journal entry" subtitle="A balanced set of debits and credits, posted atomically" />
      <ErrorBanner error={post.error ?? ledgers.error} />
      <Card>
        <div className="mb-5 grid grid-cols-1 gap-4 md:grid-cols-2">
          <Field label="Ledger">
            <select className="w-full rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm"
              value={ledgerId} onChange={(e) => setLedgerId(e.target.value)}>
              <option value="">Select ledger</option>
              {(ledgers.data?.data ?? []).map((l) => (
                <option key={l.id} value={l.id}>{l.code} · {l.name} ({l.base_currency})</option>
              ))}
            </select>
          </Field>
          <Field label="Description"><Input value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        </div>
        <div className="space-y-3">
          {lines.map((l, i) => (
            <div key={i} className="grid grid-cols-12 items-end gap-3">
              <div className="col-span-5">
                <Field label={i === 0 ? "Account" : ""}>
                  <select className="w-full rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm"
                    value={l.ledger_account_id} onChange={(e) => set(i, { ledger_account_id: e.target.value })}>
                    {accountOptions.map((id) => (
                      <option key={id} value={id}>{id ? `${byId.get(id)?.code} · ${byId.get(id)?.name} (${byId.get(id)?.currency})` : "Select account"}</option>
                    ))}
                  </select>
                </Field>
              </div>
              <div className="col-span-2">
                <Field label={i === 0 ? "Side" : ""}>
                  <Select options={["DEBIT", "CREDIT"]} value={l.direction} onChange={(e) => set(i, { direction: e.target.value as Line["direction"] })} />
                </Field>
              </div>
              <div className="col-span-2">
                <Field label={i === 0 ? "Amount" : ""}>
                  <Input inputMode="decimal" placeholder="0.00" className="num text-right" value={l.amount}
                    onChange={(e) => set(i, { amount: e.target.value })} />
                </Field>
              </div>
              <div className="col-span-2">
                <Field label={i === 0 ? "Line note" : ""}><Input value={l.description} onChange={(e) => set(i, { description: e.target.value })} /></Field>
              </div>
              <div className="col-span-1">
                <Button variant="ghost" disabled={lines.length <= 2} onClick={() => setLines(lines.filter((_, j) => j !== i))}
                  aria-label="Remove line">✕</Button>
              </div>
            </div>
          ))}
        </div>
        <div className="mt-4 flex flex-wrap items-center justify-between gap-4 border-t border-border pt-4">
          <Button variant="secondary" onClick={() => setLines([...lines, { ledger_account_id: "", direction: "CREDIT", amount: "", description: "" }])}>
            Add line
          </Button>
          <div className="flex items-center gap-4 text-sm">
            {[...totals.entries()].filter(([c]) => c !== "?").map(([ccy, v]) => (
              <span key={ccy} className={v === 0n ? "text-good" : "text-bad"}>
                {ccy} difference <span className="num">{(Number(v) / 1e8).toFixed(2)}</span>
              </span>
            ))}
            <Button disabled={!ledgerId || !balanced || post.isPending} onClick={() => post.mutate()}>Post entry</Button>
          </div>
        </div>
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><PostEntry /></Suspense>;
}
