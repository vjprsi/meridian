"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { qrDecode, qrGenerate, qrPay, useWalletContext } from "@/api/me";
import type { QrCode, QrDecoded, Wallet, WalletTxnResult } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker, WalletReceipt } from "@/components/wallet-flow";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Summary, Textarea, cx } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function WalletQr() {
  const t = useT();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  const [tab, setTab] = useState<"pay" | "mine">("pay");
  const [walletId, setWalletId] = useState("");

  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;

  const active = walletId || wallet.id || "";
  const tabCls = (on: boolean) => cx("min-h-11 rounded-[var(--radius)] px-4 text-sm font-semibold",
    on ? "bg-accent-soft text-accent" : "text-muted hover:bg-surface-2");

  return (
    <>
      <PageHeader title={t("wallet.qr")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.qrSubtitle")} />
      <div role="tablist" aria-label={t("wallet.qr")} className="mb-4 flex gap-2">
        <button role="tab" aria-selected={tab === "pay"} className={tabCls(tab === "pay")} onClick={() => setTab("pay")}>{t("wallet.qrPay")}</button>
        <button role="tab" aria-selected={tab === "mine"} className={tabCls(tab === "mine")} onClick={() => setTab("mine")}>{t("wallet.qrMine")}</button>
      </div>
      {tab === "pay"
        ? <QrPay walletId={active} wallets={wallets} currency={wallet.currency} onWallet={setWalletId} />
        : <QrMine walletId={active} wallets={wallets} currency={wallet.currency} onWallet={setWalletId} />}
    </>
  );
}

function QrPay({ walletId, wallets, currency, onWallet }:
  { walletId: string; wallets: Wallet[]; currency: string; onWallet: (id: string) => void }) {
  const t = useT();
  const [payload, setPayload] = useState("");
  const [amount, setAmount] = useState("");
  const [decoded, setDecoded] = useState<QrDecoded | null>(null);
  const [confirm, setConfirm] = useState(false);
  const [done, setDone] = useState<WalletTxnResult | null>(null);

  const decode = useMutation({
    mutationFn: () => qrDecode(payload.trim()),
    onSuccess: (d) => { setDecoded(d); if (d.amount) setAmount(d.amount); },
  });

  if (done) {
    return (
      <>
        <Notice tone="good">{t("wallet.paid")}</Notice>
        <div className="mt-4">
          <WalletReceipt amount={done.amount ?? amount} currency={done.currency ?? currency} status={done.status}
            items={[
              { label: t("wallet.payee"), value: decoded?.merchant_name ?? decoded?.payee_name ?? "—" },
              { label: t("wallet.reference"), value: done.reference ?? "—" },
            ]} />
        </div>
      </>
    );
  }

  const payeeName = decoded?.merchant_name ?? decoded?.payee_name;
  const amountEditable = !decoded?.amount || decoded.amount_editable !== false;
  const canPay = !!decoded && (!amountEditable || AMOUNT.test(amount));

  return (
    <>
      <Card className="max-w-xl">
        <div className="flex flex-col gap-4">
          <WalletPicker wallets={wallets} value={walletId} onChange={onWallet} />
          <Field label={t("wallet.qrPaste")} hint={t("wallet.qrPasteHint")}>
            <Textarea value={payload} spellCheck={false} onChange={(e) => { setPayload(e.target.value); setDecoded(null); }} />
          </Field>
          <Button variant="secondary" disabled={!payload.trim() || decode.isPending} onClick={() => decode.mutate()}>
            {decode.isPending ? t("common.working") : t("wallet.qrDecode")}
          </Button>
          <ErrorBanner error={decode.error} />
          {decoded && (
            <div className="rounded-[var(--radius)] border border-border p-4">
              <Summary items={[
                { label: t("wallet.payee"), value: payeeName ?? "—" },
                ...(decoded.merchant_till ? [{ label: t("wallet.till"), value: decoded.merchant_till }] : []),
                ...(decoded.type ? [{ label: t("wallet.qrType"), value: decoded.type.toLowerCase() }] : []),
              ]} />
              {amountEditable ? (
                <div className="mt-4">
                  <Field label={t("wallet.amount")} hint={currency}>
                    <Input inputMode="decimal" autoComplete="off" value={amount}
                      onChange={(e) => setAmount(e.target.value.replace(",", ".").trim())} />
                  </Field>
                </div>
              ) : (
                <p className="mt-3"><Money amount={decoded.amount} currency={decoded.currency ?? currency} className="text-lg font-semibold" /></p>
              )}
              <Button className="mt-4" disabled={!canPay} onClick={() => setConfirm(true)}>{t("pay.review")}</Button>
            </div>
          )}
        </div>
      </Card>

      <MoneyConfirm open={confirm} onClose={() => setConfirm(false)} title={t("wallet.confirmPay")} confirmLabel={t("wallet.pay")}
        items={[
          { label: t("wallet.payee"), value: payeeName ?? "—" },
          { label: t("wallet.amount"), value: <Money amount={amountEditable ? amount : decoded?.amount} currency={decoded?.currency ?? currency} /> },
        ]}
        run={(key) => qrPay({ wallet_id: walletId, payload: payload.trim(), amount: amountEditable ? amount : undefined }, key)}
        onDone={(r) => { setConfirm(false); setDone(r); }} />
    </>
  );
}

function QrMine({ walletId, wallets, currency, onWallet }:
  { walletId: string; wallets: Wallet[]; currency: string; onWallet: (id: string) => void }) {
  const t = useT();
  const [amount, setAmount] = useState("");
  const [reference, setReference] = useState("");
  const [code, setCode] = useState<QrCode | null>(null);

  const generate = useMutation({
    mutationFn: () => qrGenerate({ wallet_id: walletId, amount: amount && AMOUNT.test(amount) ? amount : undefined, reference: reference || undefined }, crypto.randomUUID()),
    onSuccess: setCode,
  });

  return (
    <Card className="max-w-xl">
      <div className="flex flex-col gap-4">
        <WalletPicker wallets={wallets} value={walletId} onChange={onWallet} />
        <Field label={t("wallet.amount")} hint={t("wallet.qrAmountHint")} optional>
          <Input inputMode="decimal" autoComplete="off" value={amount}
            onChange={(e) => { setAmount(e.target.value.replace(",", ".").trim()); setCode(null); }} />
        </Field>
        <Field label={t("wallet.reference")} optional>
          <Input maxLength={64} value={reference} onChange={(e) => { setReference(e.target.value); setCode(null); }} />
        </Field>
        <Button disabled={generate.isPending} onClick={() => generate.mutate()}>
          {generate.isPending ? t("common.working") : t("wallet.qrGenerate")}
        </Button>
        <ErrorBanner error={generate.error} />
        {code && (
          <div className="flex flex-col items-center gap-3 rounded-[var(--radius)] border border-border p-6 text-center">
            {code.image_url
              // eslint-disable-next-line @next/next/no-img-element
              ? <img src={code.image_url} alt={t("wallet.qrMine")} width={200} height={200} className="h-48 w-48" />
              : <p className="break-all rounded-[var(--radius)] bg-surface-2 p-4 font-mono text-xs">{code.payload}</p>}
            {code.amount && <Money amount={code.amount} currency={code.currency ?? currency} className="text-lg font-semibold" />}
            <p className="text-sm text-muted">{t("wallet.qrShow")}</p>
          </div>
        )}
      </div>
    </Card>
  );
}
