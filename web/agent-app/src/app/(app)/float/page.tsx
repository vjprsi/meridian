"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { agents, idem, problemOf, unwrap } from "@/api/client";
import { OpOutcome } from "@/components/receipt";
import { Badge, Button, Card, DateTime, Field, Input, Money, Notice, PageTitle, ProblemBox, Row, Select } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { cmp, isAmount } from "@/lib/money";
import { OfflineLimitError } from "@/lib/outbox/outbox";
import { submit } from "@/lib/outbox/runtime";
import type { OutboxView, Problem } from "@/lib/outbox/types";
import { useFloatBalance, useFloatPositions, useLive } from "@/lib/queries";

const FUNDING = ["BANK_DEPOSIT", "MOBILE_MONEY", "CASH", "SUPER_AGENT_TRANSFER"];

/**
 * Float management: balance and thresholds, top-up / rebalance requests to the super-agent or treasury
 * (agentFloatRequestsCreate, queued when offline), their status (agentFloatRequestsList, cancel), and physical
 * cash delivery / pickup requests for the outlet (cashManagementRequestsCreate / List / Cancel, online only).
 */
export default function FloatPage() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const qc = useQueryClient();
  const offline = useConnectivity() === "offline";
  const balance = useFloatBalance(p);
  const positions = useFloatPositions(p);
  const [type, setType] = useState<"TOP_UP" | "REBALANCE" | "WITHDRAW">("TOP_UP");
  const [source, setSource] = useState<"parent" | "treasury">("parent");
  const [amount, setAmount] = useState("");
  const [funding, setFunding] = useState("BANK_DEPOSIT");
  const [bankRef, setBankRef] = useState("");
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OutboxView | null>(null);

  const requests = useQuery({
    queryKey: ["float-requests", p?.agentId],
    enabled: live && !!p,
    queryFn: async () => unwrap(await agents.GET("/v1/agent-float-requests", { params: { query: { requesting_agent_id: p!.agentId, limit: 50 } } })).data,
  });

  if (!p) return null;
  const parent = p.parentAgentId ?? p.superAgentId;
  const pos = positions.data?.find((f) => f.wallet_id === p.floatWalletId) ?? positions.data?.[0];
  const available = balance.data?.available_balance ?? p.floatAvailable;
  const low = available != null && pos?.low_float_alert_threshold != null && cmp(available, pos.low_float_alert_threshold) < 0;

  async function request(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    setBusy(true);
    try {
      setResult(await submit("FLOAT_REQUEST", {
        request: {
          request_type: type, requesting_agent_id: p!.agentId,
          source_agent_id: source === "parent" && parent ? parent : undefined,
          target_wallet_id: p!.floatWalletId, amount, currency: p!.currency,
          funding_method: funding, bank_reference: bankRef || undefined,
        },
        amount, currency: p!.currency,
        display: { Type: type, Source: source === "parent" && parent ? "Super-agent" : "Treasury", Funding: funding },
      }, { offline }));
      void qc.invalidateQueries({ queryKey: ["float-requests"] });
    } catch (x) {
      setProblem(x instanceof OfflineLimitError ? { title: t("limitsExceeded"), code: x.code, detail: x.message } : problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  async function cancel(id: string) {
    try {
      unwrap(await agents.POST("/v1/agent-float-requests/{id}/cancel", { params: { path: { id }, header: idem() }, body: { reason: "Cancelled by agent" } }));
      void requests.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("float")}</PageTitle>
      <Card>
        <dl>
          <Row label={t("floatBalance")}><Money amount={available} currency={p.currency} /></Row>
          {pos?.low_float_alert_threshold && <Row label={t("lowFloat")}><Money amount={pos.low_float_alert_threshold} currency={p.currency} /></Row>}
          {pos?.max_balance && <Row label="Max"><Money amount={pos.max_balance} currency={p.currency} /></Row>}
        </dl>
        {low && <div className="mt-3"><Notice tone="bad">{t("lowFloatHint")}</Notice></div>}
      </Card>

      <Card title={t("requestTopUp")}>
        {result ? <OpOutcome view={result} onNew={() => { setResult(null); setAmount(""); setBankRef(""); }} /> : (
          <form onSubmit={request} className="flex flex-col gap-4">
            <Field label={t("requestType")}>
              <Select value={type} onChange={(e) => setType(e.target.value as typeof type)} options={["TOP_UP", "REBALANCE", "WITHDRAW"]} />
            </Field>
            <Field label="Source">
              <Select value={source} onChange={(e) => setSource(e.target.value as typeof source)}
                options={[...(parent ? [{ value: "parent", label: "Super-agent" }] : []), { value: "treasury", label: "Treasury" }]} />
            </Field>
            <Field label={`${t("amount")} (${p.currency})`}>
              <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} required />
            </Field>
            <Field label={t("fundingMethod")}>
              <Select value={funding} onChange={(e) => setFunding(e.target.value)} options={FUNDING} />
            </Field>
            <Field label={t("bankReference")}>
              <Input value={bankRef} onChange={(e) => setBankRef(e.target.value)} />
            </Field>
            <ProblemBox problem={problem} />
            <Button type="submit" block disabled={busy || !p.floatWalletId}>{t("submit")}</Button>
          </form>
        )}
      </Card>

      <Card title={t("floatRequests")}>
        {!live && <p className="text-muted">{t("offline")}</p>}
        {requests.error && <ProblemBox problem={problemOf(requests.error)} />}
        <ul className="flex flex-col gap-2">
          {requests.data?.map((r) => (
            <li key={r.id} className="rounded-[var(--radius)] border border-border p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="font-semibold">{r.request_type.replace("_", " ")} · <Money amount={r.amount} currency={r.currency} /></span>
                <Badge value={r.status} />
              </div>
              <p className="text-sm text-muted"><DateTime value={r.created_at} />{r.rejection_reason ? ` · ${r.rejection_reason}` : ""}</p>
              {r.status === "REQUESTED" && <Button variant="ghost" onClick={() => cancel(r.id)}>{t("cancel")}</Button>}
            </li>
          ))}
          {requests.data?.length === 0 && <p className="text-muted">{t("nothing")}</p>}
        </ul>
      </Card>

      {p.outletId && <CashRequests outletId={p.outletId} currency={p.currency} />}
    </div>
  );
}

function CashRequests({ outletId, currency }: { outletId: string; currency: string }) {
  const t = useT();
  const live = useLive();
  const [type, setType] = useState<"CASH_DELIVERY" | "CASH_PICKUP">("CASH_DELIVERY");
  const [amount, setAmount] = useState("");
  const [when, setWhen] = useState("");
  const [problem, setProblem] = useState<Problem | null>(null);
  const list = useQuery({
    queryKey: ["cash-requests", outletId],
    enabled: live,
    queryFn: async () => unwrap(await agents.GET("/v1/cash-management-requests", { params: { query: { agent_outlet_id: outletId, limit: 20 } } })).data,
  });

  async function create(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    try {
      unwrap(await agents.POST("/v1/cash-management-requests", { params: { header: idem() }, body: {
        agent_outlet_id: outletId, request_type: type, amount, currency,
        requested_for: when ? new Date(when).toISOString() : undefined,
      } }));
      setAmount("");
      void list.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  async function cancel(id: string) {
    try {
      unwrap(await agents.POST("/v1/cash-management-requests/{id}/cancel", { params: { path: { id }, header: idem() }, body: { reason: "Cancelled by agent" } }));
      void list.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  return (
    <Card title={t("cashRequests")}>
      {!live ? <p className="text-muted">{t("offline")}</p> : (
        <form onSubmit={create} className="mb-4 flex flex-col gap-3">
          <Select value={type} onChange={(e) => setType(e.target.value as typeof type)} aria-label={t("requestType")}
            options={[{ value: "CASH_DELIVERY", label: t("deliver") }, { value: "CASH_PICKUP", label: t("pickup") }]} />
          <Field label={`${t("amount")} (${currency})`}>
            <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} required pattern="\d+(\.\d{1,4})?" />
          </Field>
          <Field label={t("requestedFor")}>
            <Input type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
          </Field>
          <ProblemBox problem={problem} />
          <Button type="submit" variant="secondary">{t("submit")}</Button>
        </form>
      )}
      <ul className="flex flex-col gap-2">
        {list.data?.map((r) => (
          <li key={r.id} className="flex items-center justify-between gap-2 rounded-[var(--radius)] border border-border p-3">
            <span>{r.request_type === "CASH_DELIVERY" ? t("deliver") : t("pickup")} · <Money amount={r.amount} currency={r.currency} /></span>
            <span className="flex items-center gap-2">
              <Badge value={r.status} />
              {r.status === "REQUESTED" && <Button variant="ghost" onClick={() => cancel(r.id)}>{t("cancel")}</Button>}
            </span>
          </li>
        ))}
      </ul>
    </Card>
  );
}
