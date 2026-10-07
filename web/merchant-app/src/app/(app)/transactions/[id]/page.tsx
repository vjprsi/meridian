"use client";

import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { usePermissions } from "@/api/merchant";
import { ApiError, api, unwrap } from "@/api/client";
import type { Refundable } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useRun } from "@/components/step-up";
import {
  Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Loading, Money, Notice, PageHeader, Summary,
} from "@/components/ui";

/** One transaction, what can still be refunded on it, and the refund request form (cashier, manager, owner). */
export default function TransactionPage() {
  const { id } = useParams<{ id: string }>();
  const t = useT();
  const perms = usePermissions();
  const txn = useQuery({
    queryKey: ["transaction", id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/transactions/{transaction_id}",
      { params: { path: { transaction_id: id } } })),
  });
  const isPayment = txn.data?.txn_type === "MERCHANT_PAYMENT" && txn.data?.status === "COMPLETED";
  const refundable = useQuery({
    queryKey: ["refundable", id], enabled: isPayment,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/transactions/{transaction_id}/refundable",
      { params: { path: { transaction_id: id } } })) as unknown as Refundable,
  });
  const request = useRun();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [done, setDone] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await request.run(async (key) => unwrap(await api.POST("/v1/merchant/refunds", {
      params: { header: { "Idempotency-Key": key } },
      body: { original_transaction_id: id, amount, reason: reason.trim() },
    })));
    if (r.ok) {
      setDone(true);
      setAmount("");
      setReason("");
    }
  }

  if (txn.isPending) return <Loading rows={6} />;
  if (txn.error instanceof ApiError && txn.error.problem.status === 404) return <Empty title={t("err404")} />;
  if (txn.error || !txn.data) return <ErrorBanner error={txn.error} />;
  const x = txn.data;
  const rf = refundable.data;

  return (
    <>
      <PageHeader title={t("transaction")} subtitle={x.reference} back={{ href: "/transactions", label: t("transactions") }} />
      <Card className="mb-4">
        <Summary items={[
          { label: t("amount"), value: <Money amount={x.amount} currency={x.currency} /> },
          { label: t("status"), value: <Badge value={x.status} /> },
          { label: t("type"), value: x.txn_type },
          { label: t("created"), value: <DateTime value={x.created_at} /> },
          ...(rf ? [
            { label: t("refunded"), value: <Money amount={rf.refunded} currency={rf.currency} /> },
            { label: t("pendingRefund"), value: <Money amount={rf.pending} currency={rf.currency} /> },
            { label: t("refundable"), value: <span data-testid="refundable"><Money amount={rf.refundable} currency={rf.currency} /></span> },
          ] : []),
        ]} />
      </Card>
      {isPayment && perms.canSell && (
        <Card title={t("requestRefund")}>
          {done && <div className="mb-3"><Notice tone="good">{t("refundRequested")}</Notice></div>}
          <form onSubmit={submit} className="flex flex-col gap-4">
            <Field label={`${t("amount")} (${x.currency})`}>
              <Input name="amount" inputMode="decimal" required value={amount} pattern="^\d+(\.\d{1,2})?$"
                onChange={(e) => { setAmount(e.target.value); setDone(false); }} />
            </Field>
            <Field label={t("reason")}>
              <Input name="reason" required maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <ErrorBanner error={request.error} />
            <Button type="submit" disabled={request.pending}>{request.pending ? t("working") : t("requestRefund")}</Button>
          </form>
        </Card>
      )}
    </>
  );
}
