"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { usePermissions, useStaff } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { MerchantChangeRequest, PayoutAccount } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Dialog, Empty, ErrorBanner, Field, Input, Notice, PageHeader, Select, Summary,
  masked,
} from "@/components/ui";

/**
 * Payout (settlement) account. An OWNER requests a change to another account of the merchant's business (step-up);
 * another OWNER or MANAGER approves it (step-up) before it applies. Nobody approves their own request.
 */
export default function SettingsPage() {
  const t = useT();
  const perms = usePermissions();
  const staff = useStaff(perms.loaded && perms.isManager);
  const payout = useQuery({
    queryKey: ["payout-account"], enabled: perms.loaded && perms.isManager,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/payout-account")) as unknown as PayoutAccount,
  });
  const eligible = useQuery({
    queryKey: ["eligible-accounts"], enabled: perms.loaded && perms.isOwner,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/payout-account/eligible-accounts",
      { params: { query: { limit: 200 } } })).data,
  });
  const changes = useQuery({
    queryKey: ["payout-changes"], enabled: perms.loaded && perms.isManager,
    queryFn: async () => unwrap(await api.GET("/v1/merchant/payout-changes", { params: { query: { limit: 50 } } })).data,
  });
  const request = useRun();
  const decide = useRun();
  const [account, setAccount] = useState("");
  const [reason, setReason] = useState("");
  const [done, setDone] = useState(false);
  const [deciding, setDeciding] = useState<{ c: MerchantChangeRequest; op: "approve" | "reject" } | null>(null);
  const [text, setText] = useState("");
  const who = (id: string) => id === perms.userId ? t("you") : staff.data?.find((u) => u.id === id)?.display_name ?? id.slice(-8);

  if (perms.loaded && !perms.isManager) {
    return <Empty title={t("code_role_not_allowed")}>{t("code_role_not_allowed_hint")}</Empty>;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await request.run(async (key) => unwrap(await api.POST("/v1/merchant/payout-changes", {
      params: { header: { "Idempotency-Key": key } }, body: { settlement_account_id: account, reason: reason.trim() || undefined } })));
    if (r.ok) { setDone(true); setAccount(""); setReason(""); }
  }

  async function confirm() {
    if (!deciding) return;
    const { c, op } = deciding;
    const r = await decide.run(async (key) => op === "approve"
      ? unwrap(await api.POST("/v1/merchant/payout-changes/{change_id}/approve", {
        params: { path: { change_id: c.id }, header: { "Idempotency-Key": key } }, body: { comment: text.trim() || undefined } }))
      : unwrap(await api.POST("/v1/merchant/payout-changes/{change_id}/reject", {
        params: { path: { change_id: c.id }, header: { "Idempotency-Key": key } }, body: { reason: text.trim() } })));
    if (r.ok) setDeciding(null);
  }

  return (
    <>
      <PageHeader title={t("payoutAccount")} subtitle={t("payoutAccountHint")} />
      <Card className="mb-4">
        <Async q={payout}>
          {(p) => (
            <div className="flex flex-col gap-3">
              {p.settlement_account ? (
                <Summary items={[
                  { label: t("account"), value: <span data-testid="payout-account">{p.settlement_account.account_number_masked
                    ?? masked(p.settlement_account.account_number)}</span> },
                  { label: t("status"), value: <Badge value={p.settlement_account.status ?? "—"} /> },
                  { label: t("settlementFrequency"), value: p.settlement_frequency ?? "—" },
                ]} />
              ) : <Notice>{t("noPayoutAccount")}</Notice>}
              {p.pending_change && <Notice tone="warn">{t("pendingChange")}</Notice>}
            </div>
          )}
        </Async>
      </Card>

      {perms.isOwner && (
        <Card title={t("requestChange")} className="mb-4">
          {done && <div className="mb-3"><Notice tone="good">{t("changeRequested")}</Notice></div>}
          <form onSubmit={submit} className="flex flex-col gap-4">
            <Field label={t("newAccount")}>
              <Select required value={account} onChange={(e) => { setAccount(e.target.value); setDone(false); }} placeholder="—"
                options={(eligible.data ?? []).map((a) => ({ value: a.id, label: `${masked(a.account_number)} (${a.currency})` }))} />
            </Field>
            <Field label={t("reason")} optional>
              <Input maxLength={255} value={reason} onChange={(e) => setReason(e.target.value)} />
            </Field>
            <ErrorBanner error={request.error ?? eligible.error} />
            <Button type="submit" disabled={request.pending || !account}>{request.pending ? t("working") : t("requestChange")}</Button>
          </form>
        </Card>
      )}

      <Card title={t("changes")}>
        <Async q={changes} empty={<Empty title={t("noChanges")} />}>
          {(rows) => (
            <ul className="divide-y divide-border">
              {rows.map((c) => {
                const canDecide = c.status === "PENDING_APPROVAL" && c.requested_by !== perms.userId;
                return (
                  <li key={c.id} className="py-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0 text-sm">
                        <p className="font-medium">{c.account_number_masked ?? c.settlement_account_id.slice(-8)}</p>
                        <p className="text-xs text-muted">{t("requestedBy")}: {who(c.requested_by)} · <DateTime value={c.created_at} /></p>
                        {c.reason && <p className="text-xs">{c.reason}</p>}
                      </div>
                      <Badge value={c.status} />
                    </div>
                    {canDecide && (
                      <div className="mt-2 flex gap-2">
                        <Button size="sm" onClick={() => { setText(""); decide.newKey(); setDeciding({ c, op: "approve" }); }}>{t("approve")}</Button>
                        <Button size="sm" variant="danger" onClick={() => { setText(""); decide.newKey(); setDeciding({ c, op: "reject" }); }}>{t("reject")}</Button>
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}
        </Async>
      </Card>
      <Dialog open={!!deciding} onClose={() => setDeciding(null)} title={deciding?.op === "approve" ? t("approve") : t("reject")}
        footer={<>
          <Button variant="secondary" onClick={() => setDeciding(null)} disabled={decide.pending}>{t("cancel")}</Button>
          <Button variant={deciding?.op === "reject" ? "danger" : "primary"} onClick={confirm}
            disabled={decide.pending || (deciding?.op === "reject" && !text.trim())}>
            {decide.pending ? t("working") : deciding?.op === "approve" ? t("approve") : t("reject")}
          </Button>
        </>}>
        <div className="flex flex-col gap-3">
          <Field label={deciding?.op === "approve" ? t("comment") : t("reason")} optional={deciding?.op === "approve"}>
            <Input value={text} onChange={(e) => setText(e.target.value)} maxLength={deciding?.op === "approve" ? 500 : 255} />
          </Field>
          <ErrorBanner error={decide.error} />
        </div>
      </Dialog>
    </>
  );
}
