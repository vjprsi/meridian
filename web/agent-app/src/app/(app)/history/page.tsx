"use client";

import Link from "next/link";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, problemOf, unwrap, type WalletTransaction } from "@/api/client";
import { Badge, Button, DateTime, Money, Notice, PageTitle, ProblemBox, Select } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { useOutbox } from "@/lib/outbox/runtime";
import { useLive } from "@/lib/queries";

const TYPES = ["", "CASH_IN", "CASH_OUT", "BILL_PAYMENT", "AIRTIME", "FLOAT_TRANSFER", "COMMISSION_PAYOUT", "REVERSAL"] as const;

/** Transactions on the agent's float wallet (walletsTransactions), newest first, each with its receipt. */
export default function HistoryPage() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const ob = useOutbox();
  const [type, setType] = useState<(typeof TYPES)[number]>("");

  const q = useInfiniteQuery({
    queryKey: ["history", p?.floatWalletId, type],
    enabled: live && !!p?.floatWalletId,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => unwrap(await api.GET("/v1/agent/transactions", {
      params: { query: { limit: 25, cursor: pageParam, ...(type ? { txn_type: type } : {}) } } })),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor ?? undefined : undefined),
  });

  if (!p) return null;
  const rows: WalletTransaction[] = q.data?.pages.flatMap((pg) => pg.data) ?? [];
  const local = ob.views.filter((v) => v.status !== "SYNCED");

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("history")}</PageTitle>
      {local.length > 0 && (
        <Link href="/sync"><Notice tone="warn">{local.length} {t("outbox")} ({t("pending")} / {t("rejected")})</Notice></Link>
      )}
      <Select aria-label={t("type")} value={type} onChange={(e) => setType(e.target.value as typeof type)}
        options={TYPES.map((x) => ({ value: x, label: x ? x.replaceAll("_", " ") : t("all") }))} />
      {!live && <Notice tone="warn">{t("offline")}</Notice>}
      {q.error && <ProblemBox problem={problemOf(q.error)} />}
      <ul className="flex flex-col gap-2">
        {rows.map((x) => {
          const credit = x.payee_wallet_id === p.floatWalletId;
          const href = x.bill_payment_id ? `/receipt?kind=bill&id=${x.bill_payment_id}` : `/receipt?kind=wallet&id=${x.id}`;
          return (
            <li key={x.id}>
              <Link href={href} className="flex items-center justify-between gap-3 rounded-[var(--radius)] border border-border bg-surface p-3 hover:bg-surface-2">
                <span className="min-w-0">
                  <span className="block font-semibold">{x.txn_type.replaceAll("_", " ")}</span>
                  <span className="block truncate text-sm text-muted">{x.reference} · <DateTime value={x.created_at} /></span>
                  <span className="block truncate text-sm text-muted">{x.payee_name ?? x.payee_msisdn ?? x.payee_external_ref ?? ""}</span>
                </span>
                <span className="flex shrink-0 flex-col items-end gap-1">
                  <Money amount={credit ? x.amount : `-${x.amount}`} currency={x.currency} className="font-bold" />
                  <Badge value={x.status} />
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
      {rows.length === 0 && live && !q.isLoading && <p className="text-muted">{t("nothing")}</p>}
      {q.hasNextPage && <Button variant="secondary" block onClick={() => void q.fetchNextPage()}>{t("loadMore")}</Button>}
    </div>
  );
}
