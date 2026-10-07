"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, problemOf, unwrap } from "@/api/client";
import { Badge, Button, Card, DateTime, Field, Input, Money, Notice, PageTitle, ProblemBox, Row } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { today, useLive } from "@/lib/queries";

interface Group { beneficiary_wallet_id?: string; currency: string; earned?: string; clawed_back?: string; net?: string; unpaid?: string }

function monthStart(): string {
  return today().slice(0, 8) + "01";
}

/** Commission statement: totals for a period (commissionEarningsSummary) and the earning lines (commissionEarningsList). */
export default function CommissionsPage() {
  const t = useT();
  const p = useProfile();
  const live = useLive();
  const [from, setFrom] = useState(monthStart());
  const [to, setTo] = useState(today());

  const summary = useQuery({
    queryKey: ["commission-summary", p?.agentId, from, to],
    enabled: live && !!p,
    queryFn: async () => unwrap(await api.GET("/v1/agent/commissions/summary", {
      params: { query: { from, to } } })) as { groups?: Group[] },
  });

  const lines = useInfiniteQuery({
    queryKey: ["commission-earnings", p?.agentId],
    enabled: live && !!p,
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => unwrap(await api.GET("/v1/agent/commissions", {
      params: { query: { limit: 25, cursor: pageParam } } })),
    getNextPageParam: (last) => (last.has_more ? last.next_cursor ?? undefined : undefined),
  });

  if (!p) return null;
  const rows = lines.data?.pages.flatMap((pg) => pg.data) ?? [];

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("commissions")}</PageTitle>
      {!live && <Notice tone="warn">{t("offline")}</Notice>}
      <div className="grid grid-cols-2 gap-2">
        <Field label={t("from")}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label={t("to")}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
      </div>
      {summary.error && <ProblemBox problem={problemOf(summary.error)} />}
      {summary.data?.groups?.map((g) => (
        <Card key={(g.beneficiary_wallet_id ?? "") + g.currency}>
          <dl>
            <Row label={t("earned")}><Money amount={g.earned} currency={g.currency} /></Row>
            {g.clawed_back && <Row label="Clawback"><Money amount={g.clawed_back} currency={g.currency} /></Row>}
            <Row label={t("net")}><Money amount={g.net} currency={g.currency} /></Row>
            <Row label={t("unpaid")}><Money amount={g.unpaid} currency={g.currency} /></Row>
          </dl>
        </Card>
      ))}
      <Card title={t("earnings")}>
        {lines.error && <ProblemBox problem={problemOf(lines.error)} />}
        <ul className="divide-y divide-border">
          {rows.map((e) => (
            <li key={e.id} className="flex items-center justify-between gap-3 py-2">
              <span>
                <span className="block font-semibold">{e.txn_type.replaceAll("_", " ")} {e.is_clawback && <Badge value="CLAWBACK" tone="bad" />}</span>
                <span className="block text-sm text-muted"><DateTime value={e.earned_at} /> · {e.payout_frequency}</span>
              </span>
              <Money amount={e.net_amount} currency={e.currency} className="font-bold" />
            </li>
          ))}
        </ul>
        {rows.length === 0 && !lines.isLoading && <p className="text-muted">{t("nothing")}</p>}
        {lines.hasNextPage && <Button variant="secondary" block onClick={() => void lines.fetchNextPage()}>{t("loadMore")}</Button>}
      </Card>
    </div>
  );
}
