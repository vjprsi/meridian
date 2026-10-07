"use client";

import { useParams, useSearchParams } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { download, payments, unwrap } from "@/api/client";
import { assertMine, useCustomerId } from "@/api/me";
import { Async, Badge, Button, Card, DateTime, ErrorBanner, LinkButton, Money, Notice, PageHeader, Summary, masked } from "@/components/ui";
import { useT } from "@/lib/i18n";

const FINAL = ["SETTLED", "REJECTED", "RETURNED", "FAILED", "CANCELLED"];

export default function Receipt() {
  const t = useT();
  const c = useCustomerId();
  const { id } = useParams<{ id: string }>();
  const isNew = useSearchParams().get("new") === "1";
  const [dlError, setDlError] = useState<unknown>(null);
  const q = useQuery({
    queryKey: ["payment", id], enabled: !!c,
    queryFn: async () => assertMine(unwrap(await payments.GET("/v1/payments/{id}", { params: { path: { id } } })), c),
    refetchInterval: (query) => (query.state.data && !FINAL.includes(query.state.data.status ?? "") ? 3000 : false),
  });

  return (
    <>
      <PageHeader title={t("receipt.title")} back={{ href: "/move", label: t("nav.move") }} />
      <Async q={q}>
        {(p) => (
          <div className="flex max-w-2xl flex-col gap-4">
            {isNew && <Notice tone="good">{t("receipt.submitted")}</Notice>}
            <Card>
              <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
                <Money amount={p.amount} currency={p.currency} className="text-2xl font-semibold" />
                <span aria-live="polite"><Badge value={p.status} /></span>
              </div>
              <Summary items={[
                { label: t("receipt.to"), value: p.creditor_name ?? "—" },
                { label: t("receipt.toAccount"), value: masked(p.creditor_account_number) },
                { label: t("receipt.type"), value: p.payment_type.replaceAll("_", " ").toLowerCase() },
                { label: t("pay.reference"), value: p.reference ?? "—" },
                { label: t("receipt.created"), value: <DateTime value={p.created_at} /> },
                { label: t("pay.executes"), value: p.requested_execution_date ?? "—" },
                { label: t("receipt.id"), value: <span className="break-all font-mono text-xs">{p.id}</span> },
                ...(p.end_to_end_id ? [{ label: t("receipt.e2e"), value: <span className="font-mono text-xs">{p.end_to_end_id}</span> }] : []),
              ]} />
              <div className="mt-5 flex flex-wrap gap-2">
                <Button variant="secondary" onClick={async () => {
                  setDlError(null);
                  try { await download(`/v1/payments/${p.id}/receipt`, `receipt-${p.id}.pdf`); } catch (e) { setDlError(e); }
                }}>{t("receipt.download")}</Button>
                <LinkButton href="/move" variant="ghost">{t("receipt.done")}</LinkButton>
              </div>
              <ErrorBanner error={dlError} className="mt-3" />
            </Card>
          </div>
        )}
      </Async>
    </>
  );
}
