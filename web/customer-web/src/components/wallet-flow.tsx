"use client";

import Link from "next/link";
import { useEffect, useRef, type ReactNode } from "react";
import { useMutation } from "@tanstack/react-query";
import type { Wallet } from "@/api/types";
import { useT } from "@/lib/i18n";
import { StepUpConfirm } from "./step-up";
import { Badge, Card, Field, LinkButton, Money, Select, Summary, cx } from "./ui";

/** "Pay from" wallet chooser; hidden to a static line when the customer has a single wallet. */
export function WalletPicker({ wallets, value, onChange }:
  { wallets: Wallet[]; value: string; onChange: (id: string) => void }) {
  const t = useT();
  if (wallets.length <= 1) {
    const w = wallets[0];
    return (
      <div className="rounded-[var(--radius)] border border-border bg-surface-2 px-3 py-2 text-sm">
        <span className="text-muted">{t("wallet.payFrom")}: </span>
        <span className="font-medium">{w?.display_name}</span>
        {w && <span className="text-muted"> · {w.msisdn ?? w.wallet_number}</span>}
      </div>
    );
  }
  return (
    <Field label={t("wallet.payFrom")}>
      <Select value={value} onChange={(e) => onChange(e.target.value)}
        options={wallets.map((w) => ({ value: w.id!, label: `${w.display_name} · ${w.msisdn ?? w.wallet_number} (${w.currency})` }))} />
    </Field>
  );
}

/**
 * Confirm-and-run for wallet money movement. It mirrors the bank payment flow: the customer reviews the summary, then
 * confirms with step-up (StepUpConfirm asks for a second factor / dev confirmation when the sign-in is not recent, and
 * retries with exactly what a 401 step_up_required challenge asks for). One Idempotency-Key is minted when the dialog
 * opens and kept for the whole confirmation, so a resubmit after a timeout cannot post twice.
 */
export function MoneyConfirm<T>({ open, onClose, title, items, warning, confirmLabel, run, onDone }:
  { open: boolean; onClose: () => void; title: string; items: { label: ReactNode; value: ReactNode }[];
    warning?: ReactNode; confirmLabel: string; run: (idempotencyKey: string) => Promise<T>; onDone: (result: T) => void }) {
  const key = useRef<string>("");
  useEffect(() => { if (open) key.current = crypto.randomUUID(); }, [open]);
  const m = useMutation({ mutationFn: () => run(key.current), onSuccess: onDone });
  return (
    <StepUpConfirm open={open} onClose={() => { if (!m.isPending) { m.reset(); onClose(); } }}
      title={title} items={items} warning={warning} confirmLabel={confirmLabel}
      onConfirm={() => m.mutate()} pending={m.isPending} error={m.error} />
  );
}

/** Shared success / receipt panel shown after a completed wallet action. */
export function WalletReceipt({ amount, currency, status, items, note }:
  { amount?: string | null; currency?: string | null; status?: string | null;
    items: { label: ReactNode; value: ReactNode }[]; note?: ReactNode }) {
  const t = useT();
  return (
    <Card className="max-w-xl">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <Money amount={amount} currency={currency} className="text-2xl font-semibold" />
        {status && <Badge value={status} />}
      </div>
      <Summary items={items} />
      {note && <p className="mt-4 text-sm text-muted">{note}</p>}
      <div className="mt-5 flex flex-wrap gap-2">
        <LinkButton href="/wallet">{t("wallet.done")}</LinkButton>
      </div>
    </Card>
  );
}

/** A square action tile for the wallet home. */
export function ActionTile({ href, label, icon }: { href: string; label: string; icon: string }) {
  return (
    <Link href={href}
      className={cx("flex min-h-24 flex-col items-center justify-center gap-2 rounded-[var(--radius)] border border-border bg-surface px-3 py-4 text-center text-sm font-medium",
        "hover:border-accent focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-focus")}>
      <svg aria-hidden="true" viewBox="0 0 24 24" className="h-6 w-6 text-accent" fill="none" stroke="currentColor"
        strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><path d={icon} /></svg>
      {label}
    </Link>
  );
}
