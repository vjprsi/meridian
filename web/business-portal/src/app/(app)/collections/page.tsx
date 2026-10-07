"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountLabel, accountOptions, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { PaymentLink, VirtualAccount } from "@/api/types";
import { ActionButton, isoInstant, opt } from "@/components/actions";
import { useHeldNotice } from "@/components/held";
import { Async, Badge, Button, Card, DateTime, Empty, Money, PageHeader, Table, Tabs } from "@/components/ui";

const PURPOSES: VirtualAccount["purpose"][] = ["COLLECTION", "INVOICE", "SUB_LEDGER", "MARKETPLACE_SELLER", "ESCROW", "PAYROLL", "CLIENT_MONEY"];

function VaTransactions({ va }: { va: VirtualAccount }) {
  const q = useQuery({
    queryKey: ["va-transactions", va.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/virtual-accounts/{virtual_account_id}/transactions",
      { params: { path: { virtual_account_id: va.id }, query: { limit: 100 } } })).data,
  });
  return (
    <Async q={q} empty={<Empty title="No credits yet" />}>
      {(rows) => (
        <Table rows={rows} rowKey={(t) => t.id} caption="Virtual account credits" columns={[
          { header: "Date", cell: (t) => <DateTime value={t.effective_at} /> },
          { header: "Reference", cell: (t) => t.reference ?? t.description ?? "—" },
          { header: "Amount", align: "right", cell: (t) => <Money amount={t.amount} currency={t.currency} /> },
          { header: "Direction", cell: (t) => t.direction },
        ]} />
      )}
    </Async>
  );
}

function LinkPayments({ link }: { link: PaymentLink }) {
  const q = useQuery({
    queryKey: ["link-payments", link.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/payment-links/{link_id}/payments",
      { params: { path: { link_id: link.id }, query: { limit: 100 } } })).data,
  });
  return (
    <Async q={q} empty={<Empty title="No payments received yet" />}>
      {(rows) => (
        <Table rows={rows} rowKey={(p) => p.id} caption="Payments received" columns={[
          { header: "Date", cell: (p) => <DateTime value={p.created_at} /> },
          { header: "Payer", cell: (p) => p.debtor_name ?? "—" },
          { header: "Amount", align: "right", cell: (p) => <Money amount={p.amount} currency={p.currency} /> },
          { header: "Status", cell: (p) => <Badge value={p.status} /> },
        ]} />
      )}
    </Async>
  );
}

