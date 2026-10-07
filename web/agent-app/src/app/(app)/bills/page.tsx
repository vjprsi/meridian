"use client";

import { useState, type FormEvent } from "react";
import { api, idem, problemOf, unwrap, type BillInquiry } from "@/api/client";
import { OpOutcome } from "@/components/receipt";
import { Button, Field, Input, Money, Notice, PageTitle, PinInput, ProblemBox, Row, Select, Spinner } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { checkReference, useBillerProducts, useBillers } from "@/lib/billers";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { cmp, isAmount } from "@/lib/money";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { submit } from "@/lib/outbox/runtime";
import type { OutboxView, Problem } from "@/lib/outbox/types";
import { useFloatWallet } from "@/lib/queries";

/**
 * Bill payment for a walk-in customer, paid from the agent's float (billersList → billersValidateReference →
 * billPaymentsCreate). Offline it is queued only for billers whose reference the device can validate itself.
 */
export default function BillsPage() {
  const t = useT();
  const p = useProfile();
  const conn = useConnectivity();
  const offline = conn === "offline";
  const billers = useBillers();
  const floatWallet = useFloatWallet(p);
  const [billerId, setBillerId] = useState("");
  const [productId, setProductId] = useState("");
  const [ref, setRef] = useState("");
  const [amount, setAmount] = useState("");
  const [name, setName] = useState("");
  const [pin, setPin] = useState("");
  const [inquiry, setInquiry] = useState<BillInquiry | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OutboxView | null>(null);
  const products = useBillerProducts(billerId || undefined);

  if (!p) return null;
  const list = (billers.data ?? []).filter((b) => b.category !== "AIRTIME" && b.currency === p.currency);
  const b = list.find((x) => x.id === billerId);
  const check = b ? checkReference(b, ref) : null;

  function reset() {
    setRef(""); setAmount(""); setName(""); setPin(""); setInquiry(null); setProblem(null); setResult(null);
  }

  async function validate() {
    if (!b) return;
    setBusy(true);
    setProblem(null);
    try {
      const inq = unwrap(await api.POST("/v1/agent/billers/{biller_id}/validate-reference", {
        params: { path: { biller_id: b.id }, header: idem() }, body: { account_reference: ref.trim() } }));
      setInquiry(inq);
      if (inq.customer_name) setName(inq.customer_name);
      if (inq.amount_due && !amount) setAmount(inq.amount_due);
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  async function pay(e: FormEvent) {
    e.preventDefault();
    if (!b) return;
    setProblem(null);
    if (check?.error) { setProblem({ title: t("accountReference"), code: check.error }); return; }
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    if ((b.min_amount && cmp(amount, b.min_amount) < 0) || (b.max_amount && cmp(amount, b.max_amount) > 0)) {
      setProblem({ title: t("limitsExceeded"), detail: `${b.min_amount ?? 0} – ${b.max_amount ?? "∞"}` });
      return;
    }
    if (offline && !check?.offlineCapable) {
      setProblem({ title: t("offline"), detail: `${b.name}: ${t("validate")} — online only` });
      return;
    }
    setBusy(true);
    try {
      const view = await submit("BILL_PAYMENT", {
        // paid from the agent's float wallet; payer, currency and channel are set by the agent channel
        request: { biller_id: b.id, biller_product_id: productId || undefined, bill_inquiry_id: inquiry?.id,
          account_reference: ref.trim(), customer_name: name || undefined, amount },
        amount, currency: p!.currency,
        display: { Biller: b.name, Account: ref.trim(), ...(name ? { Customer: name } : {}) },
      }, { pin: pin || undefined, offline });
      setResult(view);
    } catch (x) {
      setProblem(x instanceof OfflineLimitError ? { title: t("limitsExceeded"), code: x.code, detail: x.message } : problemOf(x));
    } finally {
      setPin("");
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("bills")}</PageTitle>
      {result ? (
        <OpOutcome view={result} onNew={reset} serverReceipt={(r) => (r.id ? `/receipt?kind=bill&id=${String(r.id)}` : undefined)} />
      ) : (
        <form onSubmit={pay} className="flex flex-col gap-4">
          {billers.isLoading && <Spinner />}
          {billers.error && <ProblemBox problem={problemOf(billers.error)} />}
          <Field label={t("biller")}>
            <Select value={billerId} onChange={(e) => { setBillerId(e.target.value); setProductId(""); setInquiry(null); }} required
              options={[{ value: "", label: "—" }, ...list.map((x) => ({ value: x.id, label: `${x.name} (${x.category})` }))]} />
          </Field>
          {b && (products.data?.length ?? 0) > 0 && (
            <Field label={t("product")}>
              <Select value={productId} onChange={(e) => setProductId(e.target.value)}
                options={[{ value: "", label: "—" }, ...products.data!.filter((x) => x.is_active).map((x) => ({ value: x.id, label: x.name }))]} />
            </Field>
          )}
          {b && (
            <>
              <Field label={b.account_reference_label ?? t("accountReference")}
                error={ref && check?.error ? `${t("accountReference")}: ${check.error}` : null}>
                <Input autoComplete="off" value={ref} onChange={(e) => { setRef(e.target.value); setInquiry(null); }} required />
              </Field>
              {!offline && b.supports_inquiry && (
                <Button type="button" variant="secondary" onClick={validate} disabled={busy || !ref || !!check?.error}>{t("validate")}</Button>
              )}
              {inquiry && (
                <dl className="rounded-[var(--radius)] border border-border bg-surface p-3">
                  <Row label={t("customerName")}>{inquiry.customer_name ?? "—"}</Row>
                  <Row label={t("amountDue")}><Money amount={inquiry.amount_due} currency={inquiry.currency ?? p.currency} /></Row>
                  {inquiry.response_message && <Row label={t("status")}>{inquiry.response_message}</Row>}
                </dl>
              )}
              {offline && !check?.offlineCapable && <Notice tone="warn">{b.name}: online only</Notice>}
              <Field label={t("customerName")}>
                <Input autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
              <Field label={`${t("amount")} (${p.currency})`} hint={b.min_amount || b.max_amount ? `${b.min_amount ?? 0} – ${b.max_amount ?? "∞"}` : undefined}>
                <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} required />
              </Field>
              <Notice>{t("collectCash")}</Notice>
              {!offline && floatWallet.data?.has_pin && (
                <Field label={t("agentPin")} hint={t("agentPinHint")}>
                  <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required />
                </Field>
              )}
            </>
          )}
          <ProblemBox problem={problem} />
          <Button type="submit" block disabled={busy || !b}>{t("confirm")}</Button>
        </form>
      )}
    </div>
  );
}
