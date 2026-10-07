"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { api, idem, problemOf, unwrap } from "@/api/client";
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

const FUNDING = ["BANK_TRANSFER", "CASH_DEPOSIT"];

/**
 * Float management: balance and thresholds, top-up (bank transfer or cash deposit, with its reference) or rebalance
 * from the parent agent (agentMeFloatRequestsCreate, queued when offline; requesting agent, target wallet and source
 * are set by the agent channel), their status (agentMeFloatRequestsList, cancel), and physical cash delivery /
 * pickup requests for the outlet (agentMeCashRequestsCreate / List / Cancel, online only).
 */
export default function FloatPage() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const qc = useQueryClient();
  const offline = useConnectivity() === "offline";
  const balance = useFloatBalance(p);
  const positions = useFloatPositions(p);
  const [type, setType] = useState<"TOP_UP" | "REBALANCE">("TOP_UP");
  const [amount, setAmount] = useState("");
  const [funding, setFunding] = useState("BANK_TRANSFER");
  const [bankRef, setBankRef] = useState("");
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OutboxView | null>(null);

  const requests = useQuery({
    queryKey: ["float-requests", p?.agentId],
    enabled: live && !!p,
    queryFn: async () => unwrap(await api.GET("/v1/agent/float-requests", { params: { query: { limit: 50 } } })).data,
  });

  if (!p) return null;
  const parent = p.parentAgentId;
  const pos = positions.data?.find((f) => f.wallet_id === p.floatWalletId) ?? positions.data?.[0];
  const available = balance.data?.available_balance ?? p.floatAvailable;
  const low = available != null && pos?.low_float_alert_threshold != null && cmp(available, pos.low_float_alert_threshold) < 0;

  async function request(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    if (!isAmount(amount)) { setProblem({ detail: t("amount") }); return; }
    if (type === "TOP_UP" && !bankRef.trim()) { setProblem({ title: t("bankReference"), code: "funding_proof_required" }); return; }
    setBusy(true);
    try {
      setResult(await submit("FLOAT_REQUEST", {
        request: type === "TOP_UP"
          ? { request_type: type, amount, funding_method: funding, bank_reference: bankRef.trim() }
          : { request_type: type, amount },
        amount, currency: p!.currency,
        display: { Type: type, Source: type === "REBALANCE" ? "Parent agent" : "Treasury", ...(type === "TOP_UP" ? { Funding: funding } : {}) },
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
      unwrap(await api.POST("/v1/agent/float-requests/{float_request_id}/cancel", { params: { path: { float_request_id: id }, header: idem() }, body: { reason: "Cancelled by agent" } }));
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
              <Select value={type} onChange={(e) => setType(e.target.value as typeof type)}
                options={parent ? ["TOP_UP", "REBALANCE"] : ["TOP_UP"]} />
            </Field>
            <Field label={`${t("amount")} (${p.currency})`}>
              <Input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value.replace(",", "."))} required />
            </Field>
            {type === "TOP_UP" && (
              <>
                <Field label={t("fundingMethod")}>
                  <Select value={funding} onChange={(e) => setFunding(e.target.value)} options={FUNDING} />
                </Field>
                <Field label={t("bankReference")}>
                  <Input value={bankRef} onChange={(e) => setBankRef(e.target.value)} required />
                </Field>
              </>
            )}
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
    queryFn: async () => unwrap(await api.GET("/v1/agent/cash-requests", { params: { query: { agent_outlet_id: outletId, limit: 20 } } })).data,
  });

  async function create(e: FormEvent) {
    e.preventDefault();
    setProblem(null);
    try {
      unwrap(await api.POST("/v1/agent/cash-requests", { params: { header: idem() }, body: {
        agent_outlet_id: outletId, request_type: type, amount,
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
      unwrap(await api.POST("/v1/agent/cash-requests/{cash_request_id}/cancel", { params: { path: { cash_request_id: id }, header: idem() }, body: { reason: "Cancelled by agent" } }));
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
