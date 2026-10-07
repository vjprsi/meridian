"use client";

import { useState, type ReactNode } from "react";
import { stepUp, stepUpFresh } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { useSession } from "./providers";
import { Button, Dialog, ErrorBanner, Summary } from "./ui";

/**
 * Confirmation with step-up for money movement and other sensitive changes. The customer reviews the summary and
 * confirms; if their last strong authentication is older than STEP_UP_MAX_AGE_S, OIDC sessions re-authenticate in
 * a popup (max_age=0) and dev sessions record an explicit confirmation. Only then is onConfirm called.
 */
export function StepUpConfirm({ open, onClose, title, items, warning, confirmLabel, onConfirm, pending, error }:
  { open: boolean; onClose: () => void; title: string; items: { label: ReactNode; value: ReactNode }[];
    warning?: ReactNode; confirmLabel: string; onConfirm: () => void; pending?: boolean; error?: unknown }) {
  const t = useT();
  const { session, setSession } = useSession();
  const [stepError, setStepError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function confirm() {
    setStepError(null);
    if (!session) return;
    if (!stepUpFresh(session)) {
      setBusy(true);
      try {
        setSession(await stepUp(session));
      } catch (e) {
        setStepError(e);
        setBusy(false);
        return;
      }
      setBusy(false);
    }
    onConfirm();
  }

  return (
    <Dialog open={open} onClose={onClose} title={title}
      footer={<>
        <Button variant="secondary" onClick={onClose} disabled={pending || busy}>{t("common.cancel")}</Button>
        <Button onClick={confirm} disabled={pending || busy} aria-describedby="stepup-note">
          {pending || busy ? t("common.working") : confirmLabel}
        </Button>
      </>}>
      <Summary items={items} />
      {warning && <p className="mt-4 rounded-[var(--radius)] bg-warn-soft px-3 py-2 text-warn">{warning}</p>}
      <p id="stepup-note" className="mt-4 text-xs text-muted">
        {session && !stepUpFresh(session)
          ? session.mode === "oidc" ? t("stepup.reauth") : t("stepup.devConfirm")
          : t("stepup.fresh")}
      </p>
      <div className="mt-3 space-y-2">
        <ErrorBanner error={stepError} />
        <ErrorBanner error={error} />
      </div>
    </Dialog>
  );
}
