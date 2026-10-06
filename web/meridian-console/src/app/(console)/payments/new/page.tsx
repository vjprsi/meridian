"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { call } from "@/api/client";
import { Badge, Button, Card, ErrorBanner, Field, Input, Money, PageHeader, Select } from "@/components/ui";
import { Json, type Row } from "@/components/ops";
import { ACCOUNT_ID_TYPES, CHANNELS, CHARGE_BEARERS, DIRECTIONS, PAYMENT_TYPES, type Payment } from "../shared";

const EMPTY = {
  payment_type: "DOMESTIC", direction: "OUTBOUND", channel: "BRANCH", priority: "NORMAL",
  customer_id: "", debtor_account_id: "", debtor_name: "",
  creditor_name: "", creditor_account_id_type: "IBAN", creditor_account_number: "", creditor_agent_bic: "",
  creditor_country: "", creditor_account_id: "", payee_id: "",
  amount: "", currency: "USD", charge_bearer: "SHAR", fx_quote_id: "",
  reference: "", remittance_unstructured: "", purpose_code: "", requested_execution_date: "",
};
type Form = typeof EMPTY;

type Validation = Row & {
  valid?: boolean;
  errors?: { code: string; message: string }[];
  warnings?: { code: string; message: string }[];
  fee?: { amount?: string; currency?: string };
  total_debit?: string;
  currency?: string;
  execution_date?: string;
  approval_levels?: number;
  route?: Row;
  payee_verification?: Row;
};

/** Builds the PaymentCreate body: empty optional fields are omitted (the contract rejects empty strings for uuids). */
function toBody(f: Form): Record<string, unknown> {
  const body: Record<string, unknown> = Object.fromEntries(Object.entries(f).filter(([, v]) => v !== ""));
  // An internal transfer credits an internal account; the external creditor identifiers do not apply.
  if (f.payment_type === "INTERNAL_TRANSFER") {
    delete body.creditor_account_id_type;
    if (!f.creditor_account_number) delete body.creditor_agent_bic;
  } else if (!f.creditor_account_number) {
    delete body.creditor_account_id_type;
  }
  return body;
}

