"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { api, unwrap } from "@/api/client";
import { Card, DateTime, Icon, Money, Notice, Row, Tile } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { cmp } from "@/lib/money";
import { useOutbox } from "@/lib/outbox/runtime";
import { today, useFloatBalance, useFloatPositions, useLive } from "@/lib/queries";

interface SummaryGroup { currency: string; earned?: string; net?: string; unpaid?: string }

export default function Home() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const ob = useOutbox();
  const float = useFloatBalance(p);
  const positions = useFloatPositions(p);

  const commissionBalance = useQuery({
    queryKey: ["commission-balance", p?.commissionWalletId],
    enabled: live && !!p?.commissionWalletId,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me/commission-balance")),
  });
  const commissionSummary = useQuery({
    queryKey: ["commission-summary", p?.agentId],
    enabled: live && !!p,
    queryFn: async () => unwrap(await api.GET("/v1/agent/commissions/summary")) as { groups?: SummaryGroup[] },
  });
  const perf = useQuery({
    queryKey: ["performance", p?.agentId, today()],
    enabled: live && !!p,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me/performance", {
      params: { query: { from: today(), to: today() } } })).data,
  });
  const tills = useQuery({
    queryKey: ["tills", p?.outletId],
    enabled: live && !!p?.outletId,
    queryFn: async () => unwrap(await api.GET("/v1/agent/tills", { params: { query: { agent_outlet_id: p!.outletId! } } })).data,
  });
  const device = useQuery({
    queryKey: ["device", p?.deviceId],
    enabled: live && !!p?.deviceId,
    queryFn: async () => unwrap(await api.GET("/v1/agent/devices/{device_id}", { params: { path: { device_id: p!.deviceId! } } })),
  });

  if (!p) return null;
  const available = float.data?.available_balance ?? p.floatAvailable;
  const pos = positions.data?.find((f) => f.wallet_id === p.floatWalletId) ?? positions.data?.[0];
  const threshold = pos?.low_float_alert_threshold ?? pos?.min_balance;
  const low = available != null && threshold != null && cmp(available, threshold) < 0;
  const unpaid = commissionSummary.data?.groups?.find((g) => g.currency === p.currency)?.unpaid;
  const day = perf.data?.[0];
  const rejected = ob.views.filter((v) => v.status === "REJECTED" || v.status === "NEEDS_REVIEW").length;
  const noOpenTill = tills.data && !tills.data.some((x) => x.status === "OPEN");

  const alerts: { tone: "warn" | "bad"; text: string; href: string }[] = [];
  if (low) alerts.push({ tone: "bad", text: `${t("lowFloat")} — ${t("lowFloatHint")}`, href: "/float" });
  if (ob.pending > 0) alerts.push({ tone: "warn", text: `${ob.pending} ${t("pendingSync")}`, href: "/sync" });
  if (rejected > 0) alerts.push({ tone: "bad", text: `${rejected} ${t("rejected")} / ${t("needsReview")}`, href: "/sync" });
  if (noOpenTill) alerts.push({ tone: "warn", text: `${t("till")}: ${t("openTill")}`, href: "/till" });
  if (device.data && device.data.status !== "ACTIVE") alerts.push({ tone: "bad", text: `${t("device")}: ${device.data.status}`, href: "/settings" });

  return (
    <div className="flex flex-col gap-4">
      <div className="grid grid-cols-2 gap-3">
        <Card className="col-span-2">
          <p className="text-sm font-semibold text-muted">{t("floatBalance")}</p>
          <p data-testid="float-balance" className="mt-1 text-3xl font-bold"><Money amount={available} currency={p.currency} /></p>
          <p className="mt-1 text-xs text-muted">
            {float.data ? t("asOf") : t("lastKnown")} <DateTime value={float.data ? new Date().toISOString() : p.floatAt} />
          </p>
        </Card>
        <Card>
          <p className="text-sm font-semibold text-muted">{t("commissionBalance")}</p>
          <p className="mt-1 text-xl font-bold"><Money amount={commissionBalance.data?.available_balance} currency={p.currency} /></p>
        </Card>
        <Card>
          <p className="text-sm font-semibold text-muted">{t("unpaidCommission")}</p>
          <p className="mt-1 text-xl font-bold"><Money amount={unpaid} currency={p.currency} /></p>
        </Card>
      </div>

      <Card title={t("alerts")}>
        {alerts.length === 0 ? <p className="text-muted">{t("noAlerts")}</p> : (
          <ul className="flex flex-col gap-2">
            {alerts.map((a) => <li key={a.text}><Link href={a.href}><Notice tone={a.tone}>{a.text}</Notice></Link></li>)}
          </ul>
        )}
      </Card>

      <div className="grid grid-cols-3 gap-3">
        <Tile href="/cash-in" label={t("cashIn")} icon={Icon.in} />
        <Tile href="/cash-out" label={t("cashOut")} icon={Icon.out} />
        <Tile href="/bills" label={t("bills")} icon={Icon.bill} />
        <Tile href="/airtime" label={t("airtime")} icon={Icon.phone} />
        <Tile href="/onboarding" label={t("onboarding")} icon={Icon.user} />
        <Tile href="/float" label={t("float")} icon={Icon.float} />
      </div>

      <Card title={t("todayPerformance")}>
        {day ? (
          <dl>
            <Row label={`${t("cashIn")} (${day.cash_in_count})`}><Money amount={day.cash_in_amount} currency={day.currency} /></Row>
            <Row label={`${t("cashOut")} (${day.cash_out_count})`}><Money amount={day.cash_out_amount} currency={day.currency} /></Row>
            <Row label={t("bills")}>{day.bill_pay_count}</Row>
            <Row label={t("onboarding")}>{day.onboarding_count}</Row>
            <Row label={t("commissions")}><Money amount={day.commission_earned} currency={day.currency} /></Row>
          </dl>
        ) : <p className="text-muted">{live ? t("nothing") : t("offline")}</p>}
      </Card>
    </div>
  );
}
