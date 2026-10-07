"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { deposits, idem, unwrap } from "@/api/client";
import { useMyAccounts, useMyPayees } from "@/api/me";
import type { StandingOrder, StandingOrderCreate } from "@/api/types";
import { accountName } from "@/components/banking";
import { AMOUNT } from "@/components/payment-flow";
import { StepUpConfirm } from "@/components/step-up";
import {
  Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Select, masked,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

const FREQUENCIES: StandingOrder["frequency"][] = ["WEEKLY", "BIWEEKLY", "MONTHLY", "QUARTERLY", "ANNUAL", "ONCE"];

export default function StandingOrders() {
  const t = useT();
  const qc = useQueryClient();
  const accounts = useMyAccounts();
  const payees = useMyPayees();
  const mine = (accounts.data ?? []).filter((a) => a.status === "ACTIVE");
  const ids = mine.map((a) => a.id!);

  const orders = useQuery({
    queryKey: ["standing-orders", ids], enabled: accounts.isSuccess,
    queryFn: async () => {
      const pages = await Promise.all(ids.map(async (id) =>
        unwrap(await deposits.GET("/v1/standing-orders", { params: { query: { deposit_account_id: id, limit: 100 } } }))
          .data.filter((o) => o.deposit_account_id === id)));
      return pages.flat().filter((o) => o.status !== "CANCELLED" && o.status !== "COMPLETED");
    },
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ["standing-orders"] });

  const [tomorrow] = useState(() => new Date(Date.now() + 86_400_000).toISOString().slice(0, 10));
  const [form, setForm] = useState({ from: "", target: "", amount: "", frequency: "MONTHLY" as StandingOrder["frequency"],
    start_date: tomorrow, end_date: "", reference: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [review, setReview] = useState<StandingOrderCreate | null>(null);
  const [cancelling, setCancelling] = useState<StandingOrder | null>(null);
  const [key, setKey] = useState("");

  const create = useMutation({
    mutationFn: async () => unwrap(await deposits.POST("/v1/standing-orders", { params: { header: idem(key) }, body: review! })),
    onSuccess: () => { setReview(null); setForm({ ...form, amount: "", reference: "" }); refresh(); },
  });
  const action = useMutation({
    mutationFn: async ({ id, op }: { id: string; op: "pause" | "resume" | "cancel" }) => {
      const opts = { params: { path: { id }, header: idem() } };
      if (op === "pause") return unwrap(await deposits.POST("/v1/standing-orders/{id}/pause", { ...opts, body: { reason: "Customer request" } }));
      if (op === "resume") return unwrap(await deposits.POST("/v1/standing-orders/{id}/resume", opts));
      return unwrap(await deposits.POST("/v1/standing-orders/{id}/cancel", { ...opts, body: { reason: "Customer request" } }));
    },
    onSuccess: () => { setCancelling(null); refresh(); },
  });

  const from = mine.find((a) => a.id === form.from);
  const payeeList = (payees.data ?? []).filter((p) => p.status === "ACTIVE");
  const targetLabel = (o: Pick<StandingOrder, "payee_id" | "beneficiary_account_id">) => {
    if (o.payee_id) { const p = payeeList.find((x) => x.id === o.payee_id); return p ? (p.nickname || p.name) : masked(o.payee_id); }
    const a = mine.find((x) => x.id === o.beneficiary_account_id);
    return a ? accountName(a) : "—";
  };

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const er: Record<string, string> = {};
    if (!from) er.from = t("form.required");
    if (!form.target) er.target = t("form.required");
    if (!AMOUNT.test(form.amount)) er.amount = t("form.amount");
    if (!form.start_date) er.start_date = t("form.required");
    setErrors(er);
    if (Object.keys(er).length || !from) return;
    const [kind, id] = form.target.split(":");
    setKey(crypto.randomUUID());
    setReview({
      deposit_account_id: from.id!, amount: form.amount, currency: from.currency, frequency: form.frequency,
      start_date: form.start_date, end_date: form.end_date || undefined, reference: form.reference || undefined,
      ...(kind === "payee" ? { payee_id: id } : { beneficiary_account_id: id }),
    });
  }

  return (
    <>
      <PageHeader title={t("nav.standingOrders")} back={{ href: "/move", label: t("nav.move") }} subtitle={t("so.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-[1fr_24rem]">
        <Card title={t("so.active")}>
          <ErrorBanner error={action.error} className="mb-3" />
          {accounts.isPending || orders.isPending ? <Loading /> : accounts.error || orders.error ? (
            <ErrorBanner error={accounts.error ?? orders.error} />
          ) : (orders.data ?? []).length === 0 ? <Empty title={t("so.none")} /> : (
            <ul className="divide-y divide-border">
              {orders.data!.map((o) => (
                <li key={o.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <span>
                    <span className="block font-medium">{targetLabel(o)}</span>
                    <span className="block text-sm"><Money amount={o.amount} currency={o.currency} /> · {t(`freq.${o.frequency}`)}</span>
                    <span className="block text-xs text-muted">
                      {t("so.next")} <DateTime value={o.next_execution_date} dateOnly /> · <Badge value={o.status} />
                    </span>
                  </span>
                  <span className="flex gap-2">
                    {o.status === "ACTIVE" && <Button variant="secondary" disabled={action.isPending}
                      onClick={() => action.mutate({ id: o.id!, op: "pause" })}>{t("so.pause")}</Button>}
                    {(o.status === "PAUSED" || o.status === "FAILED_SUSPENDED") && <Button variant="secondary" disabled={action.isPending}
                      onClick={() => action.mutate({ id: o.id!, op: "resume" })}>{t("so.resume")}</Button>}
                    <Button variant="ghost" onClick={() => setCancelling(o)}>{t("common.cancel")}</Button>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title={t("so.new")}>
          <form onSubmit={submit} noValidate className="flex flex-col gap-4">
            <Field label={t("transfer.from")} error={errors.from}>
              <Select value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })}
                options={[{ value: "", label: t("form.choose") }, ...mine.filter((a) => a.can_transact !== false)
                  .map((a) => ({ value: a.id!, label: `${accountName(a)} (${a.currency})` }))]} />
            </Field>
            <Field label={t("so.to")} error={errors.target}>
              <Select value={form.target} onChange={(e) => setForm({ ...form, target: e.target.value })}
                options={[{ value: "", label: t("form.choose") },
                  ...payeeList.map((p) => ({ value: `payee:${p.id}`, label: `${p.nickname || p.name} · ${masked(p.account_number)}` })),
                  ...mine.filter((a) => a.id !== form.from).map((a) => ({ value: `account:${a.id}`, label: `${accountName(a)} (${t("so.own")})` }))]} />
            </Field>
            <Field label={t("pay.amount")} hint={from?.currency} error={errors.amount}>
              <Input inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
            </Field>
            <Field label={t("so.frequency")}>
              <Select value={form.frequency} onChange={(e) => setForm({ ...form, frequency: e.target.value as StandingOrder["frequency"] })}
                options={FREQUENCIES.map((f) => ({ value: f, label: t(`freq.${f}`) }))} />
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label={t("so.start")} error={errors.start_date}>
                <Input type="date" min={tomorrow} value={form.start_date} onChange={(e) => setForm({ ...form, start_date: e.target.value })} />
              </Field>
              <Field label={t("so.end")} optional>
                <Input type="date" min={form.start_date} value={form.end_date} onChange={(e) => setForm({ ...form, end_date: e.target.value })} />
              </Field>
            </div>
            <Field label={t("pay.reference")} optional>
              <Input maxLength={140} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} />
            </Field>
            <Button type="submit">{t("pay.review")}</Button>
            {create.isSuccess && <Notice tone="good">{t("so.created")}</Notice>}
          </form>
        </Card>
      </div>

      <StepUpConfirm open={!!review} onClose={() => setReview(null)} title={t("so.confirm")} confirmLabel={t("so.create")}
        onConfirm={() => create.mutate()} pending={create.isPending} error={create.error}
        items={review ? [
          { label: t("transfer.from"), value: from ? accountName(from) : "" },
          { label: t("so.to"), value: targetLabel(review) },
          { label: t("pay.amount"), value: <Money amount={review.amount} currency={review.currency} /> },
          { label: t("so.frequency"), value: t(`freq.${review.frequency}`) },
          { label: t("so.start"), value: review.start_date },
          ...(review.end_date ? [{ label: t("so.end"), value: review.end_date }] : []),
        ] : []} />
      <StepUpConfirm open={!!cancelling} onClose={() => setCancelling(null)} title={t("so.cancelTitle")} confirmLabel={t("so.cancelConfirm")}
        onConfirm={() => cancelling && action.mutate({ id: cancelling.id!, op: "cancel" })} pending={action.isPending} error={action.error}
        items={cancelling ? [
          { label: t("so.to"), value: targetLabel(cancelling) },
          { label: t("pay.amount"), value: <Money amount={cancelling.amount} currency={cancelling.currency} /> },
        ] : []} />
    </>
  );
}
