"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { accountOptions, useAccounts, usePermissions } from "@/api/business";
import { api, unwrap } from "@/api/client";
import type { PaymentKind, PaymentStatus } from "@/api/types";
import { KIND_LABEL, PaymentTable } from "@/components/payments";
import { Async, Card, Empty, Field, LinkButton, PageHeader, Select } from "@/components/ui";

const STATUSES: PaymentStatus[] = ["DRAFT", "PENDING_APPROVAL", "APPROVED", "RELEASED", "RELEASE_FAILED", "REJECTED", "CANCELLED"];

export default function PaymentsPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [status, setStatus] = useState("");
  const [kind, setKind] = useState("");
  const [account, setAccount] = useState("");
  const q = useQuery({
    queryKey: ["payments", status, kind, account],
    queryFn: async () => unwrap(await api.GET("/v1/business/payments", { params: { query: {
      limit: 200, status: (status || undefined) as PaymentStatus | undefined, kind: (kind || undefined) as PaymentKind | undefined,
      account_id: account || undefined } } })).data,
  });
  return (
    <>
      <PageHeader title="Payments" subtitle="Payments, bulk payments and payroll from accounts you may see"
        actions={perms.canInitiate ? <LinkButton href="/payments/new">New payment</LinkButton> : undefined} />
      <Card flush>
        <div className="flex flex-wrap gap-3 border-b border-border p-4">
          <Field label="Status">
            <Select value={status} onChange={(e) => setStatus(e.target.value)} placeholder="All"
              options={STATUSES.map((s) => ({ value: s, label: s.replaceAll("_", " ").toLowerCase() }))} />
          </Field>
          <Field label="Kind">
            <Select value={kind} onChange={(e) => setKind(e.target.value)} placeholder="All"
              options={Object.entries(KIND_LABEL).map(([value, label]) => ({ value, label }))} />
          </Field>
          <Field label="Account">
            <Select value={account} onChange={(e) => setAccount(e.target.value)} placeholder="All" options={accountOptions(accounts.data)} />
          </Field>
        </div>
        <div className="p-2">
          <Async q={q} empty={<div className="p-2"><Empty title="No payments match" /></div>}>
            {(rows) => <PaymentTable rows={rows} accounts={accounts.data} />}
          </Async>
        </div>
      </Card>
    </>
  );
}
