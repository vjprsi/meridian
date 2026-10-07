"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { dayEnd, dayStart, useStores } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { SaleStatus } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useStoreName } from "@/components/sales";
import { Async, Badge, Card, DateTime, Empty, Field, Input, Money, PageHeader, Select, cx, focusRing } from "@/components/ui";

const STATUSES: SaleStatus[] = ["PENDING", "PAID", "EXPIRED", "CANCELLED"];

/** Sales history of the stores the user may work in (the server filters by store scope). */
export default function SalesPage() {
  const t = useT();
  const { stores, selectedId } = useStores();
  const storeName = useStoreName();
  const [store, setStore] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const storeId = store ?? selectedId;
  const sales = useQuery({
    queryKey: ["sales", storeId, status, from, to],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/sales", { params: { query: {
      store_id: storeId || undefined, status: (status || undefined) as SaleStatus | undefined,
      from: dayStart(from), to: dayEnd(to), limit: 100 } } })).data,
  });
  return (
    <>
      <PageHeader title={t("navSales")} />
      <Card className="mb-4">
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <Field label={t("store")}>
            <Select value={storeId} onChange={(e) => setStore(e.target.value)} placeholder={t("all")}
              options={stores.map((s) => ({ value: s.id, label: s.name }))} />
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
        <Async q={sales} empty={<div className="p-4"><Empty title={t("noSales")} /></div>}>
          {(rows) => (
            <ul className="divide-y divide-border" data-testid="sales-list">
              {rows.map((s) => (
                <li key={s.id}>
                  <Link href={`/sales/${s.id}`} className={cx("flex min-h-14 items-center justify-between gap-3 px-4 py-2 hover:bg-surface-2", focusRing)}>
                    <span className="min-w-0">
                      <Money amount={s.amount} currency={s.currency} className="font-semibold" />
                      <span className="block truncate text-xs text-muted">
                        <DateTime value={s.created_at} /> · {storeName(s.merchant_store_id)}{s.reference ? ` · ${s.reference}` : ""}
                      </span>
                    </span>
                    <Badge value={s.status} />
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
