"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { today } from "@/api/merchant";
import { api, download, unwrap } from "@/api/client";
import type { FeeStatement } from "@/api/types";
import { useT } from "@/lib/i18n";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Money, PageHeader, Summary, Table, Tabs,
} from "@/components/ui";

/** Settlements (with CSV reports) and the fee (MDR) statement. OWNER, MANAGER and VIEWER. */
export default function SettlementsPage() {
  const t = useT();
  const [tab, setTab] = useState<"settlements" | "fees">("settlements");
  return (
    <>
      <PageHeader title={t("settlements")} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "settlements", label: t("settlements") }, { key: "fees", label: t("feeStatement") }]} />
      {tab === "settlements" ? <SettlementList /> : <Fees />}
    </>
  );
}

function SettlementList() {
  const t = useT();
  const list = useQuery({
    queryKey: ["settlements"],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/settlements", { params: { query: { limit: 100 } } })).data,
  });
  return (
    <Async q={list} empty={<Empty title={t("noSettlements")} />}>
      {(rows) => (
        <ul className="flex flex-col gap-3">
          {rows.map((s) => (
            <li key={s.id}>
              <Link href={`/settlements/${s.id}`} className="block rounded-[var(--radius)] border border-border bg-surface p-4 hover:bg-surface-2">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="font-semibold"><DateTime value={s.period_start} dateOnly /> – <DateTime value={s.period_end} dateOnly /></p>
                    <p className="text-sm">{t("net")}: <Money amount={s.net_amount} currency={s.currency} /> · {t("mdr")}: <Money amount={s.mdr_amount} currency={s.currency} /></p>
                  </div>
                  <Badge value={s.status} />
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Async>
  );
}

function Fees() {
  const t = useT();
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [error, setError] = useState<unknown>(null);
  const fees = useQuery({
    queryKey: ["fees", from, to],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/fees", { params: { query: { from, to } } })) as unknown as FeeStatement,
  });
  return (
    <>
      <Card className="mb-4">
        <div className="grid grid-cols-2 items-end gap-3 sm:grid-cols-3">
          <Field label={t("from")}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={t("to")}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Button variant="secondary" onClick={async () => {
            setError(null);
            try {
              await download(`/v1/merchant/fees/statement?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
                `fee-statement-${from}-${to}.csv`);
            } catch (e) { setError(e); }
          }}>{t("downloadCsv")}</Button>
        </div>
        <ErrorBanner error={error} className="mt-3" />
      </Card>
      <Async q={fees}>
        {(f) => (
          <div className="flex flex-col gap-4">
            {f.fee_plan && (
              <Card title={t("feePlan")}>
                <Summary items={[
                  { label: t("feePlan"), value: f.fee_plan.name ?? f.fee_plan.code ?? "—" },
                  { label: t("mdrRate"), value: f.fee_plan.mdr_percentage ? `${(Number(f.fee_plan.mdr_percentage) * 100).toFixed(2)}%` : "—" },
                  { label: t("fixedFee"), value: <Money amount={f.fee_plan.fixed_fee} currency={f.fee_plan.currency ?? f.currency} /> },
                ]} />
              </Card>
            )}
            <Card title={t("settlements")} flush>
              <Table rows={f.settlements ?? []} rowKey={(r) => r.settlement_id ?? r.id ?? `${r.period_start}`}
                empty={<div className="p-4"><Empty title={t("noSettlements")} /></div>}
                columns={[
                  { header: t("period"), cell: (r) => <><DateTime value={r.period_start} dateOnly /> – <DateTime value={r.period_end} dateOnly /></> },
                  { header: t("payments"), cell: (r) => r.payment_count ?? "—", align: "right" },
                  { header: t("gross"), cell: (r) => <Money amount={r.gross_amount} currency={f.currency} />, align: "right" },
                  { header: t("mdr"), cell: (r) => <Money amount={r.mdr_amount} currency={f.currency} />, align: "right" },
                  { header: t("refunds"), cell: (r) => <Money amount={r.refund_amount} currency={f.currency} />, align: "right" },
                  { header: t("net"), cell: (r) => <Money amount={r.net_amount} currency={f.currency} />, align: "right" },
                ]} />
            </Card>
            {f.totals && (
              <Card title={t("totals")}>
                <Summary cols={4} items={[
                  { label: t("gross"), value: <Money amount={f.totals.gross_amount} currency={f.currency} /> },
                  { label: t("mdr"), value: <Money amount={f.totals.mdr_amount} currency={f.currency} /> },
                  { label: t("refunds"), value: <Money amount={f.totals.refund_amount} currency={f.currency} /> },
                  { label: t("net"), value: <Money amount={f.totals.net_amount} currency={f.currency} /> },
                ]} />
              </Card>
            )}
          </div>
        )}
      </Async>
    </>
  );
}
