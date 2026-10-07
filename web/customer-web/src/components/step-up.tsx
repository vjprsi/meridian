"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import { isStepUpRequired } from "@/api/client";
import { useSecurity } from "@/api/me";
import { stepUp, stepUpFresh } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { useSession } from "./providers";
import { Button, Dialog, ErrorBanner, Summary } from "./ui";

/**
 * Confirmation with step-up for money movement and other sensitive changes. The customer reviews the summary and
 * confirms; unless their last authentication is a recent two-factor one (acr 2, STEP_UP_MAX_AGE_S), OIDC sessions
 * step up in a popup (acr_values=2 with max_age: Keycloak asks for the passkey / security key / authenticator app,
 * and the password too after max_age) and dev sessions record an explicit confirmation. When the server still answers
 * 401 step_up_required, the next confirmation steps up with exactly what its challenge asked (acr_values, max_age).
 * Customers without a second factor are sent to Security to add one first.
 */
export function StepUpConfirm({ open, onClose, title, items, warning, confirmLabel, onConfirm, pending, error }:
  { open: boolean; onClose: () => void; title: string; items: { label: ReactNode; value: ReactNode }[];
    warning?: ReactNode; confirmLabel: string; onConfirm: () => void; pending?: boolean; error?: unknown }) {
  const t = useT();
  const { session, setSession } = useSession();
  const [stepError, setStepError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const security = useSecurity(open && session?.mode === "oidc");
  const noFactor = session?.mode === "oidc" && security.data?.has_second_factor === false;
  const challenged = isStepUpRequired(error);

  async function confirm() {
    setStepError(null);
    if (!session) return;
    if (!stepUpFresh(session) || challenged) {
      setBusy(true);
      try {
        setSession(await stepUp(session, challenged ? error.challenge : {}));
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
        <Button onClick={confirm} disabled={pending || busy || noFactor} aria-describedby="stepup-note">
          {pending || busy ? t("common.working") : confirmLabel}
        </Button>
      </>}>
      <Summary items={items} />
      {warning && <p className="mt-4 rounded-[var(--radius)] bg-warn-soft px-3 py-2 text-warn">{warning}</p>}
      {noFactor ? (
        <p id="stepup-note" role="alert" className="mt-4 rounded-[var(--radius)] bg-warn-soft px-3 py-2 text-sm text-warn">
          {t("stepup.noFactor")} <Link href="/security" className="font-semibold underline">{t("nav.security")}</Link>
        </p>
      ) : (
        <p id="stepup-note" className="mt-4 text-xs text-muted">
          {session && (!stepUpFresh(session) || challenged)
            ? session.mode === "oidc" ? t("stepup.reauth") : t("stepup.devConfirm")
            : t("stepup.fresh")}
        </p>
      )}
      <div className="mt-3 space-y-2">
        <ErrorBanner error={stepError} />
        {!challenged && <ErrorBanner error={error} />}
      </div>
    </Dialog>
  );
}
