"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, loans, unwrap } from "@/api/client";
import { assertMine, qk, useCustomerId, useMyAccounts } from "@/api/me";
import { accountName } from "@/components/banking";
import { AMOUNT } from "@/components/payment-flow";
import { StepUpConfirm } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Money, Notice, PageHeader, Select, Summary,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function LoanDetail() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const { id } = useParams<{ id: string }>();
  const loan = useQuery({
    queryKey: ["loan", id], enabled: !!c,
    queryFn: async () => assertMine(unwrap(await loans.GET("/v1/loans/{id}", { params: { path: { id } } })), c),
  });
  const schedule = useQuery({
    queryKey: ["loan-schedule", id], enabled: loan.isSuccess,
    queryFn: async () => unwrap(await loans.GET("/v1/loans/{id}/schedule", { params: { path: { id }, query: { limit: 200 } } })).data,
  });
  const repayments = useQuery({
    queryKey: ["loan-repayments", id], enabled: loan.isSuccess,
    queryFn: async () => unwrap(await loans.GET("/v1/loans/{id}/repayments", { params: { path: { id }, query: { limit: 20 } } })).data,
  });
  const accounts = useMyAccounts();
  const [form, setForm] = useState({ amount: "", source: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [review, setReview] = useState(false);
  const [key, setKey] = useState("");

  const repay = useMutation({
    mutationFn: async () => unwrap(await loans.POST("/v1/loans/{id}/repay", {
      params: { path: { id }, header: idem(key) },
      body: { amount: form.amount, source: "MANUAL", source_account_id: form.source },
    })),
    onSuccess: () => {
      setReview(false);
      setForm({ ...form, amount: "" });
      for (const k of [["loan", id], ["loan-schedule", id], ["loan-repayments", id], qk.loans(c), ["balance"]]) qc.invalidateQueries({ queryKey: k });
    },
  });

  return (
    <>
      <PageHeader title={t("loans.detail")} back={{ href: "/loans", label: t("nav.loans") }} />
      <Async q={loan}>
        {(l) => {
          const sources = (accounts.data ?? []).filter((a) => a.status === "ACTIVE" && a.can_transact !== false && a.currency === l.currency);
          const src = sources.find((a) => a.id === form.source);
          const next = (schedule.data ?? []).find((i) => i.status !== "PAID" && i.status !== "WAIVED");
          function submit(e: React.FormEvent) {
            e.preventDefault();
            const er: Record<string, string> = {};
            if (!form.source) er.source = t("form.required");
            if (!AMOUNT.test(form.amount)) er.amount = t("form.amount");
            setErrors(er);
            if (!Object.keys(er).length) { setKey(crypto.randomUUID()); setReview(true); }
          }
          return (
            <div className="flex flex-col gap-5">
              <Card>
                <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
                  <span>
                    <span className="block text-sm text-muted">{l.loan_number}</span>
                    <Money amount={l.total_outstanding} currency={l.currency} className="text-2xl font-semibold" />
                    <span className="block text-sm text-muted">{t("loans.outstanding")}</span>
                  </span>
                  <Badge value={l.status} />
                </div>
                <Summary items={[
                  { label: t("loans.principal"), value: <Money amount={l.principal_amount} currency={l.currency} /> },
                  { label: t("loans.outPrincipal"), value: <Money amount={l.outstanding_principal} currency={l.currency} /> },
                  { label: t("loans.outInterest"), value: <Money amount={l.outstanding_interest} currency={l.currency} /> },
                  { label: t("loans.overdue"), value: <Money amount={l.overdue_amount} currency={l.currency} /> },
                  { label: t("loans.rate"), value: `${l.interest_rate}%` },
                  { label: t("loans.matures"), value: <DateTime value={l.maturity_date} dateOnly /> },
                  ...(next ? [{ label: t("loans.nextDue"), value: <><Money amount={next.total_due} currency={l.currency} /> · <DateTime value={next.due_date} dateOnly /></> }] : []),
                ]} />
              </Card>

              {l.status !== "CLOSED" && l.status !== "WRITTEN_OFF" && (
                <Card title={t("loans.repay")}>
                  <form onSubmit={submit} noValidate className="grid gap-4 sm:grid-cols-[1fr_12rem_auto] sm:items-end">
                    <Field label={t("loans.payFrom")} error={errors.source}>
                      <Select value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })}
                        options={[{ value: "", label: t("form.choose") }, ...sources.map((a) => ({ value: a.id!, label: accountName(a) }))]} />
                    </Field>
                    <Field label={t("pay.amount")} hint={l.currency} error={errors.amount}>
                      <Input inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
                    </Field>
                    <Button type="submit">{t("pay.review")}</Button>
                  </form>
                  {next && <Button variant="ghost" className="mt-2" onClick={() => setForm({ ...form, amount: next.total_due })}>
                    {t("loans.payNextDue")}</Button>}
                  {repay.isSuccess && <div className="mt-3"><Notice tone="good">{t("loans.repaid")}</Notice></div>}
                  <StepUpConfirm open={review} onClose={() => setReview(false)} title={t("loans.confirmRepay")} confirmLabel={t("loans.repay")}
                    onConfirm={() => repay.mutate()} pending={repay.isPending} error={repay.error}
                    items={[
                      { label: t("loans.loan"), value: l.loan_number },
                      { label: t("loans.payFrom"), value: src ? accountName(src) : "" },
                      { label: t("pay.amount"), value: <Money amount={form.amount} currency={l.currency} /> },
                    ]} />
                </Card>
              )}

              <Card title={t("loans.schedule")}>
                <Async q={schedule} empty={<Empty title={t("loans.noSchedule")} />}>
                  {(rows) => (
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[32rem] text-sm">
                        <caption className="sr-only">{t("loans.schedule")}</caption>
                        <thead>
                          <tr className="border-b border-border text-left text-xs text-muted">
                            <th scope="col" className="py-2 pr-3 font-medium">#</th>
                            <th scope="col" className="py-2 pr-3 font-medium">{t("loans.due")}</th>
                            <th scope="col" className="py-2 pr-3 text-right font-medium">{t("loans.amountDue")}</th>
                            <th scope="col" className="py-2 pr-3 text-right font-medium">{t("loans.balanceAfter")}</th>
                            <th scope="col" className="py-2 font-medium">{t("cards.status")}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rows.map((i) => (
                            <tr key={i.id} className="border-b border-border last:border-0">
                              <td className="py-2 pr-3 num">{i.installment_no}</td>
                              <td className="py-2 pr-3"><DateTime value={i.due_date} dateOnly /></td>
                              <td className="py-2 pr-3 text-right"><Money amount={i.total_due} currency={l.currency} /></td>
                              <td className="py-2 pr-3 text-right"><Money amount={i.outstanding_balance_after} currency={l.currency} /></td>
                              <td className="py-2"><Badge value={i.status} /></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </Async>
              </Card>

              <Card title={t("loans.repayments")}>
                <Async q={repayments} empty={<Empty title={t("loans.noRepayments")} />}>
                  {(rows) => (
                    <ul className="divide-y divide-border">
                      {rows.map((r) => (
                        <li key={r.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                          <span><DateTime value={r.received_at} /> · <Badge value={r.status} /></span>
                          <Money amount={r.amount} currency={r.currency} className="font-semibold" />
                        </li>
                      ))}
                    </ul>
                  )}
                </Async>
              </Card>
              <ErrorBanner error={accounts.error} />
            </div>
          );
        }}
      </Async>
    </>
  );
}
