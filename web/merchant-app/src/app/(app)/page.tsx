"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useMe, usePermissions, useStores } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import { useT } from "@/lib/i18n";
import { Async, Badge, Card, DateTime, Empty, LinkButton, Money, PageHeader, cx, focusRing } from "@/components/ui";

export default function Home() {
  const t = useT();
  const me = useMe();
  const perms = usePermissions();
  const { selectedId } = useStores();
  const sales = useQuery({
    queryKey: ["sales", "recent", selectedId], enabled: !!selectedId,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/sales",
      { params: { query: { store_id: selectedId, limit: 5 } } })).data,
  });
  const refunds = useQuery({
    queryKey: ["refunds", "pending"], enabled: perms.loaded && perms.isManager,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/refunds",
      { params: { query: { status: "PENDING_APPROVAL", limit: 50 } } })).data,
  });
  const name = me.data?.user.display_name ?? "";
  const waiting = (refunds.data ?? []).filter((r) => r.requested_by !== perms.userId);

  return (
    <>
      <PageHeader title={t("hello", { name })} subtitle={me.data?.merchant?.trading_name ?? undefined} />
      {perms.canSell && (
        <LinkButton href="/sale" size="lg" className="mb-5 w-full sm:w-auto">{t("newSale")}</LinkButton>
      )}
      {perms.isManager && waiting.length > 0 && (
        <Link href="/refunds" className={cx("mb-5 block rounded-[var(--radius)] bg-warn-soft px-4 py-3 text-sm text-warn", focusRing)}>
          {t("pendingRefunds")}: <strong>{waiting.length}</strong>
        </Link>
      )}
      <Card title={t("recentSales")} actions={<LinkButton href="/sales" variant="secondary" size="sm">{t("navSales")}</LinkButton>}>
        <Async q={sales} empty={<Empty title={t("noSales")} />} rows={3}>
          {(rows) => (
            <ul className="divide-y divide-border">
              {rows.map((s) => (
                <li key={s.id}>
                  <Link href={`/sales/${s.id}`} className={cx("flex min-h-12 items-center justify-between gap-3 py-2", focusRing)}>
                    <span className="min-w-0">
                      <Money amount={s.amount} currency={s.currency} className="font-semibold" />
                      <span className="block text-xs text-muted"><DateTime value={s.created_at} /></span>
                    </span>
                    <Badge value={s.status} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </Async>
      </Card>
    </>
  );
}
