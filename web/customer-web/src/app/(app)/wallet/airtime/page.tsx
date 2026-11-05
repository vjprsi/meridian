"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { createAirtimeTopup, useBillers, useWalletContext } from "@/api/me";
import type { AirtimeTopup, Biller, Wallet } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker, WalletReceipt } from "@/components/wallet-flow";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function Airtime() {
  const t = useT();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;
  return <AirtimeForm key={wallet.id} wallet={wallet} wallets={wallets} />;
}

function AirtimeForm({ wallet, wallets }: { wallet: Wallet; wallets: Wallet[] }) {
  const t = useT();
  const operators = useBillers("AIRTIME");
  const [walletId, setWalletId] = useState(wallet.id ?? "");
  const [billerId, setBillerId] = useState("");
  const [productId, setProductId] = useState("");
  const [msisdn, setMsisdn] = useState(wallet.msisdn ?? "");
  const [amount, setAmount] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<AirtimeTopup | null>(null);

  const biller: Biller | undefined = (operators.data ?? []).find((b) => b.id === billerId);
  const product = biller?.products?.find((p) => p.id === productId);
  const fixed = product?.amount ?? null;
  const effectiveAmount = fixed ?? amount;

  if (done) {
    return (
      <>
        <PageHeader title={t("wallet.airtime")} back={{ href: "/wallet", label: t("wallet.title") }} />
        <Notice tone="good">{t("wallet.airtimeDone")}</Notice>
        <div className="mt-4">
          <WalletReceipt amount={done.amount ?? effectiveAmount} currency={done.currency ?? wallet.currency} status={done.status}
            items={[
              { label: t("wallet.operator"), value: done.biller_name ?? biller?.name ?? "—" },
              { label: t("wallet.msisdn"), value: done.msisdn ?? msisdn },
              { label: t("wallet.reference"), value: done.reference ?? "—" },
            ]} />
        </div>
      </>
    );
  }

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!billerId) er.biller = t("form.required");
    if (!/^\+?\d{6,15}$/.test(msisdn.trim())) er.msisdn = t("wallet.msisdnInvalid");
    if (!fixed && !AMOUNT.test(amount)) er.amount = t("form.amount");
    setErrors(er);
    if (Object.keys(er).length === 0) setConfirm(true);
  }

  return (
    <>
      <PageHeader title={t("wallet.airtime")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.airtimeSubtitle")} />
      <Card className="max-w-xl">
        <ErrorBanner error={operators.error} className="mb-3" />
        <form className="flex flex-col gap-4" noValidate onSubmit={submit}>
          <WalletPicker wallets={wallets} value={walletId} onChange={setWalletId} />
          <Field label={t("wallet.operator")} error={errors.biller}>
            <Select value={billerId} disabled={operators.isPending}
              onChange={(e) => { setBillerId(e.target.value); setProductId(""); }}
              options={[{ value: "", label: t("form.choose") }, ...(operators.data ?? []).map((b) => ({ value: b.id, label: b.name }))]} />
          </Field>
          {biller?.products && biller.products.length > 0 && (
            <Field label={t("wallet.bundle")} optional>
              <Select value={productId} onChange={(e) => setProductId(e.target.value)}
                options={[{ value: "", label: t("wallet.customAmount") },
                  ...biller.products.map((p) => ({ value: p.id, label: p.amount ? `${p.name} · ${p.amount} ${p.currency ?? wallet.currency}` : p.name }))]} />
            </Field>
          )}
          <Field label={t("wallet.msisdn")} hint={t("wallet.msisdnHint")} error={errors.msisdn}>
            <Input inputMode="tel" autoComplete="tel" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} />
          </Field>
          {!fixed && (
            <Field label={t("wallet.amount")} hint={wallet.currency} error={errors.amount}>
              <Input inputMode="decimal" autoComplete="off" value={amount}
                onChange={(e) => setAmount(e.target.value.replace(",", ".").trim())} />
            </Field>
          )}
          <Button type="submit">{t("pay.review")}</Button>
        </form>
      </Card>

      <MoneyConfirm open={confirm} onClose={() => setConfirm(false)} title={t("wallet.confirmAirtime")} confirmLabel={t("wallet.buy")}
        items={[
          { label: t("wallet.operator"), value: biller?.name ?? "—" },
          ...(product ? [{ label: t("wallet.bundle"), value: product.name }] : []),
          { label: t("wallet.msisdn"), value: msisdn },
          { label: t("wallet.amount"), value: <Money amount={effectiveAmount} currency={wallet.currency} /> },
        ]}
        run={(key) => createAirtimeTopup({ wallet_id: walletId, biller_id: billerId, biller_product_id: productId || undefined, msisdn: msisdn.trim(), amount: fixed ? undefined : amount }, key)}
        onDone={(r) => { setConfirm(false); setDone(r); }} />
    </>
  );
}
