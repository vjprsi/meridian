"use client";

import { useParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deposits, download, idem, statements, unwrap } from "@/api/client";
import { useAccountBalance, useMyAccount } from "@/api/me";
import type { Statement } from "@/api/types";
import { accountName } from "@/components/banking";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Select, Summary,
  cx, masked,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

function isoDate(d: Date) {
  return d.toISOString().slice(0, 10);
}

export default function AccountPage() {
  const t = useT();
  const { id } = useParams<{ id: string }>();
  const { account, isPending, error } = useMyAccount(id);
  const bal = useAccountBalance(account?.id);
  const [range, setRange] = useState<{ from: string; to: string }>({ from: "", to: "" });
  const [draft, setDraft] = useState(range);
  const [dlError, setDlError] = useState<unknown>(null);

  const txns = useInfiniteQuery({
    queryKey: ["transactions", id, range], enabled: !!account,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => unwrap(await deposits.GET("/v1/deposit-accounts/{id}/transactions", {
      params: { path: { id }, query: { limit: 25, cursor: pageParam,
        from: range.from ? `${range.from}T00:00:00Z` : undefined,
        to: range.to ? `${range.to}T23:59:59Z` : undefined } } })),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor ?? undefined : undefined),
  });

  if (isPending) return <Loading rows={5} />;
  if (error || !account) return <ErrorBanner error={error} />;

  function applyRange(e: FormEvent) {
    e.preventDefault();
    setRange(draft);
  }

  const rows = txns.data?.pages.flatMap((p) => p.data) ?? [];

  return (
    <>
      <PageHeader title={accountName(account)} back={{ href: "/", label: t("nav.home") }}
        subtitle={<>{masked(account.iban ?? account.account_number)} · {account.currency}</>}
        actions={<Badge value={account.status} />} />

      <Card className="mb-5">
        {bal.error ? <ErrorBanner error={bal.error} /> : (
          <Summary items={[
            { label: t("account.available"), value: <Money amount={bal.data?.available_balance} currency={account.currency} className="text-xl" /> },
            { label: t("account.ledger"), value: <Money amount={bal.data?.posted_balance} currency={account.currency} /> },
            { label: t("account.pending"), value: <><Money amount={bal.data?.pending_debits} currency={account.currency} /> {t("account.out")}</> },
            { label: t("account.number"), value: <span className="font-mono">{account.account_number}</span> },
            ...(account.iban ? [{ label: "IBAN", value: <span className="font-mono">{account.iban}</span> }] : []),
            { label: t("account.opened"), value: <DateTime value={account.opened_at} /> },
          ]} />
        )}
        <div className="mt-4 flex flex-wrap gap-2">
          <Button variant="secondary" onClick={async () => {
            setDlError(null);
            try {
              await download(`/v1/deposit-accounts/${account.id}/confirmation-letter`, `confirmation-${account.account_number}.txt`);
            } catch (e) { setDlError(e); }
          }}>{t("account.confirmationLetter")}</Button>
        </div>
        <ErrorBanner error={dlError} className="mt-3" />
      </Card>

      <Card title={t("account.transactions")} className="mb-5">
        <form onSubmit={applyRange} className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
          <Field label={t("common.from")}><Input type="date" value={draft.from} max={draft.to || undefined}
            onChange={(e) => setDraft({ ...draft, from: e.target.value })} /></Field>
          <Field label={t("common.to")}><Input type="date" value={draft.to} min={draft.from || undefined}
            onChange={(e) => setDraft({ ...draft, to: e.target.value })} /></Field>
          <Button type="submit" variant="secondary" className="col-span-2 sm:col-span-1">{t("common.apply")}</Button>
        </form>
        {txns.isPending ? <Loading /> : txns.error ? <ErrorBanner error={txns.error} /> : rows.length === 0 ? (
          <Empty title={t("account.noTransactions")} />
        ) : (
          <>
            <ul className="divide-y divide-border">
              {rows.map((e) => {
                const credit = e.direction === "CREDIT";
                return (
                  <li key={e.id} className="flex items-center justify-between gap-3 py-3">
                    <span className="min-w-0">
                      <span className="block truncate font-medium">{e.description || e.reference || t(credit ? "activity.in" : "activity.out")}</span>
                      <span className="block text-xs text-muted"><DateTime value={e.effective_at} /></span>
                    </span>
                    <span className="text-right">
                      <Money amount={credit ? e.amount : `-${e.amount}`} currency={e.currency} signed
                        className={cx("block font-semibold", credit && "text-good")} />
                      {e.balance_after && <span className="block text-xs text-muted">
                        {t("account.balanceAfter")} <Money amount={e.balance_after} currency={e.currency} /></span>}
                    </span>
                  </li>
                );
              })}
            </ul>
            {txns.hasNextPage && (
              <Button variant="secondary" className="mt-3 w-full" disabled={txns.isFetchingNextPage} onClick={() => txns.fetchNextPage()}>
                {txns.isFetchingNextPage ? t("common.loading") : t("common.loadMore")}
              </Button>
            )}
          </>
        )}
      </Card>

      <Statements accountId={account.id!} currency={account.currency} accountNumber={account.account_number ?? ""} />
    </>
  );
}

