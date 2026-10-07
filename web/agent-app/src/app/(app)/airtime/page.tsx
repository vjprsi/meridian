"use client";

import { useState, type FormEvent } from "react";
import { problemOf } from "@/api/client";
import { OpOutcome } from "@/components/receipt";
import { Button, Field, Input, Notice, PageTitle, PinInput, ProblemBox, Select, Spinner, cx } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useBillerProducts, useBillers } from "@/lib/billers";
import { isMsisdn, toMsisdn } from "@/lib/config";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { cmp, isAmount } from "@/lib/money";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { submit } from "@/lib/outbox/runtime";
import type { OutboxView, Problem } from "@/lib/outbox/types";
import { useFloatWallet } from "@/lib/queries";

/** Airtime top-up for a walk-in customer, paid from the agent's float (billersList → airtimeTopupsCreate). */
export default function AirtimePage() {
  const t = useT();
  const p = useProfile();
  const offline = useConnectivity() === "offline";
  const billers = useBillers();
  const floatWallet = useFloatWallet(p);
  const [billerId, setBillerId] = useState("");
  const [productId, setProductId] = useState("");
  const [phone, setPhone] = useState("");
  const [phone2, setPhone2] = useState("");
  const [amount, setAmount] = useState("");
  const [pin, setPin] = useState("");
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OutboxView | null>(null);
  const products = useBillerProducts(billerId || undefined);

  if (!p) return null;
  const list = (billers.data ?? []).filter((b) => (b.category === "AIRTIME" || b.category === "DATA") && b.currency === p.currency);
  const b = list.find((x) => x.id === billerId);
  const denominations = (products.data ?? []).filter((x) => x.is_active && x.denomination);

  async function topUp(e: FormEvent) {
    e.preventDefault();
    if (!b) return;
    setProblem(null);
    const msisdn = toMsisdn(phone);
    if (!isMsisdn(msisdn)) { setProblem({ detail: `${t("targetPhone")}: ${msisdn}` }); return; }
    if (offline && toMsisdn(phone2) !== msisdn) { setProblem({ detail: t("phonesDiffer") }); return; }
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    if ((b.min_amount && cmp(amount, b.min_amount) < 0) || (b.max_amount && cmp(amount, b.max_amount) > 0)) {
      setProblem({ title: t("limitsExceeded"), detail: `${b.min_amount ?? 0} – ${b.max_amount ?? "∞"}` });
      return;
    }
    setBusy(true);
    try {
      setResult(await submit("AIRTIME", {
        // paid from the agent's float wallet; payer, currency and channel are set by the agent channel
        request: { biller_id: b.id, biller_product_id: productId || undefined, target_msisdn: msisdn, amount },
        amount, currency: p!.currency,
        display: { Network: b.name, Phone: msisdn },
      }, { pin: pin || undefined, offline }));
    } catch (x) {
      setProblem(x instanceof OfflineLimitError ? { title: t("limitsExceeded"), code: x.code, detail: x.message } : problemOf(x));
    } finally {
      setPin("");
      setBusy(false);
    }
  }

  function reset() {
    setPhone(""); setPhone2(""); setAmount(""); setProductId(""); setResult(null); setProblem(null);
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("airtime")}</PageTitle>
      {result ? (
        <OpOutcome view={result} onNew={reset}
          serverReceipt={(r) => (r.wallet_transaction_id ? `/receipt?kind=wallet&id=${String(r.wallet_transaction_id)}` : undefined)} />
      ) : (
        <form onSubmit={topUp} className="flex flex-col gap-4">
          {billers.isLoading && <Spinner />}
          {billers.error && <ProblemBox problem={problemOf(billers.error)} />}
          <Field label={t("biller")}>
            <Select value={billerId} onChange={(e) => { setBillerId(e.target.value); setProductId(""); }} required
              options={[{ value: "", label: "—" }, ...list.map((x) => ({ value: x.id, label: x.name }))]} />
          </Field>
          <Field label={t("targetPhone")} hint={phone ? toMsisdn(phone) : undefined}>
            <Input type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} required />
          </Field>
          {offline && (
            <Field label={t("confirmPhone")}>
              <Input type="tel" inputMode="tel" autoComplete="off" value={phone2} onChange={(e) => setPhone2(e.target.value)} required />
            </Field>
          )}
          {denominations.length > 0 && (
            <div className="grid grid-cols-3 gap-2">
              {denominations.map((d) => (
                <button type="button" key={d.id} onClick={() => { setProductId(d.id); setAmount(d.denomination!); }}
                  className={cx("min-h-12 rounded-[var(--radius)] border-2 font-semibold", productId === d.id ? "border-accent bg-accent-soft" : "border-border bg-surface")}>
                  {d.denomination}
                </button>
              ))}
            </div>
          )}
          <Field label={`${t("amount")} (${p.currency})`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => { setAmount(e.target.value.replace(",", ".")); setProductId(""); }} required />
          </Field>
          <Notice>{t("collectCash")}</Notice>
          {!offline && floatWallet.data?.has_pin && (
            <Field label={t("agentPin")} hint={t("agentPinHint")}>
              <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required />
            </Field>
          )}
          <ProblemBox problem={problem} />
          <Button type="submit" block disabled={busy || !b}>{t("confirm")}</Button>
        </form>
      )}
    </div>
  );
}
