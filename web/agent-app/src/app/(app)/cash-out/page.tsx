"use client";

import { useCallback, useState, type FormEvent } from "react";
import { idem, newKey, problemOf, unwrap, wallet, type Quote, type Wallet, type WalletTransaction } from "@/api/client";
import { Scanner, canScan } from "@/components/scanner";
import { Badge, Button, Card, DateTime, Field, Input, LinkButton, Money, Notice, PageTitle, PinInput, ProblemBox, Row, cx } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { isMsisdn, toMsisdn } from "@/lib/config";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { isAmount } from "@/lib/money";
import type { Problem } from "@/lib/outbox/types";

/**
 * Cash-out needs live verification and is never queued offline. The customer's funds are held when the cash-out is
 * created (walletTransactionsCashOut) and captured only when the customer confirms with the wallet PIN, or with the
 * OTP sent to their phone when the wallet has no PIN (walletTransactionsConfirm). A pending cash-out started by
 * the customer can be found by its reference (typed or scanned).
 */
export default function CashOutPage() {
  const t = useT();
  const p = useProfile();
  const conn = useConnectivity();
  const [mode, setMode] = useState<"new" | "code">("new");
  const [txn, setTxn] = useState<WalletTransaction | null>(null);

  if (!p) return null;
  if (conn === "offline") {
    return (
      <div className="flex flex-col gap-4">
        <PageTitle>{t("cashOut")}</PageTitle>
        <Notice tone="bad"><span data-testid="cashout-offline">{t("cashOutOffline")}</span></Notice>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("cashOut")}</PageTitle>
      {!txn && (
        <div role="tablist" className="grid grid-cols-2 gap-2">
          {(["new", "code"] as const).map((m) => (
            <button key={m} role="tab" aria-selected={mode === m} onClick={() => setMode(m)}
              className={cx("min-h-12 rounded-[var(--radius)] border-2 font-semibold",
                mode === m ? "border-accent bg-accent-soft text-accent" : "border-border bg-surface")}>
              {m === "new" ? t("newCashOut") : t("enterCode")}
            </button>
          ))}
        </div>
      )}
      {!txn && mode === "new" && <NewCashOut onCreated={setTxn} />}
      {!txn && mode === "code" && <FindByCode onFound={setTxn} />}
      {txn && <ConfirmCashOut txn={txn} onChange={setTxn} onNew={() => setTxn(null)} />}
    </div>
  );
}

