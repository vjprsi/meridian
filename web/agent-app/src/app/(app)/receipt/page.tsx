"use client";

import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { api, problemOf, unwrap } from "@/api/client";
import { ReceiptView, receiptText } from "@/components/receipt";
import { Button, Notice, PageTitle, ProblemBox, Spinner } from "@/components/ui";
import { useProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { useOutbox } from "@/lib/outbox/runtime";
import { useLive } from "@/lib/queries";

/**
 * Receipts: server receipts for wallet transactions (walletTransactionsReceipt) and bill payments with token
 * (billPaymentsReceipt), or the local provisional receipt of an operation still waiting for sync.
 */
function Receipt() {
  const t = useT();
  const params = useSearchParams();
  const kind = params.get("kind") ?? "wallet";
  const id = params.get("id") ?? "";
  const live = useLive();
  const p = useProfile();
  const ob = useOutbox();

  const server = useQuery({
    queryKey: ["receipt", kind, id],
    enabled: live && !!id && kind !== "outbox",
    staleTime: Infinity,
    queryFn: async () => {
      if (kind === "bill") {
        return unwrap(await api.GET("/v1/agent/bill-payments/{bill_payment_id}/receipt", {
          params: { path: { bill_payment_id: id } }, parseAs: "text" })) as string;
      }
      return unwrap(await api.GET("/v1/agent/transactions/{transaction_id}/receipt", {
        params: { path: { transaction_id: id } }, parseAs: "text" })) as string;
    },
  });

  if (kind === "outbox") {
    const v = ob.views.find((x) => x.id === id);
    if (!v) return <Notice tone="warn">{t("nothing")}</Notice>;
    return <ReceiptView text={receiptText(v, p)} provisional={v.status === "PENDING"} />;
  }
  if (!live) return <Notice tone="warn">{t("offline")}</Notice>;
  if (server.isLoading) return <Spinner />;
  if (server.error) return <ProblemBox problem={problemOf(server.error)} />;
  return <ReceiptView text={server.data ?? ""} />;
}

export default function ReceiptPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("receipt")}</PageTitle>
      <Suspense fallback={<Spinner />}><Receipt /></Suspense>
      <Button variant="ghost" className="no-print" onClick={() => history.back()}>{t("back")}</Button>
    </div>
  );
}
