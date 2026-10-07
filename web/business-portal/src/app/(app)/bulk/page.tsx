"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { accountOptions, today, useAccounts, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { BulkValidationError, BusinessPayment } from "@/api/types";
import { PaymentActions, PaymentTable } from "@/components/payments";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, Empty, ErrorBanner, Field, Input, Money, Notice, PageHeader, Select, Summary, Table, Textarea,
} from "@/components/ui";
import { BULK_CSV_HEADER } from "@/messages/en";

const SAMPLE = `${BULK_CSV_HEADER}
Jane Wanjiru,0012345678,1500.00,ACCOUNT_NUMBER,,,KES,Salary October
Peter Otieno,0098765432,2300.50,ACCOUNT_NUMBER,,,KES,Salary October`;

export default function BulkPage() {
  const perms = usePermissions();
  const accounts = useAccounts();
  const [kind, setKind] = useState<"BULK" | "PAYROLL">("PAYROLL");
  const [debtor, setDebtor] = useState("");
  const [name, setName] = useState("");
  const [date, setDate] = useState(today(1));
  const [content, setContent] = useState("");
  const [resultId, setResultId] = useState<string | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const create = useRun();

  const batches = useQuery({
    queryKey: ["payments", "bulk"],
    queryFn: async () => {
      const [b, p] = await Promise.all([
        api.GET("/v1/business/payments", { params: { query: { kind: "BULK", limit: 50 } } }).then(unwrap),
        api.GET("/v1/business/payments", { params: { query: { kind: "PAYROLL", limit: 50 } } }).then(unwrap),
      ]);
      return [...b.data, ...p.data].sort((x, y) => y.created_at.localeCompare(x.created_at));
    },
  });

  const uploaded = useQuery({
    queryKey: ["payment", resultId], enabled: !!resultId,
    queryFn: async () => unwrap(await api.GET("/v1/business/payments/{payment_id}", { params: { path: { payment_id: resultId! } } })),
  });

  async function readFile(f: File | undefined) {
    setFileError(null);
    if (!f) return;
    if (f.size > 5 * 1024 * 1024) { setFileError("The file is larger than 5 MB."); return; }
    setContent(await f.text());
    if (!name) setName(f.name.replace(/\.[^.]+$/, ""));
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await create.run(async (key) => unwrap(await api.POST("/v1/business/bulk-payments", {
      params: { header: idem(key) },
      body: { kind, debtor_account_id: debtor, name, content, requested_execution_date: date || undefined },
    })));
    if (r.ok) setResultId(r.data.id);
  }

  if (perms.loaded && !perms.canInitiate) {
    return (
      <>
        <PageHeader title="Bulk & payroll" />
        <Notice tone="warn">You are not entitled to initiate payments. Ask an administrator of your business.</Notice>
      </>
    );
  }

  const result: BusinessPayment | undefined = uploaded.data;
  const errors = (result?.validation_errors ?? []) as BulkValidationError[];

  return (
    <>
      <PageHeader title="Bulk payments & payroll" subtitle="Upload a CSV batch; lines are validated, then the batch is submitted and approved like a single payment" />

      <div className="grid gap-5 xl:grid-cols-2">
        <Card title="Upload a batch">
          <form onSubmit={submit} className="flex flex-col gap-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Kind">
                <Select value={kind} onChange={(e) => setKind(e.target.value as "BULK" | "PAYROLL")}
                  options={[{ value: "PAYROLL", label: "Payroll" }, { value: "BULK", label: "Bulk payment" }]} />
              </Field>
              <Field label="Debit account">
                <Select value={debtor} onChange={(e) => setDebtor(e.target.value)} required placeholder="Choose an account"
                  options={accountOptions(accounts.data, (a) => perms.initiateOn(a.id))} />
              </Field>
              <Field label="Batch name"><Input value={name} onChange={(e) => setName(e.target.value)} required maxLength={140} /></Field>
              <Field label="Execution date" optional><Input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
            </div>
            <Field label="CSV file" error={fileError ?? undefined}>
              <Input type="file" accept=".csv,text/csv,text/plain" onChange={(e) => readFile(e.target.files?.[0])} />
            </Field>
            <Field label="…or paste the CSV" hint={<>Header: <code className="font-mono">creditor_name, creditor_account_number, amount</code>; optional
              <code className="font-mono"> creditor_account_id_type, creditor_agent_bic, creditor_agent_clearing_id, currency, remittance</code>.</>}>
              <Textarea name="content" value={content} onChange={(e) => setContent(e.target.value)} required rows={8}
                className="font-mono text-xs" spellCheck={false} placeholder={BULK_CSV_HEADER} />
            </Field>
            <ErrorBanner error={create.error} />
            <div className="flex flex-wrap gap-2">
              <Button type="submit" disabled={create.pending || !content.trim()}>{create.pending ? "Validating…" : "Upload and validate"}</Button>
              <Button variant="secondary" onClick={() => setContent(SAMPLE)}>Insert example</Button>
            </div>
          </form>
        </Card>

        <Card title="Expected CSV format">
          <pre className="overflow-x-auto rounded-[var(--radius)] bg-surface-2 p-3 font-mono text-xs" data-testid="csv-header">{BULK_CSV_HEADER}</pre>
          <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-muted">
            <li><b>creditor_name</b>, <b>creditor_account_number</b> and <b>amount</b> are required on every line.</li>
            <li><b>creditor_account_id_type</b>: IBAN, BBAN or ACCOUNT_NUMBER (default ACCOUNT_NUMBER).</li>
            <li><b>currency</b> defaults to the debit account currency; <b>remittance</b> is shown to the beneficiary.</li>
            <li>Amounts use a dot as decimal separator, no thousands separators.</li>
          </ul>
        </Card>
      </div>

      {result && (
        <Card className="mt-5" title={<span className="flex items-center gap-2">Draft {result.name} <Badge value={result.status} /></span>}
          actions={<><PaymentActions p={result} perms={perms} /><Link className="prose-link text-sm" href={`/payments/${result.id}`}>Open</Link></>}>
          <Summary cols={4} items={[
            { label: "Lines", value: result.item_count },
            { label: "Invalid lines", value: <span className={result.invalid_count ? "text-bad" : ""}>{result.invalid_count}</span> },
            { label: "Total", value: <Money amount={result.amount} currency={result.currency} /> },
            { label: "Execution date", value: result.requested_execution_date ?? "—" },
          ]} />
          {errors.length > 0 ? (
            <div className="mt-4">
              <Notice tone="warn">Fix these lines and upload again; a batch with invalid lines cannot be submitted.</Notice>
              <Table rows={errors} rowKey={(e) => `${e.line ?? e.line_no}-${e.field}-${e.code}`} caption="Validation errors" columns={[
                { header: "Line", cell: (e) => e.line ?? e.line_no ?? "—" },
                { header: "Field", cell: (e) => e.field ?? "—" },
                { header: "Code", cell: (e) => <span className="font-mono text-xs">{e.code}</span> },
                { header: "Message", cell: (e) => e.message },
              ]} />
            </div>
          ) : <p className="mt-4 text-sm text-good">All lines are valid. Submit the batch for approval.</p>}
        </Card>
      )}

      <Card className="mt-5" title="Recent batches" flush>
        <div className="p-2">
          <Async q={batches} empty={<div className="p-2"><Empty title="No batches yet" /></div>}>
            {(rows) => <PaymentTable rows={rows} accounts={accounts.data} />}
          </Async>
        </div>
      </Card>
    </>
  );
}
