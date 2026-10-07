"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { api, problemOf, unwrap, type Agent, type AgentOutlet } from "@/api/client";
import { useSession } from "@/components/providers";
import { Button, Card, Field, Notice, PinInput, ProblemBox, Spinner, cx } from "@/components/ui";
import { ConnectivityPill } from "@/components/shell";
import { bindingOf, loadProfile, saveProfile, type AgentProfile } from "@/lib/agent-context";
import { setSigningUnlocked } from "@/lib/device-key";
import { registerDevice } from "@/lib/devices";
import { useT } from "@/lib/i18n";
import { outbox, setupPin, unlock } from "@/lib/outbox/runtime";
import type { Problem } from "@/lib/outbox/types";

/**
 * First run on a device (or a new agent): the agent comes from the sign-in (/v1/agent/me), the agent picks the outlet,
 * the device is registered with its own signing key (device binding) and the app PIN that protects the offline
 * outbox is set.
 */
export default function SetupPage() {
  const t = useT();
  const router = useRouter();
  const { session, ready } = useSession();
  const [outlet, setOutlet] = useState<AgentOutlet | null>(null);
  const [step, setStep] = useState<"outlet" | "pin">("outlet");

  useEffect(() => {
    if (ready && !session) router.replace("/login");
  }, [ready, session, router]);

  const me = useQuery({
    queryKey: ["setup-me", session?.agentId, session?.tenantId],
    enabled: !!session,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me")),
  });
  const outlets = useQuery({
    queryKey: ["setup-outlets", me.data?.id],
    enabled: !!me.data,
    queryFn: async () => unwrap(await api.GET("/v1/agent/me/outlets")).data,
  });

  if (!ready || !session) return <Spinner />;
  const agent = me.data;

  return (
    <main className="mx-auto flex max-w-xl flex-col gap-4 px-4 py-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("welcome")}</h1>
        <ConnectivityPill />
      </div>
      <ol className="flex gap-2 text-sm font-semibold" aria-label="Steps">
        {(["outlet", "pin"] as const).map((s, i) => (
          <li key={s} className={cx("rounded-full px-3 py-1", s === step ? "bg-accent text-accent-fg" : "bg-surface-2 text-muted")}>
            {i + 1}. {s === "outlet" ? t("chooseOutlet") : t("appPin")}
          </li>
        ))}
      </ol>
      {me.isLoading && <Spinner />}
      {me.error && <ProblemBox problem={problemOf(me.error)} />}

      {step === "outlet" && agent && (
        <Card title={t("chooseOutlet")}>
          <p className="mb-3 text-muted">{agent.trading_name ?? agent.legal_name} · {agent.agent_code} · {agent.agent_level.replace("_", " ")}</p>
          {outlets.isLoading && <Spinner />}
          {outlets.error && <ProblemBox problem={problemOf(outlets.error)} />}
          <ul className="flex flex-col gap-2">
            {outlets.data?.filter((o) => o.status === "ACTIVE").map((o) => (
              <li key={o.id}>
                <button onClick={() => { setOutlet(o); setStep("pin"); }}
                  className={cx("flex min-h-14 w-full flex-col rounded-[var(--radius)] border-2 bg-surface px-3 py-2 text-left hover:border-accent",
                    outlet?.id === o.id ? "border-accent" : "border-border")}>
                  <span className="font-semibold">{o.name}</span>
                  <span className="text-sm text-muted">{o.outlet_code}{o.city ? ` · ${o.city}` : ""}</span>
                </button>
              </li>
            ))}
            {outlets.data?.length === 0 && <p className="text-muted">{t("nothing")}</p>}
          </ul>
        </Card>
      )}

      {step === "pin" && agent && outlet && (
        <PinStep agent={agent} outlet={outlet} tenantId={session.tenantId} onBack={() => setStep("outlet")}
          onDone={() => router.replace("/")} />
      )}
    </main>
  );
}

function PinStep({ agent, outlet, tenantId, onBack, onDone }:
  { agent: Agent; outlet: AgentOutlet; tenantId: string; onBack: () => void; onDone: () => void }) {
  const t = useT();
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [existing, setExisting] = useState<boolean | null>(null);
  const binding = bindingOf({ tenantId, agentId: agent.id });

  useEffect(() => { outbox.isInitialized(binding).then(setExisting); }, [binding]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setProblem(null);
    if (!existing && pin !== pin2) { setErr(t("pinsDiffer")); return; }
    setBusy(true);
    try {
      const prev = loadProfile();
      if (existing) {
        if (!(await unlock(pin))) { setErr(t("wrongPin")); return; }
      } else {
        await setupPin(pin, binding);
        setSigningUnlocked(true);
      }
      const balance = await api.GET("/v1/agent/me/float-balance");
      const floatAvailable = balance.data?.available_balance ?? null;
      const deviceId = await registerDevice(outlet.id);
      const profile: AgentProfile = {
        tenantId, agentId: agent.id, agentCode: agent.agent_code, agentName: agent.trading_name ?? agent.legal_name,
        agentLevel: agent.agent_level, parentAgentId: agent.parent_agent_id, superAgentId: agent.super_agent_id,
        currency: agent.currency, floatWalletId: agent.float_wallet_id ?? outlet.float_wallet_id,
        commissionWalletId: agent.commission_wallet_id, outletId: outlet.id, outletName: outlet.name,
        deviceId: deviceId ?? (prev?.agentId === agent.id ? prev.deviceId : null),
        floatAvailable, floatAt: floatAvailable ? new Date().toISOString() : null,
      };
      saveProfile(profile);
      onDone();
    } catch (x) {
      setProblem(problemOf(x));
    } finally {
      setBusy(false);
      setPin("");
      setPin2("");
    }
  }

  return (
    <Card title={existing ? t("unlockTitle") : t("setAppPin")}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <p className="text-muted">{agent.trading_name ?? agent.legal_name} · {agent.agent_code} · {outlet.name}</p>
        {!existing && <Notice>{t("appPinHint")}</Notice>}
        <Field label={t("appPin")} error={err}>
          <PinInput value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ""))} minLength={6} maxLength={6} required />
        </Field>
        {existing === false && (
          <Field label={t("repeatPin")}>
            <PinInput value={pin2} onChange={(e) => setPin2(e.target.value.replace(/\D/g, ""))} minLength={6} maxLength={6} required />
          </Field>
        )}
        <ProblemBox problem={problem} />
        <div className="flex gap-2">
          <Button type="button" variant="secondary" onClick={onBack}>{t("back")}</Button>
          <Button type="submit" className="flex-1" disabled={busy || pin.length !== 6}>{t("continue")}</Button>
        </div>
      </form>
    </Card>
  );
}
