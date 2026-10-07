"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { dayEnd, dayStart, today, useStores } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { WalletTransaction } from "@/api/types";
import { useT } from "@/lib/i18n";
import { Async, Badge, Card, DateTime, Empty, Field, Input, Money, PageHeader, Select, cx, focusRing } from "@/components/ui";

type TxnType = WalletTransaction["txn_type"];
type TxnStatus = WalletTransaction["status"];
const TYPES: TxnType[] = ["MERCHANT_PAYMENT", "REFUND", "FEE", "WALLET_TO_BANK", "REVERSAL"];
const STATUSES: TxnStatus[] = ["COMPLETED", "PROCESSING", "FAILED", "REVERSED"];

/** Payments received and other wallet transactions of the merchant in the user's stores. */
export default function TransactionsPage() {
  const t = useT();
  const { stores, selectedId } = useStores();
  const [store, setStore] = useState<string | null>(null);
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState(today(-7));
  const [to, setTo] = useState(today());
  const storeId = store ?? selectedId;
  const txns = useQuery({
    queryKey: ["transactions", storeId, type, status, from, to],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/transactions", { params: { query: {
      store_id: storeId || undefined, txn_type: (type || undefined) as TxnType | undefined,
      status: (status || undefined) as TxnStatus | undefined, from: dayStart(from), to: dayEnd(to), limit: 100 } } })).data,
  });
  return (
    <>
      <PageHeader title={t("transactions")} />
      <Card className="mb-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-5">
          <Field label={t("store")}>
            <Select value={storeId} onChange={(e) => setStore(e.target.value)} placeholder={t("all")}
              options={stores.map((s) => ({ value: s.id, label: s.name }))} />
          </Field>
          <Field label={t("type")}>
            <Select value={type} onChange={(e) => setType(e.target.value)} placeholder={t("all")}
              options={TYPES.map((s) => ({ value: s, label: s }))} />
          </Field>
          <Field label={t("status")}>
            <Select value={status} onChange={(e) => setStatus(e.target.value)} placeholder={t("all")}
              options={STATUSES.map((s) => ({ value: s, label: s }))} />
          </Field>
          <Field label={t("from")}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={t("to")}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </div>
      </Card>
      <Card flush>
        <Async q={txns} empty={<div className="p-4"><Empty title={t("noTransactions")} /></div>}>
          {(rows) => (
            <ul className="divide-y divide-border">
              {rows.map((x) => (
                <li key={x.id}>
                  <Link href={`/transactions/${x.id}`} className={cx("flex min-h-14 items-center justify-between gap-3 px-4 py-2 hover:bg-surface-2", focusRing)}>
                    <span className="min-w-0">
                      <Money amount={x.amount} currency={x.currency} className="font-semibold" />
                      <span className="block truncate text-xs text-muted">
                        <DateTime value={x.created_at} /> · {x.txn_type.replaceAll("_", " ").toLowerCase()} · {x.reference}
                      </span>
                    </span>
                    <Badge value={x.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Async>
      </Card>
    </>
  );
}
