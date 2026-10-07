"use client";

import { useSearchParams } from "next/navigation";
import { useState } from "react";
import { useCustomerId, useMe, useMyAccounts, useMyPayees } from "@/api/me";
import type { PaymentCreate } from "@/api/types";
import { accountName } from "@/components/banking";
import { AMOUNT, PaymentFlow } from "@/components/payment-flow";
import { Card, Empty, ErrorBanner, Field, Input, LinkButton, Loading, Money, Notice, PageHeader, Select, masked } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function PayPayee() {
  const t = useT();
  const c = useCustomerId();
  const params = useSearchParams();
  const me = useMe();
  const accounts = useMyAccounts();
  const payees = useMyPayees();
  const [form, setForm] = useState({ from: "", payee: params.get("payee") ?? "", amount: "", reference: "", date: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});

  if (accounts.isPending || payees.isPending) return <Loading rows={4} />;
  if (accounts.error || payees.error) return <ErrorBanner error={accounts.error ?? payees.error} />;

  const sources = (accounts.data ?? []).filter((a) => a.status === "ACTIVE" && a.can_transact !== false);
  const usable = (payees.data ?? []).filter((p) => p.status === "ACTIVE");
  const payee = usable.find((p) => p.id === form.payee);
  const from = sources.find((a) => a.id === form.from);
  const cooling = payee?.cooling_off_until && new Date(payee.cooling_off_until) > new Date();
  const today = new Date().toISOString().slice(0, 10);

  function build(): PaymentCreate | null {
    const e: Record<string, string> = {};
    if (!from) e.from = t("form.required");
    if (!payee) e.payee = t("form.required");
    if (!AMOUNT.test(form.amount)) e.amount = t("form.amount");
    setErrors(e);
    if (Object.keys(e).length || !from || !payee) return null;
    return {
      payment_type: "DOMESTIC", direction: "OUTBOUND", channel: "WEB", priority: "NORMAL",
      customer_id: c, debtor_account_id: from.id, debtor_name: me.data?.display_name ?? undefined,
      payee_id: payee.id, creditor_name: payee.name, creditor_account_id_type: payee.account_id_type,
      creditor_account_number: payee.account_number, creditor_agent_bic: payee.agent_bic ?? undefined,
      creditor_agent_clearing_id: payee.agent_clearing_id ?? undefined, creditor_country: payee.country,
      amount: form.amount, currency: from.currency, reference: form.reference || undefined,
      remittance_unstructured: form.reference || undefined,
      requested_execution_date: form.date && form.date !== today ? form.date : undefined,
    };
  }

  return (
    <>
      <PageHeader title={t("move.payPayee")} back={{ href: "/move", label: t("nav.move") }} subtitle={t("pay.subtitle")} />
      {usable.length === 0 ? (
        <Empty title={t("payees.none")} action={<LinkButton href="/move/payees">{t("payees.add")}</LinkButton>} />
      ) : (
        <Card>
          <form className="flex max-w-xl flex-col gap-4" noValidate onSubmit={(e) => e.preventDefault()}>
            <Field label={t("transfer.from")} error={errors.from}>
              <Select value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })}
                options={[{ value: "", label: t("form.choose") }, ...sources.map((a) => ({ value: a.id!, label: `${accountName(a)} (${a.currency})` }))]} />
            </Field>
            <Field label={t("pay.payee")} error={errors.payee}>
              <Select value={form.payee} onChange={(e) => setForm({ ...form, payee: e.target.value })}
                options={[{ value: "", label: t("form.choose") },
                  ...usable.map((p) => ({ value: p.id!, label: `${p.nickname || p.name} · ${masked(p.account_number)}` }))]} />
            </Field>
            {cooling && <Notice tone="warn">{t("payees.cooling", { until: new Date(payee!.cooling_off_until!).toLocaleString() })}</Notice>}
            <Field label={t("pay.amount")} hint={from?.currency} error={errors.amount}>
              <Input inputMode="decimal" autoComplete="off" value={form.amount}
                onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
            </Field>
            <Field label={t("pay.reference")} optional>
              <Input maxLength={140} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
            </Field>
            <Field label={t("pay.date")} hint={t("pay.dateHint")} optional>
              <Input type="date" min={today} value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} />
            </Field>
            <PaymentFlow build={build} submitLabel={t("pay.confirm")}
              summary={(b) => [
                { label: t("transfer.from"), value: from ? accountName(from) : "" },
                { label: t("pay.payee"), value: `${b.creditor_name} · ${masked(b.creditor_account_number)}` },
                { label: t("pay.amount"), value: <Money amount={b.amount} currency={b.currency} /> },
                ...(b.reference ? [{ label: t("pay.reference"), value: b.reference }] : []),
                ...(b.requested_execution_date ? [{ label: t("pay.date"), value: b.requested_execution_date }] : []),
              ]} />
          </form>
        </Card>
      )}
    </>
  );
}
