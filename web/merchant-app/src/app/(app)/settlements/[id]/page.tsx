"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, download, unwrap } from "@/api/client";
import { useT } from "@/lib/i18n";
import { Badge, Button, Card, DateTime, ErrorBanner, Loading, Money, PageHeader, Summary } from "@/components/ui";

export default function SettlementPage() {
  const { id } = useParams<{ id: string }>();
  const t = useT();
  const [error, setError] = useState<unknown>(null);
  const s = useQuery({
    queryKey: ["settlement", id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/settlements/{settlement_id}",
      { params: { path: { settlement_id: id } } })),
  });
  if (s.isPending) return <Loading rows={6} />;
  if (s.error || !s.data) return <ErrorBanner error={s.error} />;
  const x = s.data;
  return (
    <>
      <PageHeader title={t("settlement")} back={{ href: "/settlements", label: t("settlements") }}
        actions={<Button variant="secondary" onClick={async () => {
          setError(null);
          try { await download(`/v1/merchant/settlements/${encodeURIComponent(id)}/report`, `settlement-${x.period_end}.csv`); }
          catch (e) { setError(e); }
        }}>{t("report")}</Button>} />
      <ErrorBanner error={error} className="mb-4" />
      <Card>
        <Summary items={[
          { label: t("status"), value: <Badge value={x.status} /> },
          { label: t("period"), value: <><DateTime value={x.period_start} dateOnly /> – <DateTime value={x.period_end} dateOnly /></> },
          { label: t("payments"), value: x.payment_count ?? "—" },
          { label: t("gross"), value: <Money amount={x.gross_amount} currency={x.currency} /> },
          { label: t("mdr"), value: <Money amount={x.mdr_amount} currency={x.currency} /> },
          { label: t("refunds"), value: <Money amount={x.refund_amount} currency={x.currency} /> },
          { label: t("net"), value: <Money amount={x.net_amount} currency={x.currency} /> },
          { label: t("paidAt"), value: <DateTime value={x.paid_at} /> },
        ]} />
      </Card>
    </>
  );
}
