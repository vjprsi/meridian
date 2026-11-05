"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { idem } from "@/api/client";
import {
  cancelMoneyRequest, createMoneyRequest, declineMoneyRequest, payMoneyRequest, qk, useCustomerId,
  useMoneyRequests, useWalletContext,
} from "@/api/me";
import type { MoneyRequest, WalletHandleType } from "@/api/types";
import { AMOUNT } from "@/components/payment-flow";
import { MoneyConfirm, WalletPicker } from "@/components/wallet-flow";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Loading, Money, PageHeader, Select,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

const HANDLES: WalletHandleType[] = ["MSISDN", "WALLET_NUMBER", "ALIAS"];

export default function RequestMoney() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const preferred = useSearchParams().get("wallet") ?? undefined;
  const { isPending, error, wallets, wallet } = useWalletContext(preferred);
  const list = useMoneyRequests();

  const [walletId, setWalletId] = useState("");
  const [form, setForm] = useState({ type: "MSISDN" as WalletHandleType, value: "", amount: "", note: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmCreate, setConfirmCreate] = useState(false);
  const [paying, setPaying] = useState<MoneyRequest | null>(null);

  const refresh = () => qc.invalidateQueries({ queryKey: qk.moneyRequests(c) });
  const active = walletId || wallet?.id || "";

  const decline = useMutation({
    mutationFn: (id: string) => declineMoneyRequest(id, idem()["Idempotency-Key"]),
    onSuccess: refresh,
  });
  const cancel = useMutation({
    mutationFn: (id: string) => cancelMoneyRequest(id, idem()["Idempotency-Key"]),
    onSuccess: refresh,
  });

  if (isPending) return <Loading rows={4} />;
  if (error) return <ErrorBanner error={error} />;
  if (!wallet) return <Empty title={t("wallet.none")}>{t("wallet.noneHint")}</Empty>;

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!form.value.trim()) er.value = t("form.required");
    if (!AMOUNT.test(form.amount)) er.amount = t("form.amount");
    setErrors(er);
    if (Object.keys(er).length === 0) setConfirmCreate(true);
  }

  const isIncoming = (r: MoneyRequest) => r.direction === "INCOMING";
  const pending = (r: MoneyRequest) => (r.status ?? "PENDING") === "PENDING";

  return (
    <>
      <PageHeader title={t("wallet.request")} back={{ href: "/wallet", label: t("wallet.title") }} subtitle={t("wallet.requestSubtitle")} />
      <div className="grid gap-5 lg:grid-cols-[1fr_24rem]">
        <Card title={t("wallet.requestsList")}>
          <ErrorBanner error={decline.error ?? cancel.error} className="mb-3" />
          <Async q={list} empty={<Empty title={t("wallet.noRequests")} />}>
            {(rows) => (
              <ul className="divide-y divide-border">
                {rows.map((r) => (
                  <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <span className="min-w-0">
                      <span className="block font-medium">
                        {isIncoming(r) ? t("wallet.fromParty", { name: r.requester_name ?? r.counterparty_name ?? r.requester_msisdn ?? "—" })
                          : t("wallet.toParty", { name: r.counterparty_name ?? r.counterparty_msisdn ?? "—" })}
                      </span>
                      <span className="block text-xs text-muted">
                        <DateTime value={r.created_at} /> · <Badge value={r.status ?? "PENDING"} />
                        {r.note ? ` · ${r.note}` : ""}
                      </span>
                    </span>
                    <span className="flex items-center gap-2">
                      <Money amount={r.amount} currency={r.currency ?? wallet.currency} className="font-semibold" />
                      {pending(r) && isIncoming(r) && (
                        <>
                          <Button onClick={() => setPaying(r)}>{t("wallet.pay")}</Button>
                          <Button variant="ghost" disabled={decline.isPending} onClick={() => decline.mutate(r.id)}>{t("wallet.decline")}</Button>
                        </>
                      )}
                      {pending(r) && !isIncoming(r) && (
                        <Button variant="ghost" disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)}>{t("wallet.cancelRequest")}</Button>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Async>
        </Card>

        <Card title={t("wallet.requestNew")}>
          <form className="flex flex-col gap-4" noValidate onSubmit={submit}>
            <WalletPicker wallets={wallets} value={active} onChange={setWalletId} />
            <Field label={t("wallet.requestFrom")}>
              <Select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as WalletHandleType })}
                options={HANDLES.map((h) => ({ value: h, label: t(`wallet.handle.${h}`) }))} />
            </Field>
            <Field label={t(`wallet.handle.${form.type}`)} error={errors.value}>
              <Input value={form.value} autoComplete="off" spellCheck={false}
                onChange={(e) => setForm({ ...form, value: e.target.value })} />
            </Field>
            <Field label={t("wallet.amount")} hint={wallet.currency} error={errors.amount}>
              <Input inputMode="decimal" autoComplete="off" value={form.amount}
                onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
            </Field>
            <Field label={t("wallet.note")} optional>
              <Input maxLength={140} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            </Field>
            <Button type="submit">{t("wallet.requestSubmit")}</Button>
          </form>
        </Card>
      </div>

      <MoneyConfirm open={confirmCreate} onClose={() => setConfirmCreate(false)} title={t("wallet.requestNew")}
        confirmLabel={t("wallet.requestSubmit")}
        items={[
          { label: t("wallet.requestFrom"), value: form.value },
          { label: t("wallet.amount"), value: <Money amount={form.amount} currency={wallet.currency} /> },
          ...(form.note ? [{ label: t("wallet.note"), value: form.note }] : []),
        ]}
        run={(key) => createMoneyRequest({ wallet_id: active, type: form.type, value: form.value.trim(), amount: form.amount, note: form.note || undefined }, key)}
        onDone={() => { setConfirmCreate(false); setForm({ ...form, value: "", amount: "", note: "" }); refresh(); }} />

      <MoneyConfirm open={!!paying} onClose={() => setPaying(null)} title={t("wallet.payRequest")} confirmLabel={t("wallet.pay")}
        items={paying ? [
          { label: t("wallet.payee"), value: paying.requester_name ?? paying.counterparty_name ?? "—" },
          { label: t("wallet.amount"), value: <Money amount={paying.amount} currency={paying.currency ?? wallet.currency} /> },
          { label: t("wallet.payFrom"), value: wallet.display_name },
          ...(paying.note ? [{ label: t("wallet.note"), value: paying.note }] : []),
        ] : []}
        run={(key) => payMoneyRequest(paying!.id, active, key)}
        onDone={() => { setPaying(null); refresh(); }} />
    </>
  );
}