export default function CollectionsPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [tab, setTab] = useState<"va" | "links">("va");
  const [openVa, setOpenVa] = useState<VirtualAccount | null>(null);
  const [openLink, setOpenLink] = useState<PaymentLink | null>(null);
  const vas = useQuery({
    queryKey: ["virtual-accounts"],
    queryFn: async () => unwrap(await api.GET("/v1/business/virtual-accounts", { params: { query: { limit: 200 } } })).data,
  });
  const links = useQuery({
    queryKey: ["payment-links"], enabled: tab === "links",
    queryFn: async () => unwrap(await api.GET("/v1/business/payment-links", { params: { query: { limit: 200 } } })).data,
  });
  const initiable = accountOptions(accounts.data, (a) => perms.initiateOn(a.id));
  const [held, onDone] = useHeldNotice();

  return (
    <>
      <PageHeader title="Collections" subtitle="Virtual accounts and payment links that collect into the business's accounts"
        actions={perms.canMake ? (tab === "va" ? (
          <ActionButton label="Open virtual account" variant="primary" size="md" confirmLabel="Open"
            fields={[
              { name: "master_account_id", label: "Master account", type: "select", required: true, options: initiable },
              { name: "purpose", label: "Purpose", type: "select", required: true, options: PURPOSES.map((p) => ({ value: p, label: p })) },
              { name: "owner_reference", label: "Owner reference (e.g. customer or invoice no.)" },
              { name: "expected_amount", label: "Expected amount", type: "number" },
              { name: "single_use", label: "Single use", type: "checkbox" },
              { name: "expires_at", label: "Expires at", type: "datetime-local" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/virtual-accounts", {
              params: { header: idem(key) },
              body: { master_account_id: v.master_account_id, purpose: v.purpose as VirtualAccount["purpose"],
                owner_reference: opt(v.owner_reference), expected_amount: opt(v.expected_amount), single_use: v.single_use === "true",
                expires_at: isoInstant(v.expires_at) } }))}
            onDone={onDone} />
        ) : (
          <ActionButton label="Create payment link" variant="primary" size="md" confirmLabel="Create"
            fields={[
              { name: "credit_account_id", label: "Credit account", type: "select", required: true, options: initiable },
              { name: "title", label: "Title", required: true },
              { name: "amount", label: "Fixed amount", type: "number", hint: "Leave empty to let the payer choose (within min/max)." },
              { name: "min_amount", label: "Minimum amount", type: "number" },
              { name: "max_amount", label: "Maximum amount", type: "number" },
              { name: "reusable", label: "Reusable", type: "checkbox" },
              { name: "max_payments", label: "Maximum payments", type: "number" },
              { name: "expires_at", label: "Expires at", type: "datetime-local" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/payment-links", {
              params: { header: idem(key) },
              body: { credit_account_id: v.credit_account_id, title: v.title, amount: opt(v.amount), min_amount: opt(v.min_amount),
                max_amount: opt(v.max_amount), reusable: v.reusable === "true",
                max_payments: opt(v.max_payments) ? Number(v.max_payments) : undefined, expires_at: isoInstant(v.expires_at) } }))}
            onDone={onDone} />
        )) : undefined} />
      {held}
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "va", label: "Virtual accounts" }, { key: "links", label: "Payment links" }]} />

      {tab === "va" ? (
        <Card flush>
          <Async q={vas} empty={<div className="p-4"><Empty title="No virtual accounts yet" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(v) => v.id} caption="Virtual accounts" columns={[
                { header: "Number", cell: (v) => <span className="font-mono text-xs">{v.iban ?? v.virtual_account_number}</span> },
                { header: "Purpose", cell: (v) => v.purpose },
                { header: "Reference", cell: (v) => v.owner_reference ?? "—" },
                { header: "Master account", cell: (v) => <span className="text-xs">{accountLabel(accounts.data?.find((a) => a.id === v.master_account_id))}</span> },
                { header: "Expected", align: "right", cell: (v) => <Money amount={v.expected_amount} currency={v.currency} /> },
                { header: "Status", cell: (v) => <Badge value={v.status} /> },
                { header: "", align: "right", cell: (v) => (
                  <div className="flex justify-end gap-2">
                    <Button size="sm" variant="secondary" onClick={() => setOpenVa(v)}>Transactions</Button>
                    {perms.canMake && v.status !== "CLOSED" && (
                      <ActionButton label="Close" variant="danger" title="Close this virtual account?" confirmLabel="Close account"
                        fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
                        run={async (f, key) => unwrap(await api.POST("/v1/business/virtual-accounts/{virtual_account_id}/close", {
                          params: { path: { virtual_account_id: v.id }, header: idem(key) }, body: { reason: opt(f.reason) } }))} />
                    )}
                  </div>
                ) },
              ]} />
            )}
          </Async>
        </Card>
      ) : (
        <Card flush>
          <Async q={links} empty={<div className="p-4"><Empty title="No payment links yet" /></div>}>
            {(rows) => (
              <Table rows={rows} rowKey={(l) => l.id} caption="Payment links" columns={[
                { header: "Title", cell: (l) => <>{l.title}<a className="prose-link block text-xs" href={l.url} target="_blank" rel="noreferrer noopener">{l.url}</a></> },
                { header: "Amount", align: "right", cell: (l) => l.amount ? <Money amount={l.amount} currency={l.currency} />
                  : <span className="text-xs">{l.min_amount ?? "any"} – {l.max_amount ?? "any"} {l.currency}</span> },
                { header: "Payments", cell: (l) => `${l.payment_count}${l.max_payments ? ` / ${l.max_payments}` : ""}` },
                { header: "Expires", cell: (l) => <DateTime value={l.expires_at} /> },
                { header: "Status", cell: (l) => <Badge value={l.status} /> },
                { header: "", align: "right", cell: (l) => (
                  <div className="flex justify-end gap-2">
                    <Button size="sm" variant="secondary" onClick={() => setOpenLink(l)}>Payments</Button>
                    {perms.canMake && l.status === "ACTIVE" && (
                      <ActionButton label="Disable" variant="danger" title={`Disable "${l.title}"?`} confirmLabel="Disable"
                        fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
                        run={async (f, key) => unwrap(await api.POST("/v1/business/payment-links/{link_id}/disable", {
                          params: { path: { link_id: l.id }, header: idem(key) }, body: { reason: opt(f.reason) } }))} />
                    )}
                  </div>
                ) },
              ]} />
            )}
          </Async>
        </Card>
      )}

      {tab === "va" && openVa && (
        <Card className="mt-5" title={`Credits · ${openVa.virtual_account_number}`}
          actions={<Button size="sm" variant="ghost" onClick={() => setOpenVa(null)}>Close</Button>}>
          <VaTransactions va={openVa} />
        </Card>
      )}
      {tab === "links" && openLink && (
        <Card className="mt-5" title={`Payments · ${openLink.title}`}
          actions={<Button size="sm" variant="ghost" onClick={() => setOpenLink(null)}>Close</Button>}>
          <LinkPayments link={openLink} />
        </Card>
      )}
    </>
  );
}
