"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { Badge, Card, ErrorBanner, Loading, Mono, PageHeader, Table, type Column } from "@/components/ui";
import { Filters, Pager, Tabs, amountCell, usePaged, type FilterSpec, type Row } from "@/components/ops";

type Kind = "mm" | "fx" | "sec";

const TABS = [
  { id: "mm", label: "Money market" },
  { id: "fx", label: "FX" },
  { id: "sec", label: "Securities trades" },
] as const;

const DEAL_STATUS = ["CAPTURED", "VERIFIED", "CONFIRMED", "SETTLED", "MATURED", "CANCELLED", "AMENDED"] as const;

/** Per deal family: list endpoint, filters and columns (all from the treasury-deal-service contract). */
const KINDS: Record<Kind, { path: string; filters: FilterSpec[]; columns: Column<Row>[] }> = {
  mm: {
    path: "/v1/treasury/money-market-deals",
    filters: [
      { name: "status", label: "Status", options: DEAL_STATUS },
      { name: "deal_type", label: "Type", options: ["PLACEMENT", "BORROWING", "CALL_PLACEMENT", "CALL_BORROWING", "REPO",
        "REVERSE_REPO", "CD_ISSUED", "CD_PURCHASED"] },
      { name: "currency", label: "Currency", placeholder: "EUR" },
      { name: "maturity_date", label: "Maturity", type: "date" },
      { name: "treasury_counterparty_id", label: "Counterparty ID" },
    ],
    columns: [
      { key: "deal_ref", header: "Deal", render: (r) => <span className="font-medium">{String(r.deal_ref ?? "—")}</span> },
      { key: "deal_type", header: "Type" },
      { key: "principal", header: "Principal", align: "right", render: amountCell("principal") },
      { key: "interest_rate", header: "Rate", align: "right", render: (r) => <span className="num">{String(r.interest_rate ?? "—")}%</span> },
      { key: "value_date", header: "Value date" },
      { key: "maturity_date", header: "Maturity", render: (r) => String(r.maturity_date ?? (r.is_call ? "call" : "—")) },
      { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
    ],
  },
  fx: {
    path: "/v1/treasury/fx-deals",
    filters: [
      { name: "status", label: "Status", options: DEAL_STATUS },
      { name: "deal_type", label: "Type", options: ["SPOT", "FORWARD", "SWAP", "NDF"] },
      { name: "trade_date", label: "Trade date", type: "date" },
      { name: "treasury_counterparty_id", label: "Counterparty ID" },
    ],
    columns: [
      { key: "deal_ref", header: "Deal", render: (r) => <span className="font-medium">{String(r.deal_ref ?? "—")}</span> },
      { key: "deal_type", header: "Type" },
      { key: "pair", header: "Near leg", render: (r) => {
        const leg = ((r.legs as Row[] | undefined) ?? []).find((l) => l.leg_type === "NEAR") ?? (r.legs as Row[] | undefined)?.[0];
        return leg ? <span className="num">{String(leg.buy_sell)} {String(leg.base_amount)} {String(leg.base_currency)}/{String(leg.quote_currency)} @ {String(leg.deal_rate)}</span> : "—";
      } },
      { key: "trade_date", header: "Trade date" },
      { key: "mtm_value", header: "MTM", align: "right", render: amountCell("mtm_value", "mtm_currency") },
      { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
    ],
  },
  sec: {
    path: "/v1/treasury/securities-trades",
    filters: [
      { name: "status", label: "Status", options: ["PENDING", "SETTLED", "MATURED", "SOLD", "CANCELLED"] },
      { name: "buy_sell", label: "Side", options: ["BUY", "SELL"] },
      { name: "trade_date", label: "Trade date", type: "date" },
      { name: "treasury_counterparty_id", label: "Counterparty ID" },
    ],
    columns: [
      { key: "deal_ref", header: "Deal", render: (r) => <span className="font-medium">{String(r.deal_ref ?? "—")}</span> },
      { key: "buy_sell", header: "Side" },
      { key: "portfolio", header: "Portfolio" },
      { key: "nominal", header: "Nominal", align: "right", render: amountCell("nominal") },
      { key: "clean_price", header: "Clean price", align: "right" },
      { key: "settlement_amount", header: "Settlement", align: "right", render: amountCell("settlement_amount") },
      { key: "settlement_date", header: "Settles" },
      { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
    ],
  },
};

function DealList({ kind }: { kind: Kind }) {
  const router = useRouter();
  const cfg = KINDS[kind];
  const [filters, setFilters] = useState<Record<string, string>>({});
  const list = usePaged("treasury-deals", cfg.path, filters);
  return (
    <Card>
      <Filters specs={cfg.filters} value={filters} onChange={setFilters} />
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} columns={cfg.columns} empty="No deals match."
          onRow={(r) => router.push(`/treasury/deals/${r.id}?kind=${kind}`)} />
      )}
      <Pager p={list} />
    </Card>
  );
}

function Deals() {
  const router = useRouter();
  const params = useSearchParams();
  const kind = (["mm", "fx", "sec"].includes(params.get("kind") ?? "") ? params.get("kind") : "mm") as Kind;
  return (
    <>
      <PageHeader title="Treasury deals" subtitle={<>Money market, FX and securities deals across their lifecycle · <Mono>capture → verify → confirm → settle</Mono></>} />
      <Tabs tabs={TABS} value={kind} onChange={(k) => router.replace(`/treasury/deals?kind=${k}`)} />
      <DealList key={kind} kind={kind} />
    </>
  );
}

export default function Page() {
  return <Suspense><Deals /></Suspense>;
}
