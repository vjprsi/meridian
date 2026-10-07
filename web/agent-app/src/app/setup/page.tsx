"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { agents, idem, problemOf, unwrap, wallet, type Agent, type AgentOutlet } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Button, Card, Field, Input, Notice, PinInput, ProblemBox, Spinner, cx } from "@/components/ui";
import { ConnectivityPill } from "@/components/shell";
import { bindingOf, loadProfile, saveProfile, type AgentProfile } from "@/lib/agent-context";
import { deviceModel, deviceSerial, deviceType } from "@/lib/device";
import { useT } from "@/lib/i18n";
import { config } from "@/lib/config";
import { outbox, setupPin, unlock } from "@/lib/outbox/runtime";
import type { Problem } from "@/lib/outbox/types";

/**
 * First run on a device (or switching agent): pick the agent profile and outlet, link this device, and set the
 * app PIN that protects the offline outbox. The platform has no "agent of the signed-in user" lookup, so the agent
 * is chosen from the agent list (searchable by agent code).
 */
export default function SetupPage() {
  const t = useT();
  const router = useRouter();
  const { session, ready } = useSession();
  const [code, setCode] = useState("");
  const [search, setSearch] = useState("");
  const [agent, setAgent] = useState<Agent | null>(null);
  const [outlet, setOutlet] = useState<AgentOutlet | null>(null);
  const [step, setStep] = useState<"agent" | "outlet" | "pin">("agent");

  useEffect(() => {
    if (ready && !session) router.replace("/login");
  }, [ready, session, router]);

  const list = useQuery({
    queryKey: ["setup-agents", search],
    enabled: !!session,
    queryFn: async () => unwrap(await agents.GET("/v1/agents", { params: { query: {
      limit: 50, status: "ACTIVE", ...(search ? { agent_code: search } : {}) } } })).data,
  });

  const outlets = useQuery({
    queryKey: ["setup-outlets", agent?.id],
    enabled: !!agent,
    queryFn: async () => unwrap(await agents.GET("/v1/agents/{id}/outlets", { params: { path: { id: agent!.id } } })).data,
  });

  if (!ready || !session) return <Spinner />;

  return (
    <main className="mx-auto flex max-w-xl flex-col gap-4 px-4 py-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t("welcome")}</h1>
        <ConnectivityPill />
      </div>
      <ol className="flex gap-2 text-sm font-semibold" aria-label="Steps">
        {(["agent", "outlet", "pin"] as const).map((s, i) => (
          <li key={s} className={cx("rounded-full px-3 py-1", s === step ? "bg-accent text-accent-fg" : "bg-surface-2 text-muted")}>
            {i + 1}. {s === "agent" ? t("chooseAgent").split(" ").slice(-2).join(" ") : s === "outlet" ? t("chooseOutlet") : t("appPin")}
          </li>
        ))}
      </ol>

      {step === "agent" && (
        <Card title={t("chooseAgent")}>
          <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setSearch(code.trim()); }}>
            <Input aria-label={t("agentCode")} placeholder={t("agentCode")} value={code} onChange={(e) => setCode(e.target.value)} />
            <Button type="submit" variant="secondary">{t("search")}</Button>
          </form>
          {list.isLoading && <Spinner />}
          {list.error && <ProblemBox problem={problemOf(list.error)} />}
          <ul className="flex flex-col gap-2">
            {list.data?.map((a) => (
              <li key={a.id}>
                <button onClick={() => { setAgent(a); setStep("outlet"); }}
                  className="flex min-h-14 w-full items-center justify-between gap-3 rounded-[var(--radius)] border-2 border-border bg-surface px-3 py-2 text-left hover:border-accent">
                  <span>
                    <span className="block font-semibold">{a.trading_name ?? a.legal_name}</span>
                    <span className="block text-sm text-muted">{a.agent_code} · {a.agent_level.replace("_", " ")} · {a.currency}</span>
                  </span>
                  <Badge value={a.status} />
                </button>
              </li>
            ))}
            {list.data?.length === 0 && <p className="text-muted">{t("nothing")}</p>}
          </ul>
        </Card>
      )}

      {step === "outlet" && agent && (
        <Card title={t("chooseOutlet")}>
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
          </ul>
          <div className="mt-4 flex gap-2">
            <Button variant="secondary" onClick={() => setStep("agent")}>{t("back")}</Button>
            <Button variant="ghost" onClick={() => { setOutlet(null); setStep("pin"); }}>{t("continue")} →</Button>
          </div>
        </Card>
      )}

      {step === "pin" && agent && (
        <PinStep agent={agent} outlet={outlet} tenantId={session.tenantId} onBack={() => setStep("outlet")}
          onDone={() => router.replace("/")} />
      )}
    </main>
  );
}

function PinStep({ agent, outlet, tenantId, onBack, onDone }:
  { agent: Agent; outlet: AgentOutlet | null; tenantId: string; onBack: () => void; onDone: () => void }) {
  const t = useT();
  const [pin, setPin] = useState("");
  const [pin2, setPin2] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);
  const [existing, setExisting] = useState<boolean | null>(null);
  const binding = bindingOf({ tenantId, agentId: agent.id });

  useEffect(() => { outbox.isInitialized(binding).then(setExisting); }, [binding]);

  async function linkDevice(): Promise<string | null> {
    // Re-use this installation's device record if it is registered, else register it (best effort).
    const serial = deviceSerial();
    const found = await agents.GET("/v1/agent-devices", { params: { query: { agent_id: agent.id, serial_number: serial } } });
    const hit = found.data?.data?.[0];
    if (hit) return hit.id;
    const created = await agents.POST("/v1/agent-devices", { params: { header: idem() }, body: {
      agent_id: agent.id, agent_outlet_id: outlet?.id, device_type: deviceType(), serial_number: serial,
      model: deviceModel(), app_version: config.appVersion, assigned_at: new Date().toISOString(),
    } });
    return created.data?.id ?? null;
  }

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
      }
      let floatAvailable: string | null = null;
      if (agent.float_wallet_id) {
        const b = await wallet.GET("/v1/wallets/{id}/balance", { params: { path: { id: agent.float_wallet_id } } });
        floatAvailable = b.data?.available_balance ?? null;
      }
      const deviceId = await linkDevice().catch(() => null);
      const profile: AgentProfile = {
        tenantId, agentId: agent.id, agentCode: agent.agent_code, agentName: agent.trading_name ?? agent.legal_name,
        agentLevel: agent.agent_level, parentAgentId: agent.parent_agent_id, superAgentId: agent.super_agent_id,
        currency: agent.currency, floatWalletId: agent.float_wallet_id ?? outlet?.float_wallet_id,
        commissionWalletId: agent.commission_wallet_id, outletId: outlet?.id ?? null, outletName: outlet?.name ?? null,
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
        <p className="text-muted">{agent.trading_name ?? agent.legal_name} · {agent.agent_code}{outlet ? ` · ${outlet.name}` : ""}</p>
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
