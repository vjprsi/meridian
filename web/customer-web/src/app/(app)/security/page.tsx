"use client";

import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { idem, me, unwrap } from "@/api/client";
import { useCustomerId, useMySessions, useSecurity } from "@/api/me";
import type { CustomerSession, SecurityOverview } from "@/api/types";
import { useSession } from "@/components/providers";
import { Async, Badge, Button, Card, DateTime, Dialog, Empty, ErrorBanner, Notice, PageHeader, Summary } from "@/components/ui";
import { identityAction, saveSession, signOut } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import type { MessageKey } from "@/messages/en";

/**
 * Security settings: how the customer signed in (Level of Authentication), their second factors (passkeys, security
 * keys, authenticator apps; enrolled and removed at Keycloak through application-initiated actions), their sign-in
 * sessions with sign-out per session and "sign out everywhere".
 */
export default function Security() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("nav.security")} subtitle={t("security.subtitle")} />
      <Suspense fallback={null}><ActionResult /></Suspense>
      <div className="grid gap-5 lg:grid-cols-2">
        <Strength />
        <Factors />
        <Sessions />
      </div>
    </>
  );
}

/** Notice after coming back from Keycloak (kc_action_status: success, cancelled, error). */
function ActionResult() {
  const t = useT();
  const action = useSearchParams().get("action");
  if (!action) return null;
  const key: MessageKey = action === "success" ? "security.actionSuccess"
    : action === "cancelled" ? "security.actionCancelled" : "security.actionError";
  return <div className="mb-4"><Notice tone={action === "success" ? "good" : "warn"}>{t(key)}</Notice></div>;
}

const AMR: Record<string, MessageKey> = { pwd: "security.amr.pwd", otp: "security.amr.otp", hwk: "security.amr.hwk" };

function Strength() {
  const t = useT();
  const sec = useSecurity();
  return (
    <Card title={t("security.strength")}>
      <Async q={sec}>
        {(s: SecurityOverview) => (
          <>
            <Summary items={[
              { label: t("security.level"), value: <Badge value={s.authentication.level >= 2 ? t("security.twoStep") : t("security.passwordOnly")}
                tone={s.authentication.level >= 2 ? "good" : "warn"} /> },
              { label: t("security.methods"), value: s.authentication.amr.length
                ? s.authentication.amr.map((m) => (AMR[m] ? t(AMR[m]) : m)).join(", ") : "—" },
              { label: t("security.lastSignIn"), value: <DateTime value={s.authentication.auth_time ?? undefined} /> },
            ]} />
            <p className="mt-3 text-xs text-muted">
              {t("security.policy", { minutes: String(Math.round(s.step_up.max_age_seconds / 60)) })}
            </p>
          </>
        )}
      </Async>
    </Card>
  );
}

const FACTOR: Record<string, MessageKey> = {
  otp: "security.factor.otp", webauthn: "security.factor.webauthn", "webauthn-passwordless": "security.factor.passkey",
};

