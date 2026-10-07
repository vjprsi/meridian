"use client";

import { useState, type FormEvent } from "react";
import { Button, Field, LinkButton, Notice, PinInput, ProblemBox } from "@/components/ui";
import type { AgentProfile } from "@/lib/agent-context";
import { useT } from "@/lib/i18n";
import { formatMoney } from "@/lib/money";
import { rememberAgentPin, syncNow, useOutbox } from "@/lib/outbox/runtime";
import type { OpKind, OutboxView } from "@/lib/outbox/types";

/** Printable / shareable receipt. Plain text so it prints on narrow thermal printers and shares over SMS/WhatsApp. */
export function ReceiptView({ text, provisional }: { text: string; provisional?: boolean }) {
  const t = useT();
  const [copied, setCopied] = useState(false);
  async function share() {
    try {
      if (navigator.share) await navigator.share({ title: t("receipt"), text });
      else {
        await navigator.clipboard.writeText(text);
        setCopied(true);
      }
    } catch { /* cancelled */ }
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="print-area rounded-[var(--radius)] border border-border bg-surface p-4">
        {provisional && <p className="mb-2 font-bold">{t("provisional")}</p>}
        <pre data-testid="receipt" className="whitespace-pre-wrap font-mono text-sm">{text}</pre>
      </div>
      <div className="no-print grid grid-cols-2 gap-2">
        <Button variant="secondary" onClick={() => window.print()}>{t("print")}</Button>
        <Button variant="secondary" onClick={share}>{copied ? "✓" : t("share")}</Button>
      </div>
    </div>
  );
}

const KIND_LABEL: Record<OpKind, string> = {
  CASH_IN: "CASH-IN", BILL_PAYMENT: "BILL PAYMENT", AIRTIME: "AIRTIME", FLOAT_REQUEST: "FLOAT REQUEST", ONBOARDING: "ONBOARDING",
};

/** Receipt text for a captured operation (provisional until synced; then enriched with the server reference). */
export function receiptText(v: OutboxView, p: AgentProfile | null): string {
  const body = (v.result?.body ?? {}) as Record<string, unknown>;
  const lines = [
    "MERIDIAN AGENT RECEIPT",
    KIND_LABEL[v.kind],
    "------------------------------",
    `Agent:   ${p?.agentName ?? ""} (${p?.agentCode ?? ""})`,
    p?.outletName ? `Outlet:  ${p.outletName}` : "",
    `Date:    ${new Date(v.createdAt).toLocaleString()}`,
    ...Object.entries(v.payload.display).filter(([k]) => k !== "client_ref").map(([k, val]) => `${k.padEnd(8)} ${val}`),
    v.payload.amount ? `Amount:  ${formatMoney(v.payload.amount, v.payload.currency)}` : "",
    body.fee_amount ? `Fee:     ${formatMoney(String(body.fee_amount), v.payload.currency)}` : "",
    body.reference ? `Ref:     ${String(body.reference)}` : "",
    body.token ? `Token:   ${String(body.token)}` : "",
    body.application_id ? `Application: ${String(body.application_id)}` : "",
    `Status:  ${v.status === "SYNCED" ? String(body.status ?? "ACCEPTED") : v.status}`,
    `Local ID: ${v.id.slice(0, 8)} #${v.seq}`,
    "------------------------------",
    v.status === "PENDING" ? "Provisional: valid once synced." : "",
  ];
  return lines.filter(Boolean).join("\n");
}

/**
 * Outcome of a captured money operation: completed, saved for sync (provisional receipt), rejected with the
 * server's problem, or paused waiting for the agent's wallet PIN.
 */
export function OpOutcome({ view: initial, onNew, serverReceipt }:
  { view: OutboxView; onNew: () => void; serverReceipt?: (body: Record<string, unknown>) => string | undefined }) {
  const t = useT();
  const ob = useOutbox();
  const view = ob.views.find((v) => v.id === initial.id) ?? initial;
  const [pin, setPin] = useState("");
  const [busy, setBusy] = useState(false);

  async function retryWithPin(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    rememberAgentPin(pin);
    setPin("");
    await syncNow(view.id);
    setBusy(false);
  }

  const body = (view.result?.body ?? {}) as { status?: string };
  return (
    <div className="flex flex-col gap-4" data-testid="op-outcome" data-status={view.status}>
      {view.status === "SYNCED" && (
        <Notice tone="good">{t("completed")}{body.status ? ` — ${body.status.replaceAll("_", " ")}` : ""}{view.result?.duplicate ? " (✓ idempotent replay)" : ""}</Notice>
      )}
      {view.status === "PENDING" && view.blocker !== "NEEDS_PIN" && <Notice tone="warn">{t("savedOffline")}</Notice>}
      {view.status === "PENDING" && view.blocker === "NEEDS_PIN" && (
        <form onSubmit={retryWithPin} className="flex flex-col gap-3">
          <ProblemBox problem={view.result?.problem} />
          <Field label={t("agentPin")} hint={t("agentPinHint")}>
            <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} required />
          </Field>
          <Button type="submit" disabled={busy || !pin}>{t("confirm")}</Button>
        </form>
      )}
      {(view.status === "REJECTED" || view.status === "NEEDS_REVIEW") && (
        <ProblemBox title={view.status === "REJECTED" ? t("rejected") : t("needsReview")} problem={view.result?.problem ?? { detail: view.result?.note }} />
      )}
      {view.status !== "REJECTED" && (
        <LinkButton variant="secondary"
          href={(view.status === "SYNCED" && serverReceipt?.(body as Record<string, unknown>)) || `/receipt?kind=outbox&id=${view.id}`}>
          {t("receipt")}
        </LinkButton>
      )}
      <Button onClick={onNew}>{t("newOperation")}</Button>
    </div>
  );
}
