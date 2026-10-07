"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { agents, idem, problemOf, unwrap, type AgentTill } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Button, Card, DateTime, Field, Input, Money, Notice, PageTitle, ProblemBox, Row, Spinner } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { fromMinor, toMinor } from "@/lib/money";
import type { Problem } from "@/lib/outbox/types";
import { useLive } from "@/lib/queries";

const DENOMS: Record<string, string[]> = {
  KES: ["1000", "500", "200", "100", "50", "20", "10", "5", "1"],
  TZS: ["10000", "5000", "2000", "1000", "500", "200", "100", "50"],
  UGX: ["50000", "20000", "10000", "5000", "2000", "1000", "500", "200", "100"],
  NGN: ["1000", "500", "200", "100", "50", "20", "10", "5"],
};

/** Notes-and-coins counter; the total is computed in integer minor units. */
function CashCount({ currency, counts, onChange }: { currency: string; counts: Record<string, string>; onChange: (c: Record<string, string>) => void }) {
  const denoms = DENOMS[currency] ?? ["100", "50", "20", "10", "5", "1"];
  return (
    <div className="grid grid-cols-2 gap-2">
      {denoms.map((d) => (
        <label key={d} className="flex items-center gap-2">
          <span className="w-20 text-right font-semibold num">{d} ×</span>
          <Input inputMode="numeric" aria-label={`${d} ${currency}`} value={counts[d] ?? ""} className="text-center"
            onChange={(e) => onChange({ ...counts, [d]: e.target.value.replace(/\D/g, "") })} />
        </label>
      ))}
    </div>
  );
}

function countTotal(counts: Record<string, string>): string {
  let total = BigInt(0);
  for (const [d, n] of Object.entries(counts)) if (n) total += toMinor(d) * BigInt(n);
  return fromMinor(total);
}

function denominationsOf(counts: Record<string, string>): Record<string, number> {
  return Object.fromEntries(Object.entries(counts).filter(([, n]) => n && Number(n) > 0).map(([d, n]) => [d, Number(n)]));
}

/**
 * Till / cash count: open the outlet's till for the shift with an opening count, close it with a declared count;
 * the server computes expected cash from the shift's cash-in / cash-out and reports the variance
 * (agentTillsList, agentTillsCreate, agentTillsOpen, agentTillsClose). Online only.
 */
export default function TillPage() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const tills = useQuery({
    queryKey: ["tills", p?.outletId],
    enabled: live && !!p?.outletId,
    queryFn: async () => unwrap(await agents.GET("/v1/agent-tills", { params: { query: { agent_outlet_id: p!.outletId! } } })).data,
  });
  const [tillNo, setTillNo] = useState("T1");
  const [problem, setProblem] = useState<Problem | null>(null);

  if (!p) return null;
  if (!p.outletId) return <><PageTitle>{t("till")}</PageTitle><Notice tone="warn">{t("chooseOutlet")}: —</Notice></>;

  async function create(e: FormEvent) {
    e.preventDefault();
    try {
      unwrap(await agents.POST("/v1/agent-tills", { params: { header: idem() }, body: { agent_outlet_id: p!.outletId!, till_number: tillNo, currency: p!.currency } }));
      void tills.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle sub={p.outletName ?? undefined}>{t("till")}</PageTitle>
      {!live && <Notice tone="warn">{t("offline")}</Notice>}
      {tills.isLoading && <Spinner />}
      {tills.error && <ProblemBox problem={problemOf(tills.error)} />}
      {tills.data?.map((till) => <TillCard key={till.id} till={till} onChange={() => void tills.refetch()} />)}
      {tills.data?.length === 0 && (
        <Card title={t("noTill")}>
          <form onSubmit={create} className="flex flex-col gap-3">
            <Field label={t("tillNumber")}><Input value={tillNo} onChange={(e) => setTillNo(e.target.value)} required /></Field>
            <ProblemBox problem={problem} />
            <Button type="submit">{t("createTill")}</Button>
          </form>
        </Card>
      )}
    </div>
  );
}

function TillCard({ till, onChange }: { till: AgentTill; onChange: () => void }) {
  const t = useT();
  const { session } = useSession();
  const [counts, setCounts] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [closed, setClosed] = useState<AgentTill | null>(null);
  const total = countTotal(counts);
  const isOpen = till.status === "OPEN";

  async function act(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      if (isOpen) {
        setClosed(unwrap(await agents.POST("/v1/agent-tills/{id}/close", { params: { path: { id: till.id }, header: idem() },
          body: { declared_cash: total, denominations: denominationsOf(counts) } })));
      } else {
        if (!session?.userId) throw new Error("No operator user id in this session");
        unwrap(await agents.POST("/v1/agent-tills/{id}/open", { params: { path: { id: till.id }, header: idem() },
          body: { operator_user_id: session.userId, opening_cash: total, denominations: denominationsOf(counts) } }));
      }
      setCounts({});
      onChange();
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
    }
  }

  const shown = closed ?? till;
  return (
    <Card title={<span>{t("till")} {till.till_number}</span>} actions={<Badge value={till.status} />}>
      <dl className="mb-4">
        {shown.opened_at && <Row label={t("openTill")}><DateTime value={shown.opened_at} /></Row>}
        {shown.opening_cash && <Row label={t("openingCash")}><Money amount={shown.opening_cash} currency={till.currency} /></Row>}
        {shown.cash_in_total && <Row label={t("cashIn")}><Money amount={shown.cash_in_total} currency={till.currency} /></Row>}
        {shown.cash_out_total && <Row label={t("cashOut")}><Money amount={shown.cash_out_total} currency={till.currency} /></Row>}
        {shown.expected_cash && <Row label={t("expectedCash")}><Money amount={shown.expected_cash} currency={till.currency} /></Row>}
        {shown.declared_cash && <Row label={t("declaredCash")}><Money amount={shown.declared_cash} currency={till.currency} /></Row>}
        {shown.variance && <Row label={t("variance")}><Money amount={shown.variance} currency={till.currency} /> {shown.variance_status && <Badge value={shown.variance_status} />}</Row>}
      </dl>
      {till.status !== "SUSPENDED" && (
        <form onSubmit={act} className="flex flex-col gap-3">
          <p className="font-semibold">{t("countCash")} — {t("denominations")}</p>
          <CashCount currency={till.currency} counts={counts} onChange={setCounts} />
          <p className="text-lg font-bold">{isOpen ? t("declaredCash") : t("openingCash")}: <Money amount={total} currency={till.currency} /></p>
          <ProblemBox problem={problem} />
          <Button type="submit" disabled={busy}>{isOpen ? t("closeTill") : t("openTill")}</Button>
        </form>
      )}
    </Card>
  );
}
