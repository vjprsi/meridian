"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useWalletContext, walletLookup, walletP2p, walletP2pQuote, type P2pInput } from "@/api/me";
import type { WalletHandleType, WalletLookupResult, WalletQuote, WalletTxnResult } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker, WalletReceipt } from "@/components/wallet-flow";
import { Button, Card, Empty, ErrorBanner, Field, Input, Loading, LinkButton, Money, Notice, PageHeader, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

const HANDLES: WalletHandleType[] = ["MSISDN", "WALLET_NUMBER", "ALIAS"];

export default function SendMoney() {
  const t = useT();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  const [walletId, setWalletId] = useState("");
  const [form, setForm] = useState({ type: "MSISDN" as WalletHandleType, value: "", amount: "", note: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [resolved, setResolved] = useState<WalletLookupResult | null>(null);
  const [quote, setQuote] = useState<WalletQuote | null>(null);
  const [done, setDone] = useState<WalletTxnResult | null>(null);

  const active = walletId || wallet?.id || "";

  const lookup = useMutation({
    mutationFn: () => walletLookup(form.type, form.value.trim()),
    onSuccess: setResolved,
  });
  const quoteM = useMutation({
    mutationFn: (): Promise<WalletQuote> => walletP2pQuote(active, { type: form.type, value: form.value.trim(), amount: form.amount, note: form.note || undefined }),
    onSuccess: setQuote,
  });

  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;

  if (done) {
    return (
      <>
        <PageHeader title={t("wallet.send")} back={{ href: "/wallet", label: t("wallet.title") }} />
        <Notice tone="good">{t("wallet.sent")}</Notice>
        <div className="mt-4">
          <WalletReceipt amount={done.amount ?? form.amount} currency={done.currency ?? wallet.currency} status={done.status}
            items={[
              { label: t("wallet.payee"), value: quote?.payee_name ?? resolved?.display_name ?? form.value },
              { label: t("wallet.reference"), value: done.reference ?? "—" },
              ...(form.note ? [{ label: t("wallet.note"), value: form.note }] : []),
            ]} />
        </div>
      </>
    );
  }

  const input: P2pInput = { type: form.type, value: form.value.trim(), amount: form.amount, note: form.note || undefined };

  function review() {
    const e: Record<string, string> = {};
    if (!form.value.trim()) e.value = t("form.required");
    if (!AMOUNT.test(form.amount)) e.amount = t("form.amount");
    setErrors(e);
    if (Object.keys(e).length === 0) quoteM.mutate();
  }

  return (
    <>
      <PageHeader title={t("wallet.send")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.sendSubtitle")} />
      <Card className="max-w-xl">
        <form className="flex flex-col gap-4" noValidate onSubmit={(e) => e.preventDefault()}>
          <WalletPicker wallets={wallets} value={active} onChange={(id) => { setWalletId(id); setQuote(null); }} />
          <Field label={t("wallet.sendTo")}>
            <Select value={form.type} onChange={(e) => { setForm({ ...form, type: e.target.value as WalletHandleType }); setResolved(null); setQuote(null); }}
              options={HANDLES.map((h) => ({ value: h, label: t(`wallet.handle.${h}`) }))} />
          </Field>
          <Field label={t(`wallet.handle.${form.type}`)} error={errors.value}>
            <div className="flex gap-2">
              <Input value={form.value} autoComplete="off" spellCheck={false} inputMode={form.type === "MSISDN" ? "tel" : "text"}
                onChange={(e) => { setForm({ ...form, value: e.target.value }); setResolved(null); setQuote(null); }} />
              <Button variant="secondary" disabled={!form.value.trim() || lookup.isPending} onClick={() => lookup.mutate()}>
                {lookup.isPending ? t("common.working") : t("wallet.check")}
              </Button>
            </div>
          </Field>
          {resolved && (
            <Notice tone={resolved.wallet_id ? "good" : "warn"}>
              {resolved.wallet_id ? t("wallet.resolvedAs", { name: resolved.display_name ?? resolved.masked_name ?? "—" }) : t("wallet.notResolved")}
            </Notice>
          )}
          <ErrorBanner error={lookup.error} />
          <Field label={t("wallet.amount")} hint={wallet.currency} error={errors.amount}>
            <Input inputMode="decimal" autoComplete="off" value={form.amount}
              onChange={(e) => { setForm({ ...form, amount: e.target.value.replace(",", ".").trim() }); setQuote(null); }} />
          </Field>
          <Field label={t("wallet.note")} optional>
            <Input maxLength={140} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          </Field>
          <ErrorBanner error={quoteM.error} />
          <Button type="submit" onClick={(e) => { e.preventDefault(); review(); }} disabled={quoteM.isPending}>
            {quoteM.isPending ? t("common.working") : t("pay.review")}
          </Button>
        </form>
      </Card>

      <MoneyConfirm open={!!quote} onClose={() => setQuote(null)} title={t("wallet.confirmSend")} confirmLabel={t("wallet.send")}
        warning={(quote?.warnings ?? []).map((w) => w.message).join(" ") || undefined}
        items={quote ? [
          { label: t("wallet.payee"), value: quote.payee_name ?? resolved?.display_name ?? form.value },
          { label: t("wallet.amount"), value: <Money amount={quote.amount} currency={quote.currency ?? wallet.currency} /> },
          ...(quote.fee ? [{ label: t("wallet.fee"), value: <Money amount={quote.fee} currency={quote.currency ?? wallet.currency} /> }] : []),
          ...(quote.total_debit ? [{ label: t("wallet.totalDebit"), value: <Money amount={quote.total_debit} currency={quote.currency ?? wallet.currency} /> }] : []),
          ...(form.note ? [{ label: t("wallet.note"), value: form.note }] : []),
        ] : []}
        run={(key) => walletP2p(active, { ...input, quote_id: quote?.quote_id }, key)}
        onDone={(r) => { setQuote(null); setDone(r); }} />
      <div className="mt-4"><LinkButton href="/wallet" variant="ghost">{t("wallet.cancel")}</LinkButton></div>
    </>
  );
}