function Factors() {
  const t = useT();
  const { session } = useSession();
  const sec = useSecurity();
  const [error, setError] = useState<unknown>(null);
  const [removing, setRemoving] = useState<SecurityOverview["factors"][number] | null>(null);
  const oidc = session?.mode === "oidc";
  const start = (action: string) => {
    setError(null);
    identityAction(action).catch(setError);
  };
  return (
    <Card title={t("security.factors")}>
      <p className="mb-3 text-xs text-muted">{t("security.factorsHint")}</p>
      {!oidc && <Notice tone="warn">{t("security.devFactors")}</Notice>}
      <Async q={sec}>
        {(s: SecurityOverview) => (
          <>
            {oidc && !s.factors_available && <Notice tone="warn">{t("security.factorsUnavailable")}</Notice>}
            {s.factors_available && s.factors.length === 0 && (
              <Notice tone="warn">{t("security.noFactor")}</Notice>
            )}
            {s.factors.length > 0 && (
              <ul className="divide-y divide-border" aria-label={t("security.factors")}>
                {s.factors.map((f) => (
                  <li key={f.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                    <span>
                      <span className="block font-medium">{f.label || (FACTOR[f.type] ? t(FACTOR[f.type]) : f.type)}</span>
                      <span className="block text-xs text-muted">
                        {FACTOR[f.type] ? t(FACTOR[f.type]) : f.type} · {t("security.added")} <DateTime value={f.created_at ?? undefined} dateOnly />
                      </span>
                    </span>
                    <Button variant="secondary" disabled={!oidc} onClick={() => setRemoving(f)}>{t("security.remove")}</Button>
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-4 flex flex-wrap gap-2">
              <Button disabled={!oidc} onClick={() => start(s.enrolment.passkey)}>{t("security.addPasskey")}</Button>
              <Button variant="secondary" disabled={!oidc} onClick={() => start(s.enrolment.security_key)}>{t("security.addKey")}</Button>
              <Button variant="secondary" disabled={!oidc} onClick={() => start(s.enrolment.authenticator_app)}>{t("security.addApp")}</Button>
            </div>
            <ErrorBanner error={error} className="mt-3" />
            <Dialog open={!!removing} onClose={() => setRemoving(null)} title={t("security.removeTitle")}
              footer={<>
                <Button variant="secondary" onClick={() => setRemoving(null)}>{t("common.cancel")}</Button>
                <Button onClick={() => removing && start(s.enrolment.remove.replace("{id}", removing.id))}>{t("security.remove")}</Button>
              </>}>
              <p>{t("security.removeHint")}</p>
            </Dialog>
          </>
        )}
      </Async>
    </Card>
  );
}

function sessionName(s: CustomerSession, unknown: string) {
  return s.device_label || unknown;
}

function Sessions() {
  const t = useT();
  const c = useCustomerId();
  const qc = useQueryClient();
  const { session, setSession } = useSession();
  const list = useMySessions();
  const [revoking, setRevoking] = useState<CustomerSession | null>(null);
  const [everywhere, setEverywhere] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: ["sessions", c] });
  const revoke = useMutation({
    mutationFn: async () => unwrap(await me.POST("/v1/me/sessions/{session_id}/revoke", {
      params: { path: { session_id: revoking!.id! }, header: idem() }, body: { reason: "Signed out by the customer from web banking" } })),
    onSuccess: () => { setRevoking(null); refresh(); },
  });
  const revokeAll = useMutation({
    mutationFn: async (includeCurrent: boolean) => unwrap(await me.POST("/v1/me/sessions/revoke-all", {
      params: { header: idem() }, body: { include_current: includeCurrent } })),
    onSuccess: async (_d, includeCurrent) => {
      setEverywhere(false);
      if (includeCurrent) {
        const s = session;
        saveSession(null);
        setSession(null);
        await signOut(s);
      } else refresh();
    },
  });
  return (
    <Card title={t("security.sessions")} className="lg:col-span-2"
      actions={<Button variant="secondary" onClick={() => { revokeAll.reset(); setEverywhere(true); }}>{t("security.signOutEverywhere")}</Button>}>
      <p className="mb-3 text-xs text-muted">{t("security.sessionsHint")}</p>
      <Async q={list} empty={<Empty title={t("security.noSessions")} />}>
        {(rows) => (
          <ul className="divide-y divide-border" aria-label={t("security.sessions")}>
            {rows.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <span className="min-w-0">
                  <span className="flex flex-wrap items-center gap-2 font-medium">
                    {sessionName(s, t("security.unknownDevice"))}
                    {s.is_current && <Badge value={t("security.thisDevice")} tone="info" />}
                    {s.new_device && <Badge value={t("security.newDevice")} tone="warn" />}
                    <Badge value={s.status} />
                  </span>
                  <span className="block text-xs text-muted">
                    {s.last_ip_address ?? s.ip_address ?? "—"} · {t("security.signedIn")} <DateTime value={s.first_seen_at} /> · {t("profile.lastSeen")} <DateTime value={s.last_seen_at} />
                  </span>
                </span>
                {s.status === "ACTIVE" && !s.is_current && (
                  <Button variant="secondary" onClick={() => { revoke.reset(); setRevoking(s); }}>{t("security.signOutSession")}</Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Async>

      <Dialog open={!!revoking} onClose={() => setRevoking(null)} title={t("security.signOutSessionTitle")}
        footer={<>
          <Button variant="secondary" onClick={() => setRevoking(null)} disabled={revoke.isPending}>{t("common.cancel")}</Button>
          <Button onClick={() => revoke.mutate()} disabled={revoke.isPending}>{revoke.isPending ? t("common.working") : t("security.signOutSession")}</Button>
        </>}>
        {revoking && <Summary items={[
          { label: t("profile.device"), value: sessionName(revoking, t("security.unknownDevice")) },
          { label: t("security.signedIn"), value: <DateTime value={revoking.first_seen_at} /> },
        ]} />}
        <ErrorBanner error={revoke.error} className="mt-3" />
      </Dialog>

      <Dialog open={everywhere} onClose={() => setEverywhere(false)} title={t("security.signOutEverywhereTitle")}
        footer={<>
          <Button variant="secondary" onClick={() => setEverywhere(false)} disabled={revokeAll.isPending}>{t("common.cancel")}</Button>
          <Button variant="secondary" onClick={() => revokeAll.mutate(false)} disabled={revokeAll.isPending}>{t("security.signOutOthers")}</Button>
          <Button onClick={() => revokeAll.mutate(true)} disabled={revokeAll.isPending}>{revokeAll.isPending ? t("common.working") : t("security.signOutAll")}</Button>
        </>}>
        <p>{t("security.signOutEverywhereHint")}</p>
        <ErrorBanner error={revokeAll.error} className="mt-3" />
      </Dialog>
    </Card>
  );
}
