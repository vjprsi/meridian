"use client";

import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Loading, Money, PageHeader, Select, Table } from "@/components/ui";
import type { AccountBalance, Ledger, LedgerAccount, Page } from "@/lib/types";

const TYPES = ["ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE", "OFF_BALANCE"];

export default function LedgerDetail() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ code: "", name: "", account_type: "LIABILITY", normal_balance: "CREDIT",
    currency: "USD", allow_negative: false });

  const ledger = useQuery({ queryKey: ["ledger", id], queryFn: () => call<Ledger>("GET", `/v1/ledgers/${id}`) });
  const accounts = useQuery({
    queryKey: ["accounts", id],
    queryFn: async () => {
      const page = await call<Page<LedgerAccount>>("GET", `/v1/ledger-accounts?ledger_id=${id}&limit=200`);
      const balances = await Promise.all(page.data.map((a) =>
        call<AccountBalance>("GET", `/v1/ledger-accounts/${a.id}/balance`).catch(() => null)));
      return page.data.map((a, i) => ({ ...a, balance: balances[i] }));
    },
  });
  const create = useMutation({
    mutationFn: () => call<LedgerAccount>("POST", "/v1/ledger-accounts", { ...form, ledger_id: id }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["accounts", id] }); setOpen(false); },
  });
  const verify = useMutation({ mutationFn: () => call<Record<string, unknown>>("POST", `/v1/ledgers/${id}/verify`) });

  return (
    <>
      <PageHeader title={ledger.data ? `${ledger.data.name}` : "Ledger"}
        subtitle={ledger.data && <>{ledger.data.code} · base {ledger.data.base_currency} · <Badge value={ledger.data.status} /></>}
        actions={<>
          <Button variant="secondary" onClick={() => verify.mutate()} disabled={verify.isPending}>Verify integrity</Button>
          <Button variant="secondary" onClick={() => router.push(`/ledger/post?ledger=${id}`)}>Post entry</Button>
          <Button onClick={() => setOpen(!open)}>{open ? "Cancel" : "Open account"}</Button>
        </>} />
      <ErrorBanner error={ledger.error ?? verify.error} />
      {verify.data && (
        <div className={`mb-4 rounded-[var(--radius)] px-4 py-3 text-sm ${verify.data.passed ? "bg-good-soft text-good" : "bg-bad-soft text-bad"}`}>
          Integrity {verify.data.passed ? "verified" : "FAILED"}: {String(verify.data.accounts_checked)} accounts,{" "}
          {String(verify.data.entries_checked)} entries · balance mismatches {String(verify.data.balance_mismatches)} ·
          hash mismatches {String(verify.data.hash_mismatches)}
        </div>
      )}
      {open && (
        <Card title="Open ledger account" className="mb-6">
          <ErrorBanner error={create.error} />
          <form className="grid grid-cols-1 gap-4 md:grid-cols-3" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
            <Field label="Code"><Input required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value })} /></Field>
            <Field label="Name"><Input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Field>
            <Field label="Currency"><Input required maxLength={3} value={form.currency}
              onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} /></Field>
            <Field label="Type"><Select options={TYPES} value={form.account_type}
              onChange={(e) => setForm({ ...form, account_type: e.target.value,
                normal_balance: ["ASSET", "EXPENSE"].includes(e.target.value) ? "DEBIT" : "CREDIT" })} /></Field>
            <Field label="Normal balance"><Select options={["DEBIT", "CREDIT"]} value={form.normal_balance}
              onChange={(e) => setForm({ ...form, normal_balance: e.target.value })} /></Field>
            <Field label="Allow negative balance">
              <Select options={["no", "yes"]} value={form.allow_negative ? "yes" : "no"}
                onChange={(e) => setForm({ ...form, allow_negative: e.target.value === "yes" })} />
            </Field>
            <div><Button type="submit" disabled={create.isPending}>Open account</Button></div>
          </form>
        </Card>
      )}
      <Card title="Accounts">
        <ErrorBanner error={accounts.error} />
        {accounts.isLoading ? <Loading /> : (
          <Table rows={(accounts.data ?? []) as unknown as Record<string, unknown>[]}
            onRow={(r) => router.push(`/ledger/accounts/${r.id}`)} empty="No accounts yet."
            columns={[
              { key: "code", header: "Code", render: (r) => <span className="font-medium">{String(r.code)}</span> },
              { key: "name", header: "Name" },
              { key: "account_type", header: "Type" },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "posted", header: "Posted", align: "right", render: (r) => {
                const b = r.balance as AccountBalance | null; return <Money amount={b?.posted_balance} currency={String(r.currency)} />; } },
              { key: "available", header: "Available", align: "right", render: (r) => {
                const b = r.balance as AccountBalance | null; return <Money amount={b?.available_balance} currency={String(r.currency)} />; } },
            ]} />
        )}
      </Card>
    </>
  );
}
