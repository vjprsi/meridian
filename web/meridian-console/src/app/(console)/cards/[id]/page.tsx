"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Card, ErrorBanner, Loading, Money, Mono, PageHeader, Table } from "@/components/ui";
import { Action, Details, Pager, Tabs, Timeline, dateCell, rows, statusCell, usePaged, type Row } from "@/components/ops";
import {
  CARD_STATUSES, CHANNELS, CONTROL_TYPES, FORM_FACTORS, LIMIT_PERIODS, REPLACE_REASONS, SHIPPING_METHODS, clean, expiry, list, masked,
} from "../shared";

type Tab = "auths" | "controls" | "limits" | "history" | "pin";
const TABS = [
  { id: "auths", label: "Authorisations" },
  { id: "controls", label: "Controls" },
  { id: "limits", label: "Spend limits" },
  { id: "history", label: "Status history" },
  { id: "pin", label: "PIN events" },
] as const;

const BLOCK_STATUSES = CARD_STATUSES.filter((s) => ["BLOCKED", "LOST", "STOLEN"].includes(s));

function CardDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("auths");
  const card = useQuery({ queryKey: ["card", id], queryFn: () => call<Row>("GET", `/v1/cards/${id}`) });
  const c = card.data;
  const program = useQuery({
    queryKey: ["card-program", c?.card_program_id],
    queryFn: () => call<Row>("GET", `/v1/card-programs/${c!.card_program_id}`),
    enabled: !!c?.card_program_id,
    retry: false,
  });
  const inv = [["card", id], ["cards"], ["card-history", id]];

  if (card.isLoading) return <Loading />;
  const status = String(c?.status ?? "");
  const live = ["CREATED", "ACTIVE", "FROZEN"].includes(status);

  return (
    <>
      <PageHeader title={c ? `${String(c.cardholder_name ?? "Card")} · ${masked(c)}` : "Card"}
        subtitle={c && <>{String(c.form_factor)} · expires {expiry(c)} · <Badge value={status} />
          {c.status_reason ? <> · {String(c.status_reason)}</> : null}</>}
        actions={c && <>
          {status === "CREATED" && (
            <Action label="Activate" path={`/v1/cards/${id}/activate`} invalidate={inv}
              description="Physical cards require the last four digits as the activation code."
              fields={c.form_factor === "PHYSICAL" ? [{ name: "activation_code", label: "Activation code", required: true }] : []} />
          )}
          {status === "ACTIVE" && (
            <Action label="Freeze" variant="secondary" path={`/v1/cards/${id}/freeze`} invalidate={inv}
              description="Temporarily declines all authorisations until the card is unfrozen."
              fields={[{ name: "reason", label: "Reason" }]} />
          )}
          {status === "FROZEN" && (
            <Action label="Unfreeze" path={`/v1/cards/${id}/unfreeze`} invalidate={inv}
              description="The card will authorise again immediately." />
          )}
          {live && (
            <Action label="Block" variant="danger" path={`/v1/cards/${id}/block`} invalidate={inv}
              description="Permanently blocks the card. This cannot be undone; issue a replacement afterwards."
              fields={[
                { name: "status", label: "Block as", options: BLOCK_STATUSES, required: true },
                { name: "reason", label: "Reason", required: true, type: "textarea" },
              ]} />
          )}
          {!["CLOSED", "REPLACED"].includes(status) && (
            <Action label="Replace" variant="secondary" path={`/v1/cards/${id}/replace`} invalidate={inv}
              description="Issues a new card. Keeping the PAN reissues the same number with a new expiry."
              fields={[
                { name: "reason", label: "Reason", options: REPLACE_REASONS, required: true },
                { name: "keep_pan", label: "Keep PAN", options: ["false", "true"], required: true },
                { name: "form_factor", label: "Form factor", options: FORM_FACTORS },
                { name: "shipping_method", label: "Shipping", options: SHIPPING_METHODS },
              ]}
              body={(v) => clean(v, ["keep_pan"])}
              onDone={(r) => { const n = (r as Row | undefined)?.new_card_id; if (n) router.push(`/cards/${String(n)}`); }} />
          )}
          {["ACTIVE", "EXPIRED"].includes(status) && (
            <Action label="Renew" variant="secondary" path={`/v1/cards/${id}/renew`} invalidate={inv}
              description="Same PAN under a new token with a new expiry and CVV." />
          )}
          {!["CLOSED", "REPLACED"].includes(status) && (
            <Action label="Close" variant="danger" path={`/v1/cards/${id}/close`} invalidate={inv}
              description="Closes the card permanently." fields={[{ name: "reason", label: "Reason" }]} />
          )}
        </>} />
      <ErrorBanner error={card.error} />
      {c && (
        <div className="mb-6 grid grid-cols-1 gap-6 lg:grid-cols-3">
          <Card title="Card" className="lg:col-span-2">
            <Details items={[
              ["Card number", <span key="p" className="font-mono">{masked(c)}</span>],
              ["BIN", c.bin],
              ["Expiry", expiry(c)],
              ["Sequence", c.sequence_number],
              ["Program", program.data
                ? <Link key="pr" className="text-accent hover:underline" href={`/cards/programs/${String(c.card_program_id)}`}>
                    {String(program.data.name)} ({String(program.data.scheme)})</Link>
                : <Mono key="pr">{String(c.card_program_id)}</Mono>],
              ["Customer", <Link key="cu" className="text-accent hover:underline" href={`/customers/${String(c.customer_id)}`}>
                <Mono>{String(c.customer_id)}</Mono></Link>],
              ["Funding", c.linked_account_type],
              ["Ledger account", c.ledger_account_id
                ? <Link key="la" className="text-accent hover:underline" href={`/ledger/accounts/${String(c.ledger_account_id)}`}>
                    <Mono>{String(c.ledger_account_id)}</Mono></Link> : null],
              ["Nickname", c.nickname],
              ["Replaces", c.replaces_card_id
                ? <Link key="rp" className="text-accent hover:underline" href={`/cards/${String(c.replaces_card_id)}`}>
                    <Mono>{String(c.replaces_card_id)}</Mono></Link> : null],
              ["Activated", c.activated_at],
              ["Frozen", c.frozen_at],
              ["Closed", c.closed_at],
              ["Issued", c.created_at],
            ]} />
          </Card>
          <Card title="PIN">
            <Details cols={1} items={[["PIN set", !!c.pin_set], ["Failed tries", c.pin_try_count]]} />
            {Number(c.pin_try_count ?? 0) > 0 && (
              <div className="mt-4">
                <Action label="Reset PIN tries" variant="secondary" path={`/v1/cards/${id}/pin/unblock`}
                  invalidate={[["card", id], ["card-pin", id]]} description="Resets the PIN try counter so the cardholder can retry."
                  fields={[{ name: "reason", label: "Reason" }]} />
              </div>
            )}
          </Card>
        </div>
      )}
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      {tab === "auths" && <CardAuths id={id} />}
      {tab === "controls" && <Controls id={id} />}
      {tab === "limits" && <Limits id={id} currency={String(program.data?.currency ?? "")} />}
      {tab === "history" && <History id={id} />}
      {tab === "pin" && <PinEvents id={id} />}
    </>
  );
}

