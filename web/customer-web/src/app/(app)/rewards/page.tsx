"use client";

import { useQuery } from "@tanstack/react-query";
import { rewards, unwrap } from "@/api/client";
import { useCustomerId } from "@/api/me";
import type { PointsAccount } from "@/api/types";
import { Async, Badge, Card, DateTime, Empty, Money, PageHeader, Summary } from "@/components/ui";
import { useI18n, useT } from "@/lib/i18n";

export default function Rewards() {
  const t = useT();
  const c = useCustomerId();
  const accounts = useQuery({
    queryKey: ["points-accounts", c], enabled: !!c,
    queryFn: async () => unwrap(await rewards.GET("/v1/rewards/points-accounts", { params: { query: { customer_id: c, limit: 20 } } }))
      .data.filter((a) => a.customer_id === c),
  });
  const cashback = useQuery({
    queryKey: ["cashback", c], enabled: !!c,
    queryFn: async () => unwrap(await rewards.GET("/v1/rewards/cashback", { params: { query: { customer_id: c, limit: 20 } } }))
      .data.filter((x) => x.customer_id === c),
  });

  return (
    <>
      <PageHeader title={t("nav.rewards")} subtitle={t("rewards.subtitle")} />
      <div className="grid gap-5 lg:grid-cols-2">
        <div className="flex flex-col gap-5">
          <Async q={accounts} empty={<Card><Empty title={t("rewards.none")} /></Card>}>
            {(list) => <>{list.map((a) => <PointsCard key={a.id} account={a} />)}</>}
          </Async>
        </div>
        <Card title={t("rewards.cashback")}>
          <Async q={cashback} empty={<Empty title={t("rewards.noCashback")} />}>
            {(rows) => (
              <ul className="divide-y divide-border">
                {rows.map((x) => (
                  <li key={x.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                    <span>
                      <span className="block"><DateTime value={x.created_at} /></span>
                      <span className="block text-xs text-muted">
                        {t("rewards.on")} <Money amount={x.transaction_amount} currency={x.currency} /> · <Badge value={x.status} />
                      </span>
                    </span>
                    <Money amount={x.cashback_amount} currency={x.currency} signed className="font-semibold text-good" />
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

function PointsCard({ account }: { account: PointsAccount }) {
  const t = useT();
  const { intl } = useI18n();
  const entries = useQuery({
    queryKey: ["points-entries", account.id],
    queryFn: async () => unwrap(await rewards.GET("/v1/rewards/points-accounts/{id}/entries", { params: { path: { id: account.id! }, query: { limit: 15 } } })).data,
  });
  const n = (v?: number | null) => (v ?? 0).toLocaleString(intl);
  return (
    <Card title={t("rewards.points")} actions={<Badge value={account.status} />}>
      <p className="text-3xl font-semibold num">{n(account.available_points)}</p>
      <p className="text-sm text-muted">{t("rewards.available")}</p>
      <div className="mt-4">
        <Summary items={[
          { label: t("rewards.pending"), value: <span className="num">{n(account.pending_points)}</span> },
          { label: t("rewards.lifetime"), value: <span className="num">{n(account.lifetime_earned)}</span> },
          { label: t("rewards.expiring"), value: account.expiring_points
            ? <>{n(account.expiring_points)} · <DateTime value={account.next_expiry_date} dateOnly /></> : "—" },
        ]} />
      </div>
      <h3 className="mt-5 text-sm font-semibold">{t("rewards.history")}</h3>
      <Async q={entries} empty={<p className="mt-2 text-sm text-muted">{t("rewards.noHistory")}</p>}>
        {(rows) => (
          <ul className="mt-2 divide-y divide-border">
            {rows.map((e) => (
              <li key={e.id} className="flex items-center justify-between gap-3 py-2 text-sm">
                <span>
                  <span className="block">{e.reason || e.entry_type.toLowerCase().replaceAll("_", " ")}</span>
                  <span className="block text-xs text-muted"><DateTime value={e.created_at} />{e.is_pending && <> · {t("rewards.pendingShort")}</>}</span>
                </span>
                <span className={`num font-semibold ${e.points >= 0 ? "text-good" : ""}`}>{e.points > 0 ? "+" : ""}{n(e.points)}</span>
              </li>
            ))}
          </ul>
        )}
      </Async>
    </Card>
  );
}
