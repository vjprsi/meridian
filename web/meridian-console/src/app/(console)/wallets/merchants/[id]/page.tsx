"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { call, download } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, Tabs, amountCell, dateCell, str, usePaged, type Row } from "@/components/ops";

const REASON = [{ name: "reason", label: "Reason", required: true, type: "textarea" as const }];
type Tab = "transactions" | "settlements" | "stores";

function MerchantDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("transactions");
  const merchant = useQuery({ queryKey: ["merchant", id], queryFn: () => call<Row>("GET", `/v1/merchants/${id}`) });
  const txns = usePaged("merchant-transactions", `/v1/merchants/${id}/transactions`, {}, { enabled: tab === "transactions" });
  const settlements = usePaged("merchant-settlements", `/v1/merchants/${id}/settlements`, {}, { enabled: tab === "settlements" });
  const stores = usePaged("merchant-stores", `/v1/merchants/${id}/stores`, {}, { enabled: tab === "stores" });
  const report = useMutation({
    mutationFn: (sid: string) => download(`/v1/merchant-settlements/${sid}/report`, `settlement-${sid}.csv`),
  });

  if (merchant.isLoading) return <Loading />;
  const m = merchant.data;
  const s = String(m?.status ?? "");
  const inv = [["merchant", id], ["merchants"]];

  return (
    <>
      <PageHeader title={str(m?.trading_name ?? "Merchant")}
        subtitle={m && <><Mono>{str(m.merchant_code)}</Mono> · {str(m.merchant_type)} · MCC {str(m.mcc)} · <Badge value={s} /></>}
        actions={m && <>
          {(s === "ONBOARDING" || s === "SUSPENDED") && (
            <Action label="Activate" path={`/v1/merchants/${id}/activate`} invalidate={inv}
              description="Checker action (different from the creator). Opens the merchant wallet on first activation."
              fields={[{ name: "comment", label: "Comment" }]} />
          )}
          {s === "ACTIVE" && (
            <Action label="Suspend" variant="secondary" path={`/v1/merchants/${id}/suspend`} invalidate={inv} fields={REASON}
              description="Suspends payment acceptance." />
          )}
          {s !== "TERMINATED" && (
            <Action label="Terminate" variant="danger" path={`/v1/merchants/${id}/terminate`} invalidate={inv} fields={REASON} />
          )}
        </>} />
      <ErrorBanner error={merchant.error ?? report.error} />
      {m && (
        <Card title="Merchant" className="mb-6">
          <Details items={[
            ["Legal name", m.legal_name],
            ["Registration no.", m.registration_number],
            ["Contact", m.contact_name],
            ["MSISDN", m.contact_msisdn],
            ["Email", m.contact_email],
            ["City / country", `${str(m.city)} / ${str(m.country)}`],
            ["Settlement wallet", m.settlement_wallet_id
              ? <Link className="text-accent hover:underline" href={`/wallets/${m.settlement_wallet_id}`}><Mono>{String(m.settlement_wallet_id)}</Mono></Link> : null],
            ["Settlement frequency", m.settlement_frequency],
            ["Fee plan", m.merchant_fee_plan_id],
            ["Daily limit", m.daily_limit ? <Money amount={String(m.daily_limit)} currency={m.currency as string} /> : null],
            ["Risk rating", m.risk_rating],
            ["Status reason", m.status_reason],
          ]} />
        </Card>
      )}
      <Tabs value={tab} onChange={setTab} tabs={[
        { id: "transactions", label: "Payments received" }, { id: "settlements", label: "Settlements" }, { id: "stores", label: "Stores" },
      ]} />
      {tab === "transactions" && (
        <Card>
          <ErrorBanner error={txns.error} />
          {txns.isLoading ? <Loading /> : (
            <Table rows={txns.rows} empty="No payments received."
              columns={[
                { key: "created_at", header: "When", render: dateCell("created_at") },
                { key: "reference", header: "Reference", render: (r) => <Mono>{str(r.reference)}</Mono> },
                { key: "txn_type", header: "Type" },
                { key: "amount", header: "Amount", align: "right", render: amountCell() },
                { key: "fee_amount", header: "Fee", align: "right", render: amountCell("fee_amount") },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
              ]} />
          )}
          <Pager p={txns} />
        </Card>
      )}
      {tab === "settlements" && (
        <Card>
          <ErrorBanner error={settlements.error} />
          {settlements.isLoading ? <Loading /> : (
            <Table rows={settlements.rows} empty="No settlements."
              columns={[
                { key: "period", header: "Period", render: (r) => `${str(r.period_start)} → ${str(r.period_end)}` },
                { key: "payment_count", header: "Payments", align: "right" },
                { key: "gross_amount", header: "Gross", align: "right", render: amountCell("gross_amount") },
                { key: "mdr_amount", header: "MDR", align: "right", render: amountCell("mdr_amount") },
                { key: "net_amount", header: "Net", align: "right", render: amountCell("net_amount") },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
                { key: "actions", header: "", render: (r) => (
                  <div className="flex gap-1">
                    {r.status === "CALCULATED" && (
                      <Action label="Approve" size="sm" path={`/v1/merchant-settlements/${r.id}/approve`}
                        invalidate={[["merchant-settlements"]]} description="Checker action: must differ from the maker."
                        fields={[{ name: "comment", label: "Comment" }]} />
                    )}
                    {r.status === "APPROVED" && (
                      <Action label="Pay" size="sm" path={`/v1/merchant-settlements/${r.id}/pay`} invalidate={[["merchant-settlements"]]}
                        description="Collects the MDR fee and pays the net amount to the settlement account." body={{}} />
                    )}
                    <Button variant="ghost" className="px-2 py-1 text-xs" disabled={report.isPending}
                      onClick={() => report.mutate(String(r.id))}>Report</Button>
                  </div>
                ) },
              ]} />
          )}
          <Pager p={settlements} />
        </Card>
      )}
      {tab === "stores" && (
        <Card>
          <ErrorBanner error={stores.error} />
          {stores.isLoading ? <Loading /> : (
            <Table rows={stores.rows} empty="No stores."
              columns={[
                { key: "store_code", header: "Code", render: (r) => <Mono>{str(r.store_code)}</Mono> },
                { key: "name", header: "Name" },
                { key: "city", header: "City" },
                { key: "country", header: "Country" },
                { key: "status", header: "Status", render: (r) => <Badge value={r.status as string} /> },
              ]} />
          )}
          <Pager p={stores} />
        </Card>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense><MerchantDetail /></Suspense>;
}
