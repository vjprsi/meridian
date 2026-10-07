"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { usePermissions, useStaff, useStores } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { MerchantRefundRequest } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useStoreName } from "@/components/sales";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Dialog, Empty, ErrorBanner, Field, Input, Money, Notice, PageHeader, Select,
} from "@/components/ui";

type Status = MerchantRefundRequest["status"];
const STATUSES: Status[] = ["PENDING_APPROVAL", "COMPLETED", "REJECTED", "CANCELLED", "FAILED"];

/**
 * Refund requests. Cashiers request (from a transaction); an OWNER or MANAGER other than the requester approves
 * (with step-up) or rejects. The buttons are only a convenience: the server enforces roles, store scope, four-eyes
 * and the cumulative refund cap.
 */
export default function RefundsPage() {
  const t = useT();
  const perms = usePermissions();
  const { stores } = useStores();
  const storeName = useStoreName();
  const staff = useStaff(perms.loaded && perms.isManager);
  const [status, setStatus] = useState<string>("PENDING_APPROVAL");
  const [store, setStore] = useState("");
  const [deciding, setDeciding] = useState<{ r: MerchantRefundRequest; op: "approve" | "reject" } | null>(null);
  const [text, setText] = useState("");
  const decide = useRun();
  const cancel = useRun();
  const refunds = useQuery({
    queryKey: ["refunds", status, store],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/refunds", { params: { query: {
      status: (status || undefined) as Status | undefined, store_id: store || undefined, limit: 100 } } })).data,
  });
  const who = (id: string) => id === perms.userId ? t("you")
    : staff.data?.find((u) => u.id === id)?.display_name ?? id.slice(-8);

  async function confirm() {
    if (!deciding) return;
    const { r, op } = deciding;
    const res = await decide.run(async (key) => op === "approve"
      ? unwrap(await api.POST("/v1/merchant/refunds/{refund_id}/approve", {
        params: { path: { refund_id: r.id }, header: { "Idempotency-Key": key } }, body: { comment: text.trim() || undefined } }))
      : unwrap(await api.POST("/v1/merchant/refunds/{refund_id}/reject", {
        params: { path: { refund_id: r.id }, header: { "Idempotency-Key": key } }, body: { reason: text.trim() } })));
    if (res.ok) setDeciding(null);
  }

  return (
    <>
      <PageHeader title={t("refunds")} />
      {perms.loaded && !perms.isManager && perms.canSell && <div className="mb-4"><Notice>{t("fourEyesHint")}</Notice></div>}
      <Card className="mb-4">
        <div className="grid grid-cols-2 gap-3">
          <Field label={t("status")}>
            <Select value={status} onChange={(e) => setStatus(e.target.value)} placeholder={t("all")}
              options={STATUSES.map((s) => ({ value: s, label: s }))} />
          </Field>
          <Field label={t("store")}>
            <Select value={store} onChange={(e) => setStore(e.target.value)} placeholder={t("all")}
              options={stores.map((s) => ({ value: s.id, label: s.name }))} />
          </Field>
        </div>
      </Card>
      <ErrorBanner error={cancel.error} className="mb-4" />
      <Async q={refunds} empty={<Empty title={t("noRefunds")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-3" data-testid="refund-list">
            {rows.map((r) => {
              const mine = r.requested_by === perms.userId;
              const pending = r.status === "PENDING_APPROVAL";
              const canDecide = pending && perms.isManager && !mine;
              return (
                <li key={r.id} data-testid={`refund-${r.id}`}
                  className="rounded-[var(--radius)] border border-border bg-surface p-4">
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <Money amount={r.amount} currency={r.currency} className="text-lg font-semibold" />
                      <p className="text-sm">{r.reason}</p>
                      <p className="mt-1 text-xs text-muted">
                        {t("requestedBy")}: {who(r.requested_by)} · <DateTime value={r.created_at} />
                        {r.merchant_store_id ? ` · ${storeName(r.merchant_store_id)}` : ""}
                      </p>
                      <p className="text-xs">
                        <Link className="prose-link" href={`/transactions/${r.original_transaction_id}`}>{t("transaction")}</Link>
                      </p>
                      {(r.decision_comment || r.failure_reason) && (
                        <p className="mt-1 text-xs text-muted">{r.decision_comment ?? r.failure_reason}</p>
                      )}
                    </div>
                    <Badge value={r.status} />
                  </div>
                  {pending && (canDecide || mine || perms.isManager) && (
                    <div className="mt-3 flex flex-wrap gap-2">
                      {canDecide && (
                        <>
                          <Button onClick={() => { setText(""); decide.newKey(); setDeciding({ r, op: "approve" }); }}>{t("approve")}</Button>
                          <Button variant="danger" onClick={() => { setText(""); decide.newKey(); setDeciding({ r, op: "reject" }); }}>{t("reject")}</Button>
                        </>
                      )}
                      {(mine || perms.isManager) && (
                        <Button variant="secondary" disabled={cancel.pending} onClick={() => { cancel.newKey(); void cancel.run(async (key) =>
                          unwrap(await api.POST("/v1/merchant/refunds/{refund_id}/cancel",
                            { params: { path: { refund_id: r.id }, header: { "Idempotency-Key": key } } }))); }}>
                          {t("withdraw")}
                        </Button>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </Async>
      <Dialog open={!!deciding} onClose={() => setDeciding(null)}
        title={deciding?.op === "approve" ? t("approveRefund") : t("rejectRefund")}
        footer={<>
          <Button variant="secondary" onClick={() => setDeciding(null)} disabled={decide.pending}>{t("cancel")}</Button>
          <Button variant={deciding?.op === "reject" ? "danger" : "primary"} onClick={confirm}
            disabled={decide.pending || (deciding?.op === "reject" && !text.trim())}>
            {decide.pending ? t("working") : deciding?.op === "approve" ? t("approve") : t("reject")}
          </Button>
        </>}>
        {deciding && (
          <div className="flex flex-col gap-3">
            <p><Money amount={deciding.r.amount} currency={deciding.r.currency} className="font-semibold" /> · {deciding.r.reason}</p>
            <Field label={deciding.op === "approve" ? t("comment") : t("reason")} optional={deciding.op === "approve"}>
              <Input value={text} maxLength={deciding.op === "approve" ? 500 : 255} onChange={(e) => setText(e.target.value)} />
            </Field>
            <ErrorBanner error={decide.error} />
          </div>
        )}
      </Dialog>
    </>
  );
}