function CardAuths({ id }: { id: string }) {
  const router = useRouter();
  const list = usePaged("card-auths", `/v1/cards/${id}/authorizations`);
  return (
    <Card>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No authorisations on this card."
          onRow={(r) => router.push(`/cards/authorisations?id=${String(r.id)}`)}
          columns={[
            { key: "created_at", header: "When", render: dateCell("created_at") },
            { key: "merchant_name", header: "Merchant", render: (r) => <>{String(r.merchant_name ?? "—")}
              <span className="ml-1 text-xs text-muted">{String(r.merchant_country ?? "")}</span></> },
            { key: "mcc", header: "MCC" },
            { key: "channel", header: "Channel" },
            { key: "amount", header: "Amount", align: "right", render: (r) =>
              <Money amount={r.transaction_amount as string} currency={r.transaction_currency as string} /> },
            { key: "decision", header: "Decision", render: (r) => <>{statusCell("decision")(r)}
              {r.decline_reason ? <span className="ml-1 text-xs text-muted">{String(r.decline_reason)}</span> : null}</> },
            { key: "status", header: "Status", render: statusCell() },
          ]} />
      )}
      <Pager p={list} />
    </Card>
  );
}

function Controls({ id }: { id: string }) {
  const q = useQuery({ queryKey: ["card-controls", id], queryFn: () => call<unknown>("GET", `/v1/cards/${id}/controls?limit=100`) });
  const inv = [["card-controls", id]];
  return (
    <Card title="Controls" actions={
      <Action label="Add control" path="/v1/card-controls" invalidate={inv}
        description="Controls apply to this card only. MCC, merchant, country, channel and currency controls take a list of values."
        fields={[
          { name: "control_type", label: "Type", options: CONTROL_TYPES, required: true },
          { name: "channel", label: "Channel", options: CHANNELS },
          { name: "values", label: "Values", placeholder: "e.g. 7995, 5933", hint: "Comma separated" },
          { name: "max_amount", label: "Max amount", type: "number" },
          { name: "currency", label: "Currency", placeholder: "USD" },
        ]}
        body={(v) => ({ ...clean(v), values: list(v.values), card_id: id, is_active: true })} />
    }>
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : (
        <Table rows={rows(q.data)} empty="No controls on this card or its program."
          columns={[
            { key: "scope", header: "Scope", render: (r) => r.card_id ? "Card" : "Program" },
            { key: "control_type", header: "Type" },
            { key: "channel", header: "Channel" },
            { key: "values", header: "Values", render: (r) => Array.isArray(r.values) ? (r.values as string[]).join(", ") || "—" : "—" },
            { key: "max_amount", header: "Max", align: "right", render: (r) => <Money amount={r.max_amount as string} currency={r.currency as string} /> },
            { key: "is_active", header: "Active", render: (r) => <Badge value={r.is_active ? "ACTIVE" : "INACTIVE"} tone={r.is_active ? "good" : "muted"} /> },
            { key: "actions", header: "", render: (r) => r.card_id ? (
              <div className="flex justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                <Action size="sm" variant="secondary" label={r.is_active ? "Disable" : "Enable"} method="PATCH"
                  path={`/v1/card-controls/${String(r.id)}`} body={{ is_active: !r.is_active }} invalidate={inv}
                  description={`${r.is_active ? "Disable" : "Enable"} the ${String(r.control_type)} control.`} />
                <Action size="sm" variant="danger" label="Delete" method="DELETE" path={`/v1/card-controls/${String(r.id)}`}
                  invalidate={inv} description="Delete this control permanently." />
              </div>
            ) : <span className="text-xs text-muted">inherited</span> },
          ]} />
      )}
    </Card>
  );
}