function NewCashOut({ onCreated }: { onCreated: (t: WalletTransaction) => void }) {
  const t = useT();
  const p = useProfile()!;
  const [who, setWho] = useState("");
  const [amount, setAmount] = useState("");
  const [customer, setCustomer] = useState<Wallet | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [key, setKey] = useState(newKey());
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);

  async function review(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    setBusy(true);
    try {
      const phone = toMsisdn(who);
      const query = /^\+?\d{7,15}$/.test(who.replace(/\s/g, "")) && isMsisdn(phone) && !/^[A-Z]/i.test(who)
        ? { msisdn: phone, currency: p.currency } : { wallet_number: who.trim(), currency: p.currency };
      const w = unwrap(await wallet.GET("/v1/wallets/lookup", { params: { query } }));
      setCustomer(w);
      setQuote(unwrap(await wallet.POST("/v1/wallet-transactions/quote", { params: { header: idem() }, body: {
        txn_type: "CASH_OUT", amount, currency: p.currency, payer_wallet_id: w.id, payee_wallet_id: p.floatWalletId ?? undefined, channel: "AGENT_APP",
      } })) as unknown as Quote);
      setKey(newKey());
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  async function create() {
    setBusy(true);
    setProblem(null);
    try {
      // One key per review: a double tap or a retry after a lost response cannot create a second hold.
      onCreated(unwrap(await wallet.POST("/v1/wallet-transactions/cash-out", { params: { header: idem(key) }, body: {
        agent_wallet_id: p.floatWalletId!, agent_outlet_id: p.outletId ?? undefined, payer_wallet_id: customer!.id,
        amount, currency: p.currency, channel: "AGENT_APP",
      } })));
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  if (customer && quote) {
    return (
      <div className="flex flex-col gap-4">
        <Card>
          <dl>
            <Row label={t("customer")}>{customer.display_name}<br /><span className="text-sm text-muted">{customer.msisdn ?? customer.wallet_number}</span></Row>
            <Row label={t("amount")}><Money amount={amount} currency={p.currency} /></Row>
            <Row label={t("fee")}><Money amount={quote.fee_amount} currency={p.currency} /></Row>
            <Row label={t("total")}><Money amount={quote.total_debit_amount} currency={p.currency} /></Row>
          </dl>
        </Card>
        {!quote.limits_ok && <Notice tone="bad">{t("limitsExceeded")}: {quote.limit_violation}</Notice>}
        <ProblemBox problem={problem} />
        <div className="grid grid-cols-2 gap-2">
          <Button variant="secondary" onClick={() => { setCustomer(null); setQuote(null); }}>{t("back")}</Button>
          <Button onClick={create} disabled={busy || !quote.limits_ok}>{t("continue")}</Button>
        </div>
      </div>
    );
  }

  return (
    <form onSubmit={review} className="flex flex-col gap-4">
      <Field label={`${t("customerPhone")} / ${t("walletNumber")}`}>
        <Input autoComplete="off" value={who} onChange={(e) => setWho(e.target.value)} required />
      </Field>
      <Field label={`${t("amount")} (${p.currency})`}>
        <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} required />
      </Field>
      <ProblemBox problem={problem} />
      <Button type="submit" block disabled={busy || !p.floatWalletId}>{t("getQuote")}</Button>
    </form>
  );
}

function FindByCode({ onFound }: { onFound: (t: WalletTransaction) => void }) {
  const t = useT();
  const [code, setCode] = useState("");
  const [scan, setScan] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);

  const find = useCallback(async (ref: string) => {
    setBusy(true);
    setProblem(null);
    try {
      const rows = unwrap(await wallet.GET("/v1/wallet-transactions", { params: { query: { reference: ref.trim(), txn_type: "CASH_OUT", limit: 5 } } })).data;
      if (rows.length === 0) setProblem({ title: t("nothing"), detail: ref });
      else onFound(rows[0]);
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }, [onFound, t]);

  const onScan = useCallback((v: string) => { setScan(false); setCode(v); void find(v); }, [find]);

  return (
    <form onSubmit={(e) => { e.preventDefault(); void find(code); }} className="flex flex-col gap-4">
      {scan ? <Scanner onResult={onScan} onClose={() => setScan(false)} /> : (
        <Field label={t("code")}>
          <Input autoComplete="off" autoCapitalize="characters" value={code} onChange={(e) => setCode(e.target.value)} required />
        </Field>
      )}
      <ProblemBox problem={problem} />
      <div className={cx("grid gap-2", canScan() ? "grid-cols-2" : "grid-cols-1")}>
        {canScan() && <Button type="button" variant="secondary" onClick={() => setScan(true)}>{t("scan")}</Button>}
        <Button type="submit" disabled={busy || !code}>{t("findPending")}</Button>
      </div>
    </form>
  );
}

function ConfirmCashOut({ txn, onChange, onNew }: { txn: WalletTransaction; onChange: (t: WalletTransaction) => void; onNew: () => void }) {
  const t = useT();
  const [secret, setSecret] = useState("");
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const method = txn.confirmation_method;
  const pending = txn.status === "PENDING_CONFIRMATION";

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setProblem(null);
    const value = secret;
    setSecret(""); // the customer's PIN leaves memory as soon as it is sent
    try {
      onChange(unwrap(await wallet.POST("/v1/wallet-transactions/{id}/confirm", {
        params: { path: { id: txn.id }, header: idem() },
        body: method === "OTP" ? { otp: value } : { pin: value },
      })));
    } catch (x) {
      setProblem(problemOf(x));
      const fresh = await wallet.GET("/v1/wallet-transactions/{id}", { params: { path: { id: txn.id } } });
      if (fresh.data) onChange(fresh.data);
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    setBusy(true);
    try {
      onChange(unwrap(await wallet.POST("/v1/wallet-transactions/{id}/cancel", {
        params: { path: { id: txn.id }, header: idem() }, body: { reason: "Cancelled at agent outlet" } })));
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4" data-testid="cashout-confirm">
      <Card>
        <dl>
          <Row label={t("reference")}><span className="font-mono">{txn.reference}</span></Row>
          <Row label={t("status")}><Badge value={txn.status} /></Row>
          <Row label={t("amount")}><Money amount={txn.amount} currency={txn.currency} /></Row>
          <Row label={t("fee")}><Money amount={txn.fee_amount} currency={txn.currency} /></Row>
          {txn.confirmation_expires_at && pending && <Row label="⏱"><DateTime value={txn.confirmation_expires_at} /></Row>}
        </dl>
      </Card>
      {pending && (
        <form onSubmit={confirm} className="flex flex-col gap-3">
          <Field label={method === "OTP" ? t("otp") : t("customerPin")} hint={method === "OTP" ? t("otpHint") : t("customerPinHint")}>
            <PinInput autoFocus value={secret} onChange={(e) => setSecret(e.target.value.replace(/\D/g, ""))} required />
          </Field>
          <ProblemBox problem={problem} />
          <div className="grid grid-cols-2 gap-2">
            <Button type="button" variant="danger" onClick={cancel} disabled={busy}>{t("cancel")}</Button>
            <Button type="submit" disabled={busy || !secret}>{t("confirm")}</Button>
          </div>
        </form>
      )}
      {txn.status === "COMPLETED" && (
        <>
          <Notice tone="good">{t("completed")} — {t("handCash")}</Notice>
          <LinkButton variant="secondary" href={`/receipt?kind=wallet&id=${txn.id}`}>{t("receipt")}</LinkButton>
        </>
      )}
      {!pending && txn.status !== "COMPLETED" && <ProblemBox problem={problem ?? { title: txn.status, detail: txn.failure_reason ?? undefined }} />}
      {!pending && <Button onClick={onNew}>{t("newOperation")}</Button>}
    </div>
  );
}
