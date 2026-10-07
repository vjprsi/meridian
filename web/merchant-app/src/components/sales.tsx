"use client";

import { useQuery } from "@tanstack/react-query";
import { useMe } from "@/api/merchant";
import { ApiError, api, unwrap } from "@/api/client";
import type { MerchantSale } from "@/api/types";
import { useT } from "@/lib/i18n";
import { QrCode } from "./qr";
import { useRun } from "./step-up";
import { Badge, Button, Card, DateTime, Empty, ErrorBanner, LinkButton, Loading, Money, Summary } from "./ui";

/** Store name from /me (the stores the user may work in); a store outside the list shows its id only. */
export function useStoreName() {
  const me = useMe();
  return (id?: string | null) => me.data?.stores.find((s) => s.id === id)?.name ?? (id ? id.slice(-8) : "—");
}

/**
 * One sale: its QR while PENDING, polled every 2 s (the server refreshes the status from merchant-service on each
 * read), and the final state (PAID / EXPIRED / CANCELLED). A sale of another merchant or of a store outside the
 * user's scope is a 404 from the server and shows the not-found state.
 */
export function SaleView({ saleId, onNew }: { saleId: string; onNew?: () => void }) {
  const t = useT();
  const storeName = useStoreName();
  const cancel = useRun();
  const sale = useQuery({
    queryKey: ["sale", saleId],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/sales/{sale_id}", { params: { path: { sale_id: saleId } } })),
    refetchInterval: (q) => (q.state.data?.status === "PENDING" && !q.state.error ? 2000 : false),
    refetchIntervalInBackground: false,
  });

  if (sale.isPending) return <Loading rows={5} />;
  if (sale.error instanceof ApiError && sale.error.problem.status === 404) {
    return <Empty title={t("saleNotFound")}>{t("saleNotFoundHint")}</Empty>;
  }
  if (sale.error || !sale.data) return <ErrorBanner error={sale.error} />;
  const s: MerchantSale = sale.data;
  const pending = s.status === "PENDING";

  return (
    <Card className="mx-auto max-w-md">
      <div className="flex flex-col items-center gap-3 text-center">
        <Money amount={s.amount} currency={s.currency} className="text-3xl font-bold" />
        {s.reference && <p className="text-sm text-muted">{t("reference")}: {s.reference}</p>}
        {pending && s.qr_payload && (
          <>
            <QrCode payload={s.qr_payload} label={t("scanToPay")} />
            <p className="font-medium">{t("scanToPay")}</p>
            <p role="status" aria-live="polite" className="text-sm text-muted" data-testid="sale-status">{t("waitingForPayment")}</p>
          </>
        )}
        {s.status === "PAID" && (
          <div role="status" aria-live="polite" data-testid="sale-status"
            className="w-full rounded-[var(--radius)] bg-good-soft px-4 py-6 text-good">
            <p className="text-4xl" aria-hidden="true">✓</p>
            <p className="text-2xl font-bold">{t("paid")}</p>
            {s.paid_at && <p className="mt-1 text-sm">{t("paidAt")}: <DateTime value={s.paid_at} /></p>}
          </div>
        )}
        {(s.status === "EXPIRED" || s.status === "CANCELLED") && (
          <div role="status" data-testid="sale-status" className="w-full rounded-[var(--radius)] bg-bad-soft px-4 py-6 text-bad">
            <p className="text-2xl font-bold">{s.status === "EXPIRED" ? t("expired") : t("cancelled")}</p>
          </div>
        )}
      </div>
      <div className="mt-5">
        <Summary items={[
          { label: t("status"), value: <Badge value={s.status} /> },
          { label: t("store"), value: storeName(s.merchant_store_id) },
          { label: t("created"), value: <DateTime value={s.created_at} /> },
          { label: t("expiresAt"), value: <DateTime value={s.expires_at} /> },
        ]} />
      </div>
      <ErrorBanner error={cancel.error} className="mt-4" />
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        {pending && (
          <Button variant="secondary" disabled={cancel.pending} onClick={() => cancel.run(async (key) =>
            unwrap(await api.POST("/v1/merchant/sales/{sale_id}/cancel",
              { params: { path: { sale_id: s.id }, header: { "Idempotency-Key": key } } })))}>
            {t("cancelSale")}
          </Button>
        )}
        {!pending && (onNew
          ? <Button size="lg" onClick={onNew}>{t("anotherSale")}</Button>
          : <LinkButton href="/sale" size="lg">{t("anotherSale")}</LinkButton>)}
      </div>
    </Card>
  );
}
