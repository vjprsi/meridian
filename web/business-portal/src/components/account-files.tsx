"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { today } from "@/api/business";
import { api, download, idem, unwrap } from "@/api/client";
import type { DepositAccount, Statement } from "@/api/types";
import { ActionButton } from "./actions";
import { useAction } from "./step-up";
import { Async, Badge, Button, DateTime, Empty, ErrorBanner, Field, Input, Money, Select, Table } from "./ui";

const STATEMENT_FORMATS = ["PDF", "CSV", "CAMT_053", "MT940", "OFX", "JSON"] as const;
const EXT: Record<string, string> = { PDF: "pdf", CSV: "csv", CAMT_053: "xml", MT940: "sta", OFX: "ofx", JSON: "json" };

/** Statements of one account: list, request an on-demand statement, download. */
export function Statements({ account }: { account: DepositAccount }) {
  const [dlError, setDlError] = useState<unknown>(null);
  const action = useAction();
  const q = useQuery({
    queryKey: ["statements", account.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/accounts/{account_id}/statements",
      { params: { path: { account_id: account.id }, query: { limit: 100 } } })).data,
  });

  async function get(s: Statement) {
    setDlError(null);
    try {
      await action(() => download(`/v1/business/accounts/${account.id}/statements/${s.id}/download`,
        `statement-${account.account_number}-${s.statement_no}.${EXT[s.format] ?? "bin"}`));
    } catch (e) {
      setDlError(e);
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <ActionButton label="Request statement" title={`Request a statement · ${account.account_number}`}
          fields={[
            { name: "period_start", label: "From", type: "date", required: true, defaultValue: today(-30) },
            { name: "period_end", label: "To", type: "date", required: true, defaultValue: today() },
            { name: "format", label: "Format", type: "select", required: true, options: STATEMENT_FORMATS.map((f) => ({ value: f, label: f })) },
          ]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/accounts/{account_id}/statements", {
            params: { path: { account_id: account.id }, header: idem(key) },
            body: { period_start: v.period_start, period_end: v.period_end, format: v.format as Statement["format"] },
          }))} />
      </div>
      <ErrorBanner error={dlError} />
      <Async q={q} empty={<Empty title="No statements yet" />}>
        {(rows) => (
          <Table rows={rows} rowKey={(s) => s.id} caption="Statements" columns={[
            { header: "No.", cell: (s) => s.statement_no },
            { header: "Period", cell: (s) => <><DateTime value={s.period_start} dateOnly /> – <DateTime value={s.period_end} dateOnly /></> },
            { header: "Format", cell: (s) => s.format },
            { header: "Closing", align: "right", cell: (s) => <Money amount={s.closing_balance} currency={s.currency} /> },
            { header: "Status", cell: (s) => <Badge value={s.status} /> },
            { header: "", align: "right", cell: (s) => (
              <Button size="sm" variant="secondary" disabled={s.status !== "READY" && s.status !== "DELIVERED"} onClick={() => get(s)}>Download</Button>
            ) },
          ]} />
        )}
      </Async>
    </div>
  );
}

/** Accounting export of posted entries for a date range: CSV or ISO 20022 camt.053, saved as a file. */
export function ExportForm({ account }: { account: DepositAccount }) {
  const [from, setFrom] = useState(today(-30));
  const [to, setTo] = useState(today());
  const [format, setFormat] = useState<"CSV" | "CAMT_053">("CSV");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const action = useAction();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const qs = new URLSearchParams({ from, to, format });
      await action(() => download(`/v1/business/accounts/${account.id}/export?${qs}`,
        `${account.account_number}-${from}-${to}.${format === "CSV" ? "csv" : "xml"}`));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
      <Field label="From"><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} required /></Field>
      <Field label="To"><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} required /></Field>
      <Field label="Format">
        <Select value={format} onChange={(e) => setFormat(e.target.value as "CSV" | "CAMT_053")}
          options={[{ value: "CSV", label: "CSV" }, { value: "CAMT_053", label: "ISO 20022 camt.053 (XML)" }]} />
      </Field>
      <Button type="submit" variant="secondary" disabled={busy}>{busy ? "Preparing…" : "Export"}</Button>
      <ErrorBanner error={error} className="basis-full" />
    </form>
  );
}
