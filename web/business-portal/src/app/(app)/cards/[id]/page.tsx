"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountLabel, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { CardSpendLimit } from "@/api/types";
import { ActionButton, opt } from "@/components/actions";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Loading, Money, PageHeader, Summary, Table, Tabs,
} from "@/components/ui";

const CHANNELS = ["ECOM", "POS", "ATM", "CONTACTLESS"] as const;
const PERIODS: CardSpendLimit["period"][] = ["PER_TRANSACTION", "DAILY", "WEEKLY", "MONTHLY", "YEARLY", "LIFETIME"];
const LIMIT_CHANNELS: CardSpendLimit["channel"][] = ["ANY", "ECOM", "POS", "ATM", "CONTACTLESS", "MOTO", "RECURRING"];

export default function CardPage() {
  const { id } = useParams<{ id: string }>();
  const perms = usePermissions();
  const accounts = useAccounts();
  const [tab, setTab] = useState<"controls" | "authorizations">("controls");
  const toggle = useRun();
  const path = { card_id: id };

  const card = useQuery({
    queryKey: ["card", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/cards/{card_id}", { params: { path } })),
  });
  const controls = useQuery({
    queryKey: ["card-controls", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/cards/{card_id}/controls", { params: { path, query: { limit: 100 } } })).data,
  });
  const limits = useQuery({
    queryKey: ["card-limits", id],
    queryFn: async () => unwrap(await api.GET("/v1/business/cards/{card_id}/limits", { params: { path, query: { limit: 100 } } })).data,
  });
  const auths = useQuery({
    queryKey: ["card-auths", id], enabled: tab === "authorizations",
    queryFn: async () => unwrap(await api.GET("/v1/business/cards/{card_id}/authorizations", { params: { path, query: { limit: 100 } } })).data,
  });

  if (card.isPending) return <Loading rows={5} />;
  if (card.error || !card.data) return <ErrorBanner error={card.error} />;
  const c = card.data;
  const blocked = (ch: string) => !!controls.data?.some((x) => x.channel === ch && x.control_type === "CHANNEL_DENY" && x.is_active);
  const admin = perms.isAdmin && c.status !== "CLOSED";

  return (
    <>
      <PageHeader back={{ href: "/cards", label: "Expense cards" }} title={`${c.nickname ?? c.cardholder_name} •••• ${c.last4}`}
        subtitle={`${c.form_factor} card · ${c.cardholder_name}`}
        actions={admin ? <>
          {c.status === "ACTIVE" && (
            <ActionButton label="Freeze" size="md" confirmLabel="Freeze card" description="Card payments are declined until you unfreeze it."
              run={async (_v, key) => unwrap(await api.POST("/v1/business/cards/{card_id}/freeze", { params: { path, header: idem(key) } }))} />
          )}
          {c.status === "FROZEN" && (
            <ActionButton label="Unfreeze" size="md" variant="primary" confirmLabel="Unfreeze card"
              run={async (_v, key) => unwrap(await api.POST("/v1/business/cards/{card_id}/unfreeze", { params: { path, header: idem(key) } }))} />
          )}
          <ActionButton label="Close card" size="md" variant="danger" confirmLabel="Close for good"
            description="Closing is permanent." fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/cards/{card_id}/close", {
              params: { path, header: idem(key) }, body: { reason: opt(v.reason) } }))} />
        </> : undefined} />

      <Card className="mb-5">
        <Summary cols={4} items={[
          { label: "Status", value: <Badge value={c.status} /> },
          { label: "Expires", value: `${String(c.expiry_month).padStart(2, "0")}/${c.expiry_year}` },
          { label: "Linked account", value: accountLabel(accounts.data?.find((a) => a.id === c.deposit_account_id)) },
          { label: "Issued", value: <DateTime value={c.created_at} /> },
        ]} />
      </Card>

      <Tabs value={tab} onChange={setTab} tabs={[{ key: "controls", label: "Channels & limits" }, { key: "authorizations", label: "Authorisations" }]} />

      {tab === "controls" ? (
        <div className="grid gap-5 xl:grid-cols-2">
          <Card title="Channels">
            <ul className="divide-y divide-border">
              {CHANNELS.map((ch) => (
                <li key={ch} className="flex items-center justify-between py-2">
                  <span>{ch === "ECOM" ? "Online (e-commerce)" : ch === "POS" ? "In-store (chip & PIN)" : ch === "ATM" ? "ATM withdrawals" : "Contactless"}</span>
                  <span className="flex items-center gap-3">
                    <Badge value={blocked(ch) ? "BLOCKED" : "ALLOWED"} tone={blocked(ch) ? "bad" : "good"} />
                    {admin && (
                      <Button size="sm" variant="secondary" disabled={toggle.pending} onClick={() => {
                        toggle.newKey();
                        void toggle.run(async (key) => unwrap(await api.PUT("/v1/business/cards/{card_id}/channels/{channel}", {
                          params: { path: { card_id: id, channel: ch }, header: idem(key) }, body: { blocked: !blocked(ch) } })));
                      }}>{blocked(ch) ? "Allow" : "Block"}</Button>
                    )}
                  </span>
                </li>
              ))}
            </ul>
            <ErrorBanner error={toggle.error} className="mt-3" />
          </Card>
          <Card title="Spend limits" actions={admin ? (
            <ActionButton label="Set limit" confirmLabel="Save limit"
              fields={[
                { name: "period", label: "Period", type: "select", required: true, options: PERIODS.map((p) => ({ value: p, label: p })) },
                { name: "channel", label: "Channel", type: "select", required: true, options: LIMIT_CHANNELS.map((p) => ({ value: p, label: p })) },
                { name: "max_amount", label: "Maximum amount", type: "number", required: true },
              ]}
              run={async (v, key) => unwrap(await api.PUT("/v1/business/cards/{card_id}/limits", {
                params: { path, header: idem(key) },
                body: { period: v.period as CardSpendLimit["period"], channel: v.channel as CardSpendLimit["channel"], max_amount: v.max_amount } }))} />
          ) : undefined}>
            <Async q={limits} empty={<Empty title="No card-specific limits" />}>
              {(rows) => (
                <Table rows={rows} rowKey={(l) => l.id} caption="Spend limits" columns={[
                  { header: "Period", cell: (l) => l.period },
                  { header: "Channel", cell: (l) => l.channel },
                  { header: "Limit", align: "right", cell: (l) => <Money amount={l.max_amount} currency={l.currency} /> },
                  { header: "Used", align: "right", cell: (l) => <Money amount={l.used_amount} currency={l.currency} /> },
                ]} />
              )}
            </Async>
          </Card>
        </div>
      ) : (
        <Card flush>
          <Async q={auths} empty={<div className="p-4"><Empty title="No authorisations yet" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(a) => a.id} caption="Authorisations" columns={[
                { header: "When", cell: (a) => <DateTime value={a.created_at} /> },
                { header: "Merchant", cell: (a) => <>{a.merchant_name ?? "—"}<span className="block text-xs text-muted">{[a.merchant_city, a.merchant_country].filter(Boolean).join(", ")}</span></> },
                { header: "Channel", cell: (a) => a.channel },
                { header: "Amount", align: "right", cell: (a) => <Money amount={a.transaction_amount} currency={a.transaction_currency} /> },
                { header: "Decision", cell: (a) => <Badge value={a.decision ?? a.status} /> },
                { header: "Reason", cell: (a) => a.decline_reason ?? "—" },
              ]} />
            )}
          </Async>
        </Card>
      )}
    </>
  );
}
