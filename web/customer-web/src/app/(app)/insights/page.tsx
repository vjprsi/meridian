"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { idem, insights, unwrap } from "@/api/client";
import { useCustomerId, useMyAccounts } from "@/api/me";
import type { Budget, SpendingByCategory } from "@/api/types";
import {
  Async, Badge, Button, Card, Empty, ErrorBanner, Field, Input, Money, PageHeader, Select,
} from "@/components/ui";
import { useT } from "@/lib/i18n";

function iso(d: Date) {
  return d.toISOString().slice(0, 10);
}

export default function Insights() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const accounts = useMyAccounts();
  const currency = accounts.data?.[0]?.currency ?? "KES";
  const now = new Date();
  const ranges = {
    month: { from: iso(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))), to: iso(now) },
    last: { from: iso(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))), to: iso(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 0))) },
    d90: { from: iso(new Date(now.getTime() - 89 * 86_400_000)), to: iso(now) },
  };
  const [range, setRange] = useState<keyof typeof ranges>("month");

  const spending = useQuery({
    queryKey: ["spending", c, range], enabled: !!c,
    queryFn: async () => unwrap(await insights.GET("/v1/insights/spending/by-category", {
      params: { query: { customer_id: c, ...ranges[range] } } })) as unknown as SpendingByCategory,
  });
  const cards = useQuery({
    queryKey: ["insight-cards", c], enabled: !!c,
    queryFn: async () => unwrap(await insights.GET("/v1/insights/cards", { params: { query: { customer_id: c, limit: 10 } } }))
      .data.filter((x) => x.customer_id === c && x.delivery_status !== "DISMISSED" && x.delivery_status !== "EXPIRED"),
  });
  const dismiss = useMutation({
    mutationFn: async (id: string) => unwrap(await insights.POST("/v1/insights/cards/{id}/dismiss", { params: { path: { id }, header: idem() } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["insight-cards", c] }),
  });

  const spend = (spending.data?.categories ?? []).filter((x) => !x.is_income && Number(x.spend) > 0);
  const max = Math.max(1, ...spend.map((x) => Number(x.spend)));

  return (
    <>
      <PageHeader title={t("nav.insights")} subtitle={t("insights.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t("insights.spending")}
          actions={
            <Select aria-label={t("insights.period")} value={range} onChange={(e) => setRange(e.target.value as keyof typeof ranges)}
              options={[{ value: "month", label: t("insights.thisMonth") }, { value: "last", label: t("insights.lastMonth") },
                { value: "d90", label: t("insights.last90") }]} />
          }>
          <Async q={spending} isEmpty={() => spend.length === 0} empty={<Empty title={t("insights.noSpending")} />}>
            {() => (
              <ul className="flex flex-col gap-3">
                {spend.map((x) => {
                  const pct = Math.round(Number(x.share_of_spend) * 100);
                  return (
                    <li key={`${x.currency}-${x.spend_category_id ?? "none"}`}>
                      <div className="flex justify-between gap-3 text-sm">
                        <span className="font-medium">{x.category_name}</span>
                        <span><Money amount={x.spend} currency={x.currency} /> <span className="text-muted">({pct}%)</span></span>
                      </div>
                      <div aria-hidden="true" className="mt-1 h-2 rounded-full bg-surface-2">
                        <div className="h-2 rounded-full bg-accent" style={{ width: `${(Number(x.spend) / max) * 100}%` }} />
                      </div>
                      <span className="text-xs text-muted">{t("insights.txns", { n: x.transaction_count })}</span>
                    </li>
                  );
                })}
              </ul>
            )}
          </Async>
        </Card>

        <Budgets currency={currency} />

        <Card title={t("insights.tips")} className="lg:col-span-2">
          <ErrorBanner error={dismiss.error} className="mb-3" />
          <Async q={cards} empty={<Empty title={t("insights.noTips")} />}>
            {(rows) => (
              <ul className="grid gap-3 sm:grid-cols-2">
                {rows.map((i) => (
                  <li key={i.id} className="rounded-[var(--radius)] border border-border p-4">
                    <div className="flex items-start justify-between gap-2">
                      <p className="font-semibold">{i.title}</p>
                      <Badge value={i.severity} tone={i.severity === "CRITICAL" ? "bad" : i.severity === "WARNING" ? "warn" : i.severity === "POSITIVE" ? "good" : "info"} />
                    </div>
                    <p className="mt-1 text-sm">{i.body}</p>
                    <Button variant="ghost" className="mt-2" onClick={() => dismiss.mutate(i.id!)}>{t("insights.dismiss")}</Button>
                  </li>
                ))}
              </ul>
            )}
          </Async>
        </Card>
      </div>
    </>
  );
}

function Budgets({ currency }: { currency: string }) {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const budgets = useQuery({
    queryKey: ["budgets", c], enabled: !!c,
    queryFn: async () => {
      const list = unwrap(await insights.GET("/v1/insights/budgets", { params: { query: { customer_id: c, limit: 50 } } }))
        .data.filter((b) => b.customer_id === c && b.status !== "ARCHIVED");
      return Promise.all(list.map(async (b) => {
        const r = await insights.GET("/v1/insights/budgets/{id}/current-period", { params: { path: { id: b.id! } } });
        return { budget: b, period: r.data };
      }));
    },
  });
  const categories = useQuery({
    queryKey: ["spend-categories"],
    queryFn: async () => unwrap(await insights.GET("/v1/insights/categories", { params: { query: { is_active: true, is_income: false, limit: 200 } } }))
      .data.filter((x) => x.level !== "SUBCATEGORY"),
    staleTime: 10 * 60_000,
  });
  const [form, setForm] = useState({ name: "", spend_category_id: "", amount: "", period_type: "MONTHLY" as Budget["period_type"] });
  const [err, setErr] = useState<Record<string, string>>({});
  const create = useMutation({
    mutationFn: async () => unwrap(await insights.POST("/v1/insights/budgets", {
      params: { header: idem() },
      body: { customer_id: c, name: form.name, spend_category_id: form.spend_category_id || undefined, amount: form.amount,
        currency, period_type: form.period_type, starts_on: new Date().toISOString().slice(0, 10), notify_customer: true },
    })),
    onSuccess: () => { setForm({ ...form, name: "", amount: "" }); qc.invalidateQueries({ queryKey: ["budgets", c] }); },
  });
  const archive = useMutation({
    mutationFn: async (id: string) => unwrap(await insights.POST("/v1/insights/budgets/{id}/archive", { params: { path: { id }, header: idem() } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["budgets", c] }),
  });

  return (
    <Card title={t("insights.budgets")}>
      <Async q={budgets} empty={<Empty title={t("insights.noBudgets")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-4">
            {rows.map(({ budget: b, period: p }) => {
              const spent = Number(p?.spent_amount ?? 0);
              const total = Number(p?.budget_amount ?? b.amount);
              const pct = total > 0 ? Math.min(100, Math.round((spent / total) * 100)) : 0;
              const over = spent > total;
              return (
                <li key={b.id}>
                  <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
                    <span className="font-medium">{b.name}</span>
                    <span><Money amount={p?.spent_amount ?? "0"} currency={b.currency} /> / <Money amount={p?.budget_amount ?? b.amount} currency={b.currency} /></span>
                  </div>
                  <div role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct}
                    aria-label={t("insights.budgetUsed", { name: b.name, pct })} className="mt-1 h-2 rounded-full bg-surface-2">
                    <div className={over ? "h-2 rounded-full bg-bad" : "h-2 rounded-full bg-good"} style={{ width: `${pct}%` }} />
                  </div>
                  <div className="mt-1 flex items-center justify-between text-xs text-muted">
                    <span>{over ? t("insights.over") : t("insights.left", { amount: p?.remaining_amount ?? b.amount })}</span>
                    <button type="button" className="prose-link min-h-11" onClick={() => archive.mutate(b.id!)}>{t("insights.remove")}</button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Async>
      <ErrorBanner error={archive.error ?? create.error} className="mt-3" />
      <form noValidate className="mt-5 grid gap-3 border-t border-border pt-4 sm:grid-cols-2" onSubmit={(e) => {
        e.preventDefault();
        const er: Record<string, string> = {};
        if (!form.name.trim()) er.name = t("form.required");
        if (!/^\d+(\.\d{1,2})?$/.test(form.amount) || Number(form.amount) <= 0) er.amount = t("form.amount");
        setErr(er);
        if (!Object.keys(er).length) create.mutate();
      }}>
        <Field label={t("insights.budgetName")} error={err.name}>
          <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </Field>
        <Field label={t("insights.category")} optional>
          <Select value={form.spend_category_id} onChange={(e) => setForm({ ...form, spend_category_id: e.target.value })}
            options={[{ value: "", label: t("insights.allSpending") }, ...(categories.data ?? []).map((x) => ({ value: x.id!, label: x.name }))]} />
        </Field>
        <Field label={t("pay.amount")} hint={currency} error={err.amount}>
          <Input inputMode="decimal" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value.replace(",", ".").trim() })} />
        </Field>
        <Field label={t("insights.periodType")}>
          <Select value={form.period_type} onChange={(e) => setForm({ ...form, period_type: e.target.value as Budget["period_type"] })}
            options={(["WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"] as const).map((p) => ({ value: p, label: t(`period.${p}`) }))} />
        </Field>
        <Button type="submit" className="sm:col-span-2" disabled={create.isPending}>{create.isPending ? t("common.working") : t("insights.addBudget")}</Button>
      </form>
    </Card>
  );
}
