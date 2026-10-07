"use client";

import { useState } from "react";
import { useMyAccounts } from "@/api/me";
import type { MyPayment } from "@/api/types";
import { accountName } from "@/components/banking";
import { AMOUNT, PaymentFlow } from "@/components/payment-flow";
import { Async, Card, Empty, Field, Input, LinkButton, Money, PageHeader, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function OwnTransfer() {
  const t = useT();
  const accounts = useMyAccounts();
  const [form, setForm] = useState({ from: "", to: "", amount: "", reference: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});

  return (
    <>
      <PageHeader title={t("move.ownTransfer")} back={{ href: "/move", label: t("nav.move") }} subtitle={t("transfer.subtitle")} />
      <Async q={accounts}
        isEmpty={(l) => l.filter((a) => a.status === "ACTIVE").length < 2}
        empty={<Empty title={t("transfer.needTwo")} action={<LinkButton href="/" variant="secondary">{t("nav.home")}</LinkButton>} />}>
        {(list) => {
          const active = list.filter((a) => a.status === "ACTIVE");
          const from = active.find((a) => a.id === form.from);
          const sources = active.filter((a) => a.can_transact !== false);
          const targets = active.filter((a) => a.id !== form.from && (!from || a.currency === from.currency));
          const name = (id: string) => { const a = active.find((x) => x.id === id); return a ? accountName(a) : ""; };

          function build(): MyPayment | null {
            const e: Record<string, string> = {};
            if (!form.from) e.from = t("form.required");
            if (!form.to) e.to = t("form.required");
            if (!AMOUNT.test(form.amount)) e.amount = t("form.amount");
            setErrors(e);
            if (Object.keys(e).length || !from) return null;
            return { kind: "OWN_TRANSFER", from_account_id: form.from, to_account_id: form.to, amount: form.amount,
              reference: form.reference || undefined, channel: "WEB" };
          }

          return (
            <Card>
              <form className="flex max-w-xl flex-col gap-4" noValidate onSubmit={(e) => e.preventDefault()}>
                <Field label={t("transfer.from")} error={errors.from}>
                  <Select value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value, to: "" })}
                    options={[{ value: "", label: t("form.choose") }, ...sources.map((a) => ({ value: a.id!, label: `${accountName(a)} (${a.currency})` }))]} />
                </Field>
                <Field label={t("transfer.to")} error={errors.to}>
                  <Select value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} disabled={!form.from}
                    options={[{ value: "", label: t("form.choose") }, ...targets.map((a) => ({ value: a.id!, label: `${accountName(a)} (${a.currency})` }))]} />
                </Field>
                <Field label={t("pay.amount")} hint={from ? from.currency : undefined} error={errors.amount}>
                  <Input inputMode="decimal" autoComplete="off" value={form.amount}
                    onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
                </Field>
                <Field label={t("pay.reference")} optional>
                  <Input maxLength={140} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
                </Field>
                <PaymentFlow build={build} submitLabel={t("transfer.confirm")}
                  summary={(b) => [
                    { label: t("transfer.from"), value: name(b.from_account_id) },
                    { label: t("transfer.to"), value: name(b.to_account_id ?? "") },
                    { label: t("pay.amount"), value: <Money amount={b.amount} currency={from?.currency} /> },
                    ...(b.reference ? [{ label: t("pay.reference"), value: b.reference }] : []),
                  ]} />
              </form>
            </Card>
          );
        }}
      </Async>
    </>
  );
}
