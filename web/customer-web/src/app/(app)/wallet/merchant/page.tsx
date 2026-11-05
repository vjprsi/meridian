"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { createMerchantPayment, useWalletContext } from "@/api/me";
import type { WalletTxnResult } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker, WalletReceipt } from "@/components/wallet-flow";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function MerchantPay() {
  const t = useT();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  const [walletId, setWalletId] = useState("");
  const [form, setForm] = useState({ till: "", amount: "", reference: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<WalletTxnResult | null>(null);

  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;

  const active = walletId || wallet.id || "";

  if (done) {
    return (
      <>
        <PageHeader title={t("wallet.merchant")} back={{ href: "/wallet", label: t("wallet.title") }} />
        <Notice tone="good">{t("wallet.paid")}</Notice>
        <div className="mt-4">
          <WalletReceipt amount={done.amount ?? form.amount} currency={done.currency ?? wallet.currency} status={done.status}
            items={[
              { label: t("wallet.till"), value: form.till },
              { label: t("wallet.reference"), value: done.reference ?? form.reference ?? "—" },
            ]} />
        </div>
      </>
    );
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!form.till.trim()) er.till = t("form.required");
    if (!AMOUNT.test(form.amount)) er.amount = t("form.amount");
    setErrors(er);
    if (Object.keys(er).length === 0) setConfirm(true);
  }

  return (
    <>
      <PageHeader title={t("wallet.merchant")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.merchantSubtitle")} />
      <Card className="max-w-xl">
        <form className="flex flex-col gap-4" noValidate onSubmit={submit}>
          <WalletPicker wallets={wallets} value={active} onChange={setWalletId} />
          <Field label={t("wallet.till")} hint={t("wallet.tillHint")} error={errors.till}>
            <Input inputMode="numeric" autoComplete="off" value={form.till}
              onChange={(e) => setForm({ ...form, till: e.target.value.trim() })} />
          </Field>
          <Field label={t("wallet.amount")} hint={wallet.currency} error={errors.amount}>
            <Input inputMode="decimal" autoComplete="off" value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
          </Field>
          <Field label={t("wallet.reference")} optional>
            <Input maxLength={140} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
          </Field>
          <Button type="submit">{t("pay.review")}</Button>
        </form>
      </Card>

      <MoneyConfirm open={confirm} onClose={() => setConfirm(false)} title={t("wallet.confirmMerchant")} confirmLabel={t("wallet.pay")}
        items={[
          { label: t("wallet.till"), value: form.till },
          { label: t("wallet.amount"), value: <Money amount={form.amount} currency={wallet.currency} /> },
          ...(form.reference ? [{ label: t("wallet.reference"), value: form.reference }] : []),
        ]}
        run={(key) => createMerchantPayment({ wallet_id: active, merchant_till: form.till.trim(), amount: form.amount, reference: form.reference || undefined }, key)}
        onDone={(r) => { setConfirm(false); setDone(r); }} />
    </>
  );
}
