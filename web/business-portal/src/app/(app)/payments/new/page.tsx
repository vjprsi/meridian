"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { accountOptions, useAccounts, usePayees, usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { FxQuote, PaymentKind } from "@/api/types";
import { useRun } from "@/components/step-up";
import {
  Button, Card, DateTime, ErrorBanner, Field, Input, LinkButton, Money, Notice, PageHeader, Select, Summary,
} from "@/components/ui";

type Kind = Extract<PaymentKind, "OWN_TRANSFER" | "DOMESTIC" | "INTERNATIONAL">;

export default function NewPaymentPage() {
  const router = useRouter();
  const params = useSearchParams();
  const perms = usePermissions();
  const accounts = useAccounts();
  const payees = usePayees();
  const initialKind = (params.get("kind") as Kind | null) ?? "DOMESTIC";
  const [kind, setKind] = useState<Kind>(initialKind);
  const [debtor, setDebtor] = useState(params.get("from") ?? "");
  const [creditorAccount, setCreditorAccount] = useState("");
  const [payeeId, setPayeeId] = useState(params.get("payee") ?? "");
  const [amount, setAmount] = useState("");
  const [buyCurrency, setBuyCurrency] = useState("");
  const [reference, setReference] = useState("");
  const [remittance, setRemittance] = useState("");
  const [purpose, setPurpose] = useState("");
  const [executionDate, setExecutionDate] = useState("");
  const [quote, setQuote] = useState<FxQuote | null>(null);
  const quoteRun = useRun();
  const create = useRun();

  const initiable = accountOptions(accounts.data, (a) => perms.initiateOn(a.id));
  const debtorAccount = accounts.data?.find((a) => a.id === debtor);
  const payee = payees.data?.find((p) => p.id === payeeId);
  const maxAmount = perms.entitlement(debtor)?.max_amount;
  const targetCurrency = buyCurrency || payee?.currency || "";

  async function getQuote() {
    if (!debtorAccount || !amount || !targetCurrency) return;
    quoteRun.newKey();
    const r = await quoteRun.run(async (key) => unwrap(await api.POST("/v1/business/fx-quotes", {
      params: { header: idem(key) },
      body: { sell_currency: debtorAccount.currency, buy_currency: targetCurrency, sell_amount: amount } })));
    if (r.ok) setQuote(r.data);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await create.run(async (key) => unwrap(await api.POST("/v1/business/payments", {
      params: { header: idem(key) },
      body: {
        kind, debtor_account_id: debtor, amount,
        creditor_account_id: kind === "OWN_TRANSFER" ? creditorAccount : undefined,
        payee_id: kind !== "OWN_TRANSFER" ? payeeId : undefined,
        fx_quote_id: kind === "INTERNATIONAL" ? quote?.id : undefined,
        reference: reference || undefined, remittance: remittance || undefined, purpose_code: purpose || undefined,
        requested_execution_date: executionDate || undefined,
      },
    })));
    if (r.ok) router.push(`/payments/${r.data.id}`);
  }

  if (perms.loaded && !perms.canInitiate) {
    return (
      <>
        <PageHeader title="New payment" back={{ href: "/payments", label: "Payments" }} />
        <Notice tone="warn">You are not entitled to initiate payments. Ask an administrator of your business.</Notice>
      </>
    );
  }

  const needsQuote = kind === "INTERNATIONAL";
  const quoteMatches = !!quote && quote.sell_amount !== null && Number(quote.sell_amount) === Number(amount)
    && quote.sell_currency === debtorAccount?.currency;

  return (
    <>
      <PageHeader title="New payment" subtitle="Drafts need to be submitted and approved according to your approval rules"
        back={{ href: "/payments", label: "Payments" }} />
      <Card className="max-w-2xl">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <Field label="Kind">
            <Select name="kind" value={kind} onChange={(e) => { setKind(e.target.value as Kind); setQuote(null); }} options={[
              { value: "OWN_TRANSFER", label: "Own transfer (between business accounts)" },
              { value: "DOMESTIC", label: "Domestic payment to a beneficiary" },
              { value: "INTERNATIONAL", label: "International payment (FX) to a beneficiary" },
            ]} />
          </Field>
          <Field label="From account" hint={maxAmount ? `Your maximum per payment on this account: ${maxAmount}` : undefined}>
            <Select name="debtor_account_id" value={debtor} required placeholder="Choose an account"
              onChange={(e) => { setDebtor(e.target.value); setQuote(null); }} options={initiable} />
          </Field>
          {kind === "OWN_TRANSFER" ? (
            <Field label="To account">
              <Select name="creditor_account_id" value={creditorAccount} required placeholder="Choose an account"
                onChange={(e) => setCreditorAccount(e.target.value)} options={accountOptions(accounts.data, (a) => a.id !== debtor)} />
            </Field>
          ) : (
            <Field label="Beneficiary" hint={payees.data?.length === 0 ? "Add a beneficiary first (Beneficiaries)." : undefined}>
              <Select name="payee_id" value={payeeId} required placeholder="Choose a beneficiary"
                onChange={(e) => { setPayeeId(e.target.value); setQuote(null); }}
                options={(payees.data ?? []).map((p) => ({ value: p.id, label: `${p.nickname ?? p.name} · ${p.account_number}${p.currency ? ` (${p.currency})` : ""}` }))} />
            </Field>
          )}
          <Field label={`Amount${debtorAccount ? ` (${debtorAccount.currency})` : ""}`}
            hint={needsQuote ? "The amount debited from your account; the quote sells exactly this amount." : undefined}>
            <Input name="amount" value={amount} onChange={(e) => { setAmount(e.target.value); setQuote(null); }} required
              inputMode="decimal" pattern="[0-9]+(\.[0-9]{1,2})?" />
          </Field>

          {needsQuote && (
            <div className="rounded-[var(--radius)] border border-border p-3">
              <div className="flex flex-wrap items-end gap-3">
                <Field label="Beneficiary receives currency">
                  <Input value={targetCurrency} onChange={(e) => { setBuyCurrency(e.target.value.toUpperCase()); setQuote(null); }}
                    maxLength={3} placeholder="USD" />
                </Field>
                <Button variant="secondary" onClick={getQuote} disabled={!debtorAccount || !amount || !targetCurrency || quoteRun.pending}>
                  {quoteRun.pending ? "Pricing…" : quote ? "Refresh quote" : "Get FX quote"}
                </Button>
              </div>
              <ErrorBanner error={quoteRun.error} className="mt-3" />
              {quote && (
                <div className="mt-3">
                  <Summary cols={4} items={[
                    { label: "You sell", value: <Money amount={quote.sell_amount} currency={quote.sell_currency} /> },
                    { label: "Beneficiary gets", value: <Money amount={quote.buy_amount} currency={quote.buy_currency} /> },
                    { label: "Rate", value: <span className="num">{quote.customer_rate}</span> },
                    { label: "Valid until", value: <DateTime value={quote.expires_at} /> },
                  ]} />
                </div>
              )}
            </div>
          )}

          <Field label="Reference" optional><Input value={reference} onChange={(e) => setReference(e.target.value)} maxLength={35} /></Field>
          <Field label="Remittance information" optional><Input value={remittance} onChange={(e) => setRemittance(e.target.value)} maxLength={140} /></Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Purpose code" optional><Input value={purpose} onChange={(e) => setPurpose(e.target.value)} maxLength={4} /></Field>
            <Field label="Execution date" optional><Input type="date" value={executionDate} onChange={(e) => setExecutionDate(e.target.value)} /></Field>
          </div>

          <ErrorBanner error={create.error} />
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={create.pending || (needsQuote && !quoteMatches)}>
              {create.pending ? "Saving…" : "Create draft"}
            </Button>
            <LinkButton href="/payments" variant="secondary">Cancel</LinkButton>
          </div>
          {needsQuote && !quoteMatches && <p className="text-xs text-muted">Get an FX quote for this amount before creating the draft.</p>}
        </form>
      </Card>
    </>
  );
}