function Statements({ accountId, currency, accountNumber }: { accountId: string; currency: string; accountNumber: string }) {
  const t = useT();
  const qc = useQueryClient();
  const today = new Date();
  const monthStart = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - 1, 1));
  const monthEnd = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 0));
  const [form, setForm] = useState({ period_start: isoDate(monthStart), period_end: isoDate(monthEnd), format: "PDF" as Statement["format"] });
  const [dlError, setDlError] = useState<unknown>(null);

  const list = useQuery({
    queryKey: ["statements", accountId],
    queryFn: async () => unwrap(await statements.GET("/v1/statements", { params: { query: { deposit_account_id: accountId, limit: 24 } } }))
      .data.filter((s) => s.deposit_account_id === accountId),
    refetchInterval: (q) => (q.state.data?.some((s) => s.status === "PENDING" || s.status === "GENERATING") ? 4000 : false),
  });

  const create = useMutation({
    mutationFn: async () => unwrap(await statements.POST("/v1/statements", {
      params: { header: idem() },
      body: { deposit_account_id: accountId, currency, is_on_demand: true, ...form },
    })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["statements", accountId] }),
  });

  async function get(s: Statement) {
    setDlError(null);
    try {
      const link = unwrap(await statements.GET("/v1/statements/{id}/download-link", { params: { path: { id: s.id! } } })) as { url?: string };
      if (!link.url) throw new Error("No download link");
      await download(link.url, `statement-${accountNumber}-${s.period_start}_${s.period_end}.${s.format.toLowerCase()}`);
    } catch (e) {
      setDlError(e);
    }
  }

  return (
    <Card title={t("statements.title")}>
      <form onSubmit={(e) => { e.preventDefault(); create.mutate(); }}
        className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end">
        <Field label={t("common.from")}><Input type="date" required value={form.period_start} max={form.period_end}
          onChange={(e) => setForm({ ...form, period_start: e.target.value })} /></Field>
        <Field label={t("common.to")}><Input type="date" required value={form.period_end} min={form.period_start}
          onChange={(e) => setForm({ ...form, period_end: e.target.value })} /></Field>
        <Field label={t("statements.format")}>
          <Select value={form.format} onChange={(e) => setForm({ ...form, format: e.target.value as Statement["format"] })}
            options={[{ value: "PDF", label: "PDF" }, { value: "CSV", label: "CSV" }]} />
        </Field>
        <Button type="submit" disabled={create.isPending}>{create.isPending ? t("common.working") : t("statements.request")}</Button>
      </form>
      <ErrorBanner error={create.error} className="mb-3" />
      {create.isSuccess && <div className="mb-3"><Notice tone="good">{t("statements.requested")}</Notice></div>}
      <ErrorBanner error={dlError} className="mb-3" />
      <Async q={list} empty={<Empty title={t("statements.none")} />}>
        {(rows) => (
          <ul className="divide-y divide-border">
            {rows.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span>
                  <span className="block font-medium"><DateTime value={s.period_start} dateOnly /> – <DateTime value={s.period_end} dateOnly /></span>
                  <span className="block text-xs text-muted">{s.format} · <Badge value={s.status} /></span>
                </span>
                <Button variant="secondary" disabled={s.status !== "READY" && s.status !== "DELIVERED"} onClick={() => get(s)}
                  aria-label={`${t("common.download")} ${s.period_start} – ${s.period_end}`}>
                  {t("common.download")}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Async>
    </Card>
  );
}
