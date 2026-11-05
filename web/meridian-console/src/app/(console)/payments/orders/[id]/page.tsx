"use client";

import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Mono, Money, PageHeader } from "@/components/ui";
import { Action, Details, Stat, Tabs, Timeline, rows, str, type Row } from "@/components/ops";
import { DetailSections } from "@/components/detail";
import { paymentOrderDetailSections } from "@/lib/entities/payment";

// WE-ETM-1 (G-08): one Order & Pay (ETM) order — the segregated order / payment / fulfilment legs, the
// payment-gateway heartbeat, R/Y/G settlement alerts, and ambiguous-case resolution.
const PAYMENT_OUTCOMES = ["SETTLED", "FAILED", "AMBIGUOUS"] as const;
const FULFILMENT_STATES = ["IN_PROGRESS", "FULFILLED", "FAILED", "CANCELLED"] as const;
const TERMINAL = ["CANCELLED", "FULFILLED", "FAILED"];

const TABS = [
  { id: "detail", label: "Detail" },
  { id: "events", label: "Events" },
] as const;
type Tab = (typeof TABS)[number]["id"];

function alertTone(level: string): "good" | "warn" | "bad" | undefined {
  if (level === "GREEN") return "good";
  if (level === "YELLOW") return "warn";
  if (level === "RED") return "bad";
  return undefined;
}

function OrderDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("detail");
  const key = ["payment-orders", id];
  const inv = [["payment-orders"]];
  const q = useQuery({ queryKey: key, queryFn: () => call<Row>("GET", `/v1/payment-orders/${id}`) });
  const events = useQuery({ queryKey: [...key, "events"], enabled: tab === "events",
    queryFn: () => call<Row[]>("GET", `/v1/payment-orders/${id}/events`) });

  if (q.isLoading) return <Loading />;
  const o = q.data;
  const orderStatus = String(o?.order_status ?? "");
  const paymentStatus = String(o?.payment_status ?? "");
  const fulfilment = String(o?.fulfilment_status ?? "");
  const alert = String(o?.alert_level ?? "");
  const active = !TERMINAL.includes(orderStatus);

  return (
    <>
      <PageHeader title="Order & Pay (ETM)"
        subtitle={o && <><Mono>{String(o.reference ?? id)}</Mono> · <Money amount={o.amount as string} currency={o.currency as string} /> · <Badge value={orderStatus} /></>}
        actions={o && <>
          {active && paymentStatus !== "SETTLED" && (
            <Action label="Pay" path={`/v1/payment-orders/${id}/pay`} invalidate={inv}
              title="Initiate payment leg"
              description="Starts the segregated payment leg for this order."
              fields={[{ name: "payment_method", label: "Payment method", placeholder: "WALLET / CARD / BANK" }]} />
          )}
          {active && (paymentStatus === "PENDING" || paymentStatus === "INITIATED") && (
            <Action label="Payment result" variant="secondary" path={`/v1/payment-orders/${id}/payment-result`} invalidate={inv}
              title="Record payment-gateway result"
              description="Simulates the PG callback. AMBIGUOUS parks the order for settlement resolution."
              fields={[
                { name: "outcome", label: "Outcome", required: true, options: PAYMENT_OUTCOMES },
                { name: "pg_heartbeat_status", label: "PG heartbeat", placeholder: "UP / DOWN" },
                { name: "reason", label: "Reason", type: "textarea" },
              ]} />
          )}
          {active && (
            <Action label="Fulfil" variant="secondary" path={`/v1/payment-orders/${id}/fulfil`} invalidate={inv}
              title="Advance fulfilment leg"
              fields={[
                { name: "status", label: "Fulfilment status", required: true, options: FULFILMENT_STATES },
                { name: "reason", label: "Reason", type: "textarea" },
              ]} />
          )}
          {active && (
            <Action label="Heartbeat" variant="ghost" path={`/v1/payment-orders/${id}/heartbeat`} invalidate={inv}
              title="Record payment-gateway heartbeat"
              description="Drives the R/Y/G settlement alert for in-flight orders."
              fields={[{ name: "status", label: "Heartbeat status", required: true, placeholder: "UP / DOWN" }]} />
          )}
          {paymentStatus === "AMBIGUOUS" && (
            <Action label="Resolve settlement" path={`/v1/payment-orders/${id}/resolve`} invalidate={inv}
              title="Resolve ambiguous settlement"
              description="Settles the ambiguous payment for this order (roll-forward / roll-back per the confirmed PG state)." />
          )}
          {active && (
            <Action label="Cancel" variant="danger" path={`/v1/payment-orders/${id}/cancel`} invalidate={inv}
              confirmLabel="Cancel order"
              fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]} />
          )}
        </>} />
      <ErrorBanner error={q.error} />

      <div className="mb-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Order" value={orderStatus || "—"} />
        <Stat label="Payment" value={paymentStatus || "—"} tone={paymentStatus === "SETTLED" ? "good" : paymentStatus === "FAILED" ? "bad" : paymentStatus === "AMBIGUOUS" ? "warn" : undefined} />
        <Stat label="Fulfilment" value={fulfilment || "—"} tone={fulfilment === "FULFILLED" ? "good" : fulfilment === "FAILED" ? "bad" : undefined} />
        <Stat label="Settlement alert" value={alert || "—"} tone={alertTone(alert)} />
      </div>

      {o && <div className="mb-6"><DetailSections sections={paymentOrderDetailSections(o as Record<string, unknown>)} /></div>}

      <Card>
        <Tabs tabs={TABS} value={tab} onChange={setTab} />

        {tab === "detail" && o && (
          <Details items={[
            ["Reference", <Mono key="r">{String(o.reference ?? "—")}</Mono>],
            ["Amount", <Money key="a" amount={o.amount as string} currency={o.currency as string} />],
            ["Payer wallet", o.payer_wallet_id ? <Mono key="pw">{String(o.payer_wallet_id)}</Mono> : null],
            ["Payee", o.payee ?? o.payee_id ?? o.merchant_id],
            ["Payment id", o.payment_id ? <Mono key="pi">{String(o.payment_id)}</Mono> : null],
            ["Order status", <Badge key="os" value={orderStatus} />],
            ["Payment status", <Badge key="ps" value={paymentStatus} />],
            ["Fulfilment status", <Badge key="fs" value={fulfilment} />],
            ["Alert level", <Badge key="al" value={alert} />],
            ["PG heartbeat", o.pg_heartbeat_status],
            ["Created", o.created_at],
            ["Updated", o.updated_at],
          ]} />
        )}

        {tab === "events" && (
          <>
            <ErrorBanner error={events.error} />
            {events.isLoading ? <Loading /> : (
              <Timeline items={rows(events.data).map((e) => ({
                at: e.at ?? e.created_at,
                status: str(e.event_type ?? e.type),
                title: [e.from_status, e.to_status].filter(Boolean).length
                  ? `${str(e.from_status)} → ${str(e.to_status)}`
                  : str(e.leg ?? e.event_type ?? e.type),
                detail: e.alert_level ? `alert ${str(e.alert_level)}${e.reason ? ` · ${str(e.reason)}` : ""}` : (e.reason ? str(e.reason) : undefined),
              }))} />
            )}
          </>
        )}
      </Card>
    </>
  );
}

export default function Page() {
  return <Suspense><OrderDetail /></Suspense>;
}