function NewPayment() {
  const router = useRouter();
  const params = useSearchParams();
  const qc = useQueryClient();
  const [form, setForm] = useState<Form>({ ...EMPTY,
    customer_id: params.get("customer") ?? "", debtor_account_id: params.get("account") ?? "" });
  const set = (k: keyof Form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  const validate = useMutation({
    mutationFn: () => call<Validation>("POST", "/v1/payments/validate", { payment: toBody(form) }),
  });
  const create = useMutation({
    mutationFn: () => call<Payment>("POST", "/v1/payments", toBody(form)),
    onSuccess: (p) => { qc.invalidateQueries({ queryKey: ["payments"] }); router.push(`/payments/${p.id}`); },
  });
  const internal = form.payment_type === "INTERNAL_TRANSFER";
  const crossBorder = form.payment_type === "CROSS_BORDER";
  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate(); };
  const v = validate.data;

  return (
    <>
      <PageHeader title="New payment" subtitle="Initiate a payment on behalf of a customer. It is screened, limit-checked and routed by the payment service." />
      <form onSubmit={submit} className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <ErrorBanner error={create.error ?? validate.error} />
          <Card title="Payment">
            <Grid>
              <Field label="Type"><Select options={[...PAYMENT_TYPES]} value={form.payment_type} onChange={set("payment_type")} /></Field>
              <Field label="Direction"><Select options={[...DIRECTIONS]} value={form.direction} onChange={set("direction")} /></Field>
              <Field label="Channel"><Select options={[...CHANNELS]} value={form.channel} onChange={set("channel")} /></Field>
              <Field label="Amount"><Input required inputMode="decimal" pattern="^\d+(\.\d{1,8})?$" placeholder="0.00"
                value={form.amount} onChange={set("amount")} /></Field>
              <Field label="Currency (ISO 4217)"><Input required maxLength={3} minLength={3} value={form.currency}
                onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} /></Field>
              <Field label="Priority"><Select options={["NORMAL", "HIGH", "URGENT"]} value={form.priority} onChange={set("priority")} /></Field>
              <Field label="Requested execution date" hint="Empty = as soon as possible">
                <Input type="date" value={form.requested_execution_date} onChange={set("requested_execution_date")} /></Field>
              {crossBorder && <>
                <Field label="Charge bearer"><Select options={[...CHARGE_BEARERS]} value={form.charge_bearer} onChange={set("charge_bearer")} /></Field>
                <Field label="FX quote ID" hint="Locked quote from the FX service"><Input value={form.fx_quote_id} onChange={set("fx_quote_id")} /></Field>
              </>}
            </Grid>
          </Card>
          <Card title="Debtor">
            <Grid>
              <Field label="Customer ID"><Input value={form.customer_id} onChange={set("customer_id")} placeholder="UUID" /></Field>
              <Field label="Debit account ID" hint="Internal deposit account"><Input value={form.debtor_account_id}
                onChange={set("debtor_account_id")} placeholder="UUID" /></Field>
              <Field label="Debtor name"><Input value={form.debtor_name} onChange={set("debtor_name")} /></Field>
            </Grid>
          </Card>
          <Card title="Creditor">
            <Grid>
              {internal ? (
                <Field label="Credit account ID" hint="Internal deposit account"><Input required value={form.creditor_account_id}
                  onChange={set("creditor_account_id")} placeholder="UUID" /></Field>
              ) : <>
                <Field label="Saved payee ID" hint="Or enter the creditor details below"><Input value={form.payee_id}
                  onChange={set("payee_id")} placeholder="UUID" /></Field>
                <Field label="Account ID type"><Select options={[...ACCOUNT_ID_TYPES]} value={form.creditor_account_id_type}
                  onChange={set("creditor_account_id_type")} /></Field>
                <Field label="Account number / IBAN"><Input value={form.creditor_account_number}
                  onChange={(e) => setForm({ ...form, creditor_account_number: e.target.value.replace(/\s+/g, "").toUpperCase() })} /></Field>
                <Field label="Agent BIC"><Input maxLength={11} value={form.creditor_agent_bic}
                  onChange={(e) => setForm({ ...form, creditor_agent_bic: e.target.value.toUpperCase() })} /></Field>
                <Field label="Country (ISO)"><Input maxLength={2} value={form.creditor_country}
                  onChange={(e) => setForm({ ...form, creditor_country: e.target.value.toUpperCase() })} /></Field>
              </>}
              <Field label="Creditor name"><Input required={!internal && !form.payee_id} value={form.creditor_name} onChange={set("creditor_name")} /></Field>
            </Grid>
          </Card>
          <Card title="Remittance">
            <Grid>
              <Field label="Reference"><Input maxLength={35} value={form.reference} onChange={set("reference")} /></Field>
              <Field label="Remittance information"><Input maxLength={140} value={form.remittance_unstructured}
                onChange={set("remittance_unstructured")} /></Field>
              <Field label="Purpose code (ISO 20022)"><Input maxLength={4} value={form.purpose_code}
                onChange={(e) => setForm({ ...form, purpose_code: e.target.value.toUpperCase() })} /></Field>
            </Grid>
          </Card>
          <div className="flex gap-2">
            <Button type="button" variant="secondary" disabled={validate.isPending || !form.amount}
              onClick={() => validate.mutate()}>{validate.isPending ? "Validating…" : "Validate & price"}</Button>
            <Button type="submit" disabled={create.isPending}>{create.isPending ? "Submitting…" : "Create payment"}</Button>
            <Button type="button" variant="ghost" onClick={() => router.push("/payments")}>Cancel</Button>
          </div>
        </div>
        <div>
          <Card title="Pre-validation">
            {!v ? (
              <p className="text-sm text-muted">Validate to check accounts, route, cut-off, fees, FX and Confirmation of Payee
                without creating the payment.</p>
            ) : (
              <div className="space-y-3 text-sm">
                <Badge value={v.valid ? "PASSED" : "FAILED"} />
                {(v.errors ?? []).map((e) => <Msg key={e.code} tone="bad" code={e.code}>{e.message}</Msg>)}
                {(v.warnings ?? []).map((w) => <Msg key={w.code} tone="warn" code={w.code}>{w.message}</Msg>)}
                {v.fee && <p>Fee <Money amount={v.fee.amount} currency={v.fee.currency} /> · total debit{" "}
                  <Money amount={v.total_debit} currency={v.currency} /></p>}
                {v.execution_date && <p>Executes on <span className="num">{v.execution_date}</span></p>}
                {v.approval_levels !== undefined && <p>Approval levels required: <span className="num">{v.approval_levels}</span></p>}
                {v.payee_verification && <p>Confirmation of Payee: <Badge value={String(v.payee_verification.result)} />
                  {v.payee_verification.matched_name ? <> · {String(v.payee_verification.matched_name)}</> : null}</p>}
                {v.route && <details><summary className="cursor-pointer text-muted">Route</summary><Json value={v.route} /></details>}
              </div>
            )}
          </Card>
        </div>
      </form>
    </>
  );
}

function Grid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-1 gap-4 md:grid-cols-3">{children}</div>;
}

function Msg({ tone, code, children }: { tone: "bad" | "warn"; code: string; children: ReactNode }) {
  return (
    <p className={tone === "bad" ? "rounded bg-bad-soft p-2 text-xs text-bad" : "rounded bg-warn-soft p-2 text-xs text-warn"}>
      <span className="font-mono">{code}</span> · {children}
    </p>
  );
}

export default function Page() {
  return <Suspense><NewPayment /></Suspense>;
}
