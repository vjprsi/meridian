"use client";

import { useState, type FormEvent } from "react";
import { idem, problemOf, unwrap, wallet, type Quote, type Wallet } from "@/api/client";
import { OpOutcome } from "@/components/receipt";
import { Button, Card, Field, Input, Money, Notice, PageTitle, PinInput, ProblemBox, Row } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { isMsisdn, toMsisdn } from "@/lib/config";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { isAmount } from "@/lib/money";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { submit } from "@/lib/outbox/runtime";
import type { OutboxView, Problem } from "@/lib/outbox/types";
import { useFloatWallet } from "@/lib/queries";

/**
 * Cash-in: the customer hands over cash, the agent's float wallet sends the same e-value to the customer's wallet
 * (walletsLookup → walletTransactionsQuote → walletTransactionsCashIn). Offline, the operation is queued within the
 * offline limits; the phone number is typed twice because the name cannot be confirmed.
 */
export default function CashInPage() {
  const t = useT();
  const p = useProfile();
  const conn = useConnectivity();
  const floatWallet = useFloatWallet(p);
  const [phone, setPhone] = useState("");
  const [phone2, setPhone2] = useState("");
  const [amount, setAmount] = useState("");
  const [payee, setPayee] = useState<Wallet | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [pin, setPin] = useState("");
  const [stage, setStage] = useState<"form" | "review" | "done">("form");
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OutboxView | null>(null);
  const offline = conn === "offline";

  if (!p) return null;
  const msisdn = toMsisdn(phone);
  const currency = p.currency;

  function reset() {
    setPhone(""); setPhone2(""); setAmount(""); setPayee(null); setQuote(null); setPin("");
    setProblem(null); setResult(null); setStage("form");
  }

  async function review(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    if (!isMsisdn(msisdn)) { setProblem({ detail: `${t("customerPhone")}: ${msisdn}` }); return; }
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    if (offline) {
      if (toMsisdn(phone2) !== msisdn) { setProblem({ detail: t("phonesDiffer") }); return; }
      setStage("review");
      return;
    }
    setBusy(true);
    try {
      const w = unwrap(await wallet.GET("/v1/wallets/lookup", { params: { query: { msisdn, currency } } }));
      setPayee(w);
      const q = unwrap(await wallet.POST("/v1/wallet-transactions/quote", {
        params: { header: idem() },
        body: { txn_type: "CASH_IN", amount, currency, payer_wallet_id: p!.floatWalletId ?? undefined, payee_msisdn: msisdn, channel: "AGENT_APP" },
      })) as unknown as Quote;
      setQuote(q);
      setStage("review");
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      const view = await submit("CASH_IN", {
        request: { agent_wallet_id: p!.floatWalletId, agent_outlet_id: p!.outletId ?? undefined, payee_msisdn: msisdn,
          amount, currency, channel: "AGENT_APP" },
        amount, currency,
        display: { Customer: payee ? `${payee.display_name} ${msisdn}` : msisdn },
      }, { pin: pin || undefined, offline });
      setResult(view);
      setStage("done");
    } catch (x) {
      setProblem(x instanceof OfflineLimitError ? { title: t("limitsExceeded"), code: x.code, detail: x.message } : problemOf(x));
    } finally {
      setPin("");
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("cashIn")}</PageTitle>
      {!p.floatWalletId && <Notice tone="bad">No float wallet on this agent profile.</Notice>}

      {stage === "form" && (
        <form onSubmit={review} className="flex flex-col gap-4">
          <Field label={t("customerPhone")} hint={phone ? msisdn : undefined}>
            <Input type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} required />
          </Field>
          {offline && (
            <Field label={t("confirmPhone")}>
              <Input type="tel" inputMode="tel" autoComplete="off" value={phone2} onChange={(e) => setPhone2(e.target.value)} required />
            </Field>
          )}
          <Field label={`${t("amount")} (${currency})`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} required />
          </Field>
          <ProblemBox problem={problem} />
          <Button type="submit" block disabled={busy || !p.floatWalletId}>{offline ? t("continue") : t("getQuote")}</Button>
        </form>
      )}

      {stage === "review" && (
        <form onSubmit={confirm} className="flex flex-col gap-4">
          <Card>
            <dl>
              <Row label={t("customer")}>{payee?.display_name ?? "—"}<br /><span className="text-sm text-muted">{msisdn}</span></Row>
              <Row label={t("amount")}><Money amount={amount} currency={currency} /></Row>
              {quote ? (
                <>
                  <Row label={t("fee")}><Money amount={quote.fee_amount} currency={currency} /></Row>
                  <Row label={t("tax")}><Money amount={quote.tax_amount} currency={currency} /></Row>
                  <Row label={t("total")}><Money amount={quote.total_debit_amount} currency={currency} /></Row>
                </>
              ) : <p className="pt-2 text-sm text-muted">{t("quoteUnavailable")}</p>}
            </dl>
          </Card>
          {quote && !quote.limits_ok && <Notice tone="bad">{t("limitsExceeded")}: {quote.limit_violation}</Notice>}
          {offline && <Notice tone="warn">{t("savedOffline")}</Notice>}
          <Notice>{t("collectCash")}</Notice>
          {!offline && floatWallet.data?.has_pin && (
            <Field label={t("agentPin")} hint={t("agentPinHint")}>
              <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required />
            </Field>
          )}
          <ProblemBox problem={problem} />
          <div className="grid grid-cols-2 gap-2">
            <Button type="button" variant="secondary" onClick={() => setStage("form")}>{t("back")}</Button>
            <Button type="submit" disabled={busy || (quote ? !quote.limits_ok : false)}>{t("confirm")}</Button>
          </div>
        </form>
      )}

      {stage === "done" && result && (
        <OpOutcome view={result} onNew={reset} serverReceipt={(b) => (b.id ? `/receipt?kind=wallet&id=${String(b.id)}` : undefined)} />
      )}
    </div>
  );
}