function Limits({ id, currency }: { id: string; currency: string }) {
  const q = useQuery({ queryKey: ["card-limits", id], queryFn: () => call<unknown>("GET", `/v1/cards/${id}/limits?limit=100`) });
  const inv = [["card-limits", id]];
  return (
    <Card title="Spend limits" actions={
      <Action label="Add limit" path="/v1/card-spend-limits" invalidate={inv}
        fields={[
          { name: "period", label: "Period", options: LIMIT_PERIODS, required: true },
          { name: "channel", label: "Channel", options: CHANNELS },
          { name: "max_amount", label: "Max amount", type: "number" },
          { name: "max_count", label: "Max count", type: "number" },
          { name: "currency", label: "Currency", required: true, initial: currency },
        ]}
        body={(v) => ({ ...clean(v), max_count: v.max_count ? Number(v.max_count) : undefined, card_id: id, is_active: true })} />
    }>
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : (
        <Table rows={rows(q.data)} empty="No spend limits."
          columns={[
            { key: "scope", header: "Scope", render: (r) => r.card_id ? "Card" : "Program" },
            { key: "period", header: "Period" },
            { key: "channel", header: "Channel" },
            { key: "used", header: "Used", align: "right", render: (r) => <Money amount={r.used_amount as string} currency={r.currency as string} /> },
            { key: "max_amount", header: "Limit", align: "right", render: (r) => <Money amount={r.max_amount as string} currency={r.currency as string} /> },
            { key: "count", header: "Count", align: "right", render: (r) => `${String(r.used_count ?? 0)} / ${r.max_count == null ? "∞" : String(r.max_count)}` },
            { key: "is_active", header: "Active", render: (r) => <Badge value={r.is_active ? "ACTIVE" : "INACTIVE"} tone={r.is_active ? "good" : "muted"} /> },
            { key: "actions", header: "", render: (r) => r.card_id ? (
              <div className="flex justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                <Action size="sm" variant="secondary" label="Edit" method="PATCH" path={`/v1/card-spend-limits/${String(r.id)}`}
                  invalidate={inv} title={`Edit ${String(r.period)} limit`}
                  fields={[
                    { name: "max_amount", label: "Max amount", type: "number", initial: String(r.max_amount ?? "") },
                    { name: "max_count", label: "Max count", type: "number", initial: r.max_count == null ? "" : String(r.max_count) },
                    { name: "is_active", label: "Active", options: ["true", "false"], required: true, initial: String(!!r.is_active) },
                  ]}
                  body={(v) => ({ ...clean(v, ["is_active"]), max_count: v.max_count ? Number(v.max_count) : undefined })} />
                <Action size="sm" variant="danger" label="Delete" method="DELETE" path={`/v1/card-spend-limits/${String(r.id)}`}
                  invalidate={inv} description="Delete this spend limit." />
              </div>
            ) : <span className="text-xs text-muted">inherited</span> },
          ]} />
      )}
    </Card>
  );
}

function History({ id }: { id: string }) {
  const q = useQuery({ queryKey: ["card-history", id], queryFn: () => call<unknown>("GET", `/v1/cards/${id}/status-history?limit=100`) });
  return (
    <Card title="Status history">
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : (
        <Timeline items={rows(q.data).map((h) => ({
          at: h.created_at, status: String(h.to_status ?? ""),
          title: `${String(h.from_status ?? "—")} → ${String(h.to_status ?? "—")}`,
          detail: [h.reason, h.channel, h.actor_id ?? h.created_by].filter(Boolean).map(String).join(" · "),
        }))} />
      )}
    </Card>
  );
}

function PinEvents({ id }: { id: string }) {
  const list = usePaged("card-pin", `/v1/cards/${id}/pin-events`);
  return (
    <Card title="PIN events">
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows} empty="No PIN events."
          columns={[
            { key: "created_at", header: "When", render: dateCell("created_at") },
            { key: "event_type", header: "Event" },
            { key: "channel", header: "Channel" },
            { key: "success", header: "Result", render: (r) => <Badge value={r.success ? "SUCCEEDED" : "FAILED"} /> },
            { key: "created_by", header: "By" },
          ]} />
      )}
      <Pager p={list} />
    </Card>
  );
}

export default function Page() {
  return <Suspense><CardDetail /></Suspense>;
}
