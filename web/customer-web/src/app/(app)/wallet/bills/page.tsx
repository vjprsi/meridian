"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import {
  billInquire, createBillPayment, useBillers, useWalletContext, validateBillerReference,
} from "@/api/me";
import type { BillInquiry, BillPayment, Biller, ReferenceValidation } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker, WalletReceipt } from "@/components/wallet-flow";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Bills() {
  const t = useT();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  const billersQ = useBillers();

  const [walletId, setWalletId] = useState("");
  const [billerId, setBillerId] = useState("");
  const [reference, setReference] = useState("");
  const [amount, setAmount] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [validated, setValidated] = useState<ReferenceValidation | null>(null);
  const [inquiry, setInquiry] = useState<BillInquiry | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<BillPayment | null>(null);

  const active = walletId || wallet?.id || "";

  const validate = useMutation({
    mutationFn: () => validateBillerReference(billerId, reference.trim()),
    onSuccess: setValidated,
  });
  const inquire = useMutation({
    mutationFn: () => billInquire({ wallet_id: active, biller_id: billerId, reference: reference.trim() }),
    onSuccess: (r) => { setInquiry(r); if (r.amount) setAmount(r.amount); },
  });

  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;

  const billers: Biller[] = (billersQ.data ?? []).filter((b) => (b.biller_type ?? "").toUpperCase() !== "AIRTIME");
  const biller = billers.find((b) => b.id === billerId);
  const refLabel = biller?.reference_label || t("wallet.billReference");
  const amountFixed = inquiry?.amount != null && inquiry.amount_editable === false;

  if (done) {
    return (
      <>
        <PageHeader title={t("wallet.bills")} back={{ href: "/wallet", label: t("wallet.title") }} />
        <Notice tone="good">{t("wallet.billDone")}</Notice>
        <div className="mt-4">
          <WalletReceipt amount={done.amount ?? amount} currency={done.currency ?? wallet.currency} status={done.status}
            items={[
              { label: t("wallet.biller"), value: done.biller_name ?? biller?.name ?? "—" },
              { label: refLabel, value: done.bill_reference ?? reference },
              { label: t("wallet.reference"), value: done.reference ?? "—" },
            ]} />
        </div>
      </>
    );
  }

  function reset() { setValidated(null); setInquiry(null); setAmount(""); }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!billerId) er.biller = t("form.required");
    if (!reference.trim()) er.reference = t("form.required");
    if (!AMOUNT.test(amount)) er.amount = t("form.amount");
    setErrors(er);
    if (Object.keys(er).length === 0) setConfirm(true);
  }

  return (
    <>
      <PageHeader title={t("wallet.bills")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.billsSubtitle")} />
      <Card className="max-w-xl">
        <ErrorBanner error={billersQ.error} className="mb-3" />
        <form className="flex flex-col gap-4" noValidate onSubmit={submit}>
          <WalletPicker wallets={wallets} value={active} onChange={setWalletId} />
          <Field label={t("wallet.biller")} error={errors.biller}>
            <Select value={billerId} disabled={billersQ.isPending}
              onChange={(e) => { setBillerId(e.target.value); reset(); }}
              options={[{ value: "", label: t("form.choose") }, ...billers.map((b) => ({ value: b.id, label: b.name }))]} />
          </Field>
          <Field label={refLabel} hint={biller?.reference_pattern ? undefined : t("wallet.billReferenceHint")} error={errors.reference}>
            <div className="flex gap-2">
              <Input value={reference} autoComplete="off" spellCheck={false}
                onChange={(e) => { setReference(e.target.value); reset(); }} />
              <Button variant="secondary" disabled={!billerId || !reference.trim() || validate.isPending} onClick={() => validate.mutate()}>
                {validate.isPending ? t("common.working") : t("wallet.check")}
              </Button>
            </div>
          </Field>
          {validated && (
            <Notice tone={validated.valid === false ? "warn" : "good"}>
              {validated.valid === false ? (validated.message ?? t("wallet.refInvalid"))
                : t("wallet.refValid", { name: validated.customer_name ?? "—" })}
            </Notice>
          )}
          <ErrorBanner error={validate.error} />
          {biller?.supports_inquiry && (
            <div>
              <Button variant="secondary" disabled={!billerId || !reference.trim() || inquire.isPending} onClick={() => inquire.mutate()}>
                {inquire.isPending ? t("common.working") : t("wallet.lookupBill")}
              </Button>
              <ErrorBanner error={inquire.error} className="mt-2" />
              {inquiry && (
                <Notice tone="info">
                  {t("wallet.billDue", { name: inquiry.customer_name ?? "—" })}
                  {inquiry.amount ? ` · ` : ""}
                  {inquiry.amount ? <Money amount={inquiry.amount} currency={inquiry.currency ?? wallet.currency} /> : null}
                  {inquiry.due_date ? ` · ${inquiry.due_date}` : ""}
                </Notice>
              )}
            </div>
          )}
          <Field label={t("wallet.amount")} hint={wallet.currency} error={errors.amount}>
            <Input inputMode="decimal" autoComplete="off" value={amount} readOnly={amountFixed}
              onChange={(e) => setAmount(e.target.value.replace(",", ".").trim())} />
          </Field>
          <Button type="submit">{t("pay.review")}</Button>
        </form>
      </Card>

      <MoneyConfirm open={confirm} onClose={() => setConfirm(false)} title={t("wallet.confirmBill")} confirmLabel={t("wallet.payBill")}
        items={[
          { label: t("wallet.biller"), value: biller?.name ?? "—" },
          { label: refLabel, value: reference },
          ...(validated?.customer_name ? [{ label: t("wallet.accountName"), value: validated.customer_name }] : []),
          { label: t("wallet.amount"), value: <Money amount={amount} currency={wallet.currency} /> },
        ]}
        run={(key) => createBillPayment({ wallet_id: active, biller_id: billerId, reference: reference.trim(), amount, bill_inquiry_id: inquiry?.bill_inquiry_id }, key)}
        onDone={(r) => { setConfirm(false); setDone(r); }} />
    </>
  );
}
