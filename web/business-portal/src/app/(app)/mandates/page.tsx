"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountLabel, accountOptions, today, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { PaymentMandate } from "@/api/types";
import { ActionButton, isoInstant, opt } from "@/components/actions";
import { useHeldNotice } from "@/components/held";
import { Async, Badge, Button, Card, DateTime, Empty, Money, PageHeader, Table } from "@/components/ui";

const SCHEMES: PaymentMandate["scheme"][] = ["INTERNAL", "SEPA_CORE", "SEPA_B2B", "BACS", "ACH", "PIX_AUTOMATICO", "UPI_AUTOPAY", "NACH", "BECS"];
const FREQUENCIES: PaymentMandate["frequency"][] = ["MONTHLY", "ONE_OFF", "DAILY", "WEEKLY", "QUARTERLY", "YEARLY", "ADHOC"];

function Collections({ mandate, canMake, onDone }: { mandate: PaymentMandate; canMake: boolean; onDone: (r: unknown) => void }) {
  const q = useQuery({
    queryKey: ["collections", mandate.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/mandates/{mandate_id}/collections",
      { params: { path: { mandate_id: mandate.id }, query: { limit: 100 } } })).data,
  });
  return (
    <div className="space-y-3">
      {canMake && mandate.status === "ACTIVE" && (
        <ActionButton label="Schedule collection" title={`Schedule a collection · ${mandate.mandate_reference}`} confirmLabel="Schedule"
          fields={[
            { name: "amount", label: `Amount (${mandate.currency})`, type: "number", required: true },
            { name: "due_date", label: "Due date", type: "date", required: true, defaultValue: today(5) },
            { name: "sequence_type", label: "Sequence type", type: "select", options: ["RCUR", "FRST", "OOFF", "FNAL"].map((v) => ({ value: v, label: v })) },
          ]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/mandates/{mandate_id}/collections", {
            params: { path: { mandate_id: mandate.id }, header: idem(key) },
            body: { amount: v.amount, due_date: v.due_date, sequence_type: opt(v.sequence_type) } }))}
          onDone={onDone} />
      )}
      <Async q={q} empty={<Empty title="No collections under this mandate" />}>
        {(rows) => (
          <Table rows={rows} rowKey={(c) => c.id} caption="Collections" columns={[
            { header: "Due", cell: (c) => <DateTime value={c.due_date} dateOnly /> },
            { header: "Amount", align: "right", cell: (c) => <Money amount={c.amount} currency={c.currency} /> },
            { header: "Sequence", cell: (c) => c.sequence_type },
            { header: "Status", cell: (c) => <Badge value={c.status} /> },
            { header: "Detail", cell: (c) => c.failure_reason ?? c.return_reason_code ?? "—" },
          ]} />
        )}
      </Async>
    </div>
  );
}

export default function MandatesPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [open, setOpen] = useState<string | null>(null);
  const [held, onDone] = useHeldNotice();
  const q = useQuery({
    queryKey: ["mandates"],
    queryFn: async () => unwrap(await api.GET("/v1/business/mandates", { params: { query: { limit: 200 } } })).data,
  });
  return (
    <>
      <PageHeader title="Direct debit mandates" subtitle="Mandates under which the business collects from its customers"
        actions={perms.canMake ? (
          <ActionButton label="Register mandate" variant="primary" size="md" confirmLabel="Register" wide
            fields={[
              { name: "creditor_account_id", label: "Credit account", type: "select", required: true,
                options: accountOptions(accounts.data, (a) => perms.initiateOn(a.id)) },
              { name: "scheme", label: "Scheme", type: "select", required: true, options: SCHEMES.map((s) => ({ value: s, label: s })) },
              { name: "creditor_id", label: "Creditor id", required: true },
              { name: "mandate_reference", label: "Mandate reference", required: true },
              { name: "debtor_name", label: "Debtor name", required: true },
              { name: "debtor_account_number", label: "Debtor account number / IBAN", required: true },
              { name: "debtor_agent_bic", label: "Debtor bank BIC" },
              { name: "frequency", label: "Frequency", type: "select", options: FREQUENCIES.map((s) => ({ value: s, label: s })) },
              { name: "max_amount", label: "Maximum amount per collection", type: "number" },
              { name: "first_collection_date", label: "First collection", type: "date" },
              { name: "final_collection_date", label: "Final collection", type: "date" },
              { name: "signed_at", label: "Signed at", type: "datetime-local" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/mandates", {
              params: { header: idem(key) },
              body: { creditor_account_id: v.creditor_account_id, scheme: v.scheme as PaymentMandate["scheme"], creditor_id: v.creditor_id,
                mandate_reference: v.mandate_reference, debtor_name: v.debtor_name, debtor_account_number: v.debtor_account_number,
                debtor_agent_bic: opt(v.debtor_agent_bic), frequency: opt(v.frequency) as PaymentMandate["frequency"] | undefined,
                max_amount: opt(v.max_amount), first_collection_date: opt(v.first_collection_date),
                final_collection_date: opt(v.final_collection_date), signed_at: isoInstant(v.signed_at) } }))}
            onDone={onDone} />
        ) : undefined} />
      {held}
      <Card flush>
        <Async q={q} empty={<div className="p-4"><Empty title="No mandates yet" /></div>}>
          {(rows) => (
            <Table rows={rows} rowKey={(m) => m.id} caption="Mandates" columns={[
              { header: "Reference", cell: (m) => <span className="font-mono text-xs">{m.mandate_reference}</span> },
              { header: "Debtor", cell: (m) => <>{m.debtor_name}<span className="block text-xs text-muted">{m.debtor_account_number}</span></> },
              { header: "Credit account", cell: (m) => <span className="text-xs">{accountLabel(accounts.data?.find((a) => a.id === m.creditor_account_id))}</span> },
              { header: "Scheme", cell: (m) => m.scheme },
              { header: "Frequency", cell: (m) => m.frequency },
              { header: "Max", align: "right", cell: (m) => <Money amount={m.max_amount} currency={m.currency} /> },
              { header: "Status", cell: (m) => <Badge value={m.status} /> },
              { header: "", align: "right", cell: (m) => (
                <div className="flex justify-end gap-2">
                  <Button size="sm" variant="secondary" onClick={() => setOpen(open === m.id ? null : m.id)}>
                    {open === m.id ? "Hide collections" : "Collections"}
                  </Button>
                  {perms.canMake && (m.status === "ACTIVE" || m.status === "PENDING") && (
                    <ActionButton label="Cancel" variant="danger" title={`Cancel mandate ${m.mandate_reference}?`} confirmLabel="Cancel mandate"
                      fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
                      run={async (v, key) => unwrap(await api.POST("/v1/business/mandates/{mandate_id}/cancel", {
                        params: { path: { mandate_id: m.id }, header: idem(key) }, body: { reason: v.reason } }))} />
                  )}
                </div>
              ) },
            ]} />
          )}
        </Async>
      </Card>
      {open && q.data?.find((m) => m.id === open) && (
        <Card className="mt-5" title={`Collections · ${q.data.find((m) => m.id === open)!.mandate_reference}`}>
          <Collections mandate={q.data.find((m) => m.id === open)!} canMake={perms.canMake} onDone={onDone} />
        </Card>
      )}
    </>
  );
}
