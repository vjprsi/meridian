"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { SyncButton } from "@/components/shell";
import { Badge, Button, Card, DateTime, Field, Money, Notice, PageTitle, PinInput, ProblemBox, Row } from "@/components/ui";
import { offlinePolicy } from "@/lib/config";
import { useConnectivity } from "@/lib/connectivity";
import { useT } from "@/lib/i18n";
import { outbox, refresh, rememberAgentPin, syncNow, useOutbox } from "@/lib/outbox/runtime";
import { sum } from "@/lib/money";

/**
 * The outbox: every operation captured on this device in order, with its sync state. Pending operations are sent
 * automatically (back online, background sync, every minute) or with "Sync now"; rejected ones show the server's
 * problem; nothing pending can be deleted.
 */
export default function SyncPage() {
  const t = useT();
  const ob = useOutbox();
  const conn = useConnectivity();
  const [pin, setPin] = useState("");
  const pending = ob.views.filter((v) => v.status === "PENDING");
  const blocked = ob.last?.blocked ?? pending.find((v) => v.blocker)?.blocker;

  async function withPin(e: FormEvent) {
    e.preventDefault();
    rememberAgentPin(pin);
    setPin("");
    await syncNow();
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle sub={t("outbox")}>{t("sync")}</PageTitle>
      <Card>
        <dl>
          <Row label={t("pending")}><span data-testid="pending-count">{pending.length}</span></Row>
          <Row label={t("total")}>
            <Money amount={sum(pending.map((v) => v.payload.amount))} currency={pending[0]?.payload.currency} />
          </Row>
          {ob.last && <Row label={t("syncNow")}><DateTime value={ob.last.at} /> · ✓{ob.last.synced} ✕{ob.last.rejected}</Row>}
        </dl>
        <div className="mt-3 flex flex-col gap-2">
          <SyncButton className="w-full" />
          {conn === "offline" && <Notice tone="warn">{t("offline")}</Notice>}
        </div>
      </Card>

      {blocked === "NEEDS_PIN" && pending.length > 0 && (
        <Card title={t("queueBlocked")}>
          <form onSubmit={withPin} className="flex flex-col gap-3">
            <ProblemBox problem={pending.find((v) => v.blocker === "NEEDS_PIN")?.result?.problem} />
            <Field label={t("agentPin")} hint={t("agentPinHint")}>
              <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required />
            </Field>
            <Button type="submit" disabled={!pin || conn !== "online"}>{t("syncNow")}</Button>
          </form>
        </Card>
      )}
      {blocked === "AUTH" && <Notice tone="warn"><Link href="/login" className="underline">{t("needLoginToSync")}</Link></Notice>}

      <ul className="flex flex-col gap-2" data-testid="outbox-list">
        {[...ob.views].reverse().map((v) => (
          <li key={v.id} className="rounded-[var(--radius)] border border-border bg-surface p-3">
            <div className="flex items-start justify-between gap-2">
              <div className="min-w-0">
                <p className="font-semibold">#{v.seq} {v.kind.replaceAll("_", " ")} {v.payload.amount && <Money amount={v.payload.amount} currency={v.payload.currency} />}</p>
                <p className="truncate text-sm text-muted">{Object.entries(v.payload.display).filter(([k]) => k !== "client_ref").map(([, x]) => x).join(" · ")}</p>
                <p className="text-xs text-muted"><DateTime value={v.createdAt} />{v.createdOffline ? " · offline" : ""} · {v.attempts}×</p>
              </div>
              <Badge value={v.status === "PENDING" && v.blocker ? `${v.status} ${v.blocker}` : v.status} />
            </div>
            {(v.status === "REJECTED" || v.status === "NEEDS_REVIEW") && (
              <div className="mt-2"><ProblemBox problem={v.result?.problem ?? { detail: v.result?.note }} /></div>
            )}
            {v.status === "PENDING" && v.blocker === "NETWORK" && v.result?.problem && (
              <p className="mt-1 text-sm text-warn">{v.result.problem.title ?? v.result.problem.detail}</p>
            )}
            <div className="mt-2 flex gap-2">
              {v.status !== "REJECTED" && <Link className="font-semibold text-accent underline" href={`/receipt?kind=outbox&id=${v.id}`}>{t("receipt")}</Link>}
              {v.status !== "PENDING" && (
                <button className="font-semibold text-bad underline" onClick={async () => { await outbox.remove(v.id); await refresh(); }}>{t("remove")}</button>
              )}
            </div>
          </li>
        ))}
      </ul>
      {ob.views.length === 0 && <p className="text-muted">{t("nothing")}</p>}

      <Card title={t("offlineLimits")}>
        <dl>
          <Row label={t("maxPerOp")}>{offlinePolicy.maxAmountPerOp}</Row>
          <Row label={t("maxTotal")}>{offlinePolicy.maxTotalAmount}</Row>
          <Row label={t("maxCount")}>{offlinePolicy.maxCount}</Row>
          <Row label={t("maxHours")}>{offlinePolicy.maxOfflineHours}</Row>
        </dl>
      </Card>
    </div>
  );
}
