"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, problemOf, unwrap } from "@/api/client";
import { useSession } from "@/components/providers";
import { Badge, Button, Card, DateTime, Field, PageTitle, ProblemBox, Row, Select } from "@/components/ui";
import { signOut } from "@/lib/auth";
import { updateProfile, useProfile } from "@/lib/agent-context";
import { config } from "@/lib/config";
import { registerDevice } from "@/lib/devices";
import { isForcedOffline, setForcedOffline } from "@/lib/connectivity";
import { geoEnabled, lastHeartbeat, sendHeartbeat, setGeoEnabled } from "@/lib/heartbeat";
import { setLang, useLang, useT } from "@/lib/i18n";
import { lockApp, useOutbox } from "@/lib/outbox/runtime";
import type { Problem } from "@/lib/outbox/types";
import { useLive } from "@/lib/queries";

interface InstallPrompt extends Event { prompt(): Promise<void> }

export default function SettingsPage() {
  const t = useT();
  const lang = useLang();
  const router = useRouter();
  const p = useProfile();
  const live = useLive();
  const ob = useOutbox();
  const { session } = useSession();
  // The app shell renders pages only after hydration, so reading device storage in initialisers is safe.
  const [beat, setBeat] = useState<string | null>(() => lastHeartbeat());
  const [geo, setGeo] = useState(() => geoEnabled());
  const [training, setTraining] = useState(() => isForcedOffline());
  const [problem, setProblem] = useState<Problem | null>(null);
  const [install, setInstall] = useState<InstallPrompt | null>(null);

  useEffect(() => {
    const h = (e: Event) => { e.preventDefault(); setInstall(e as InstallPrompt); };
    window.addEventListener("beforeinstallprompt", h);
    return () => window.removeEventListener("beforeinstallprompt", h);
  }, []);

  const device = useQuery({
    queryKey: ["device", p?.deviceId],
    enabled: live && !!p?.deviceId,
    queryFn: async () => unwrap(await api.GET("/v1/agent/devices/{device_id}", { params: { path: { device_id: p!.deviceId! } } })),
  });
  const keys = useQuery({
    queryKey: ["device-keys", p?.deviceId],
    enabled: live && !!p?.deviceId,
    queryFn: async () => unwrap(await api.GET("/v1/agent/devices/{device_id}/keys", { params: { path: { device_id: p!.deviceId! } } })).data,
  });

  if (!p) return null;
  const activeKey = keys.data?.find((k) => k.status === "ACTIVE");

  /** New device key pair and registration (the previous key is revoked by the server); needs a recent sign-in. */
  async function reRegister() {
    setProblem(null);
    try {
      const deviceId = await registerDevice(p!.outletId!, true);
      updateProfile({ deviceId });
      void keys.refetch();
      void device.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  async function heartbeat() {
    setProblem(null);
    try {
      await sendHeartbeat();
      setBeat(lastHeartbeat());
      void device.refetch();
    } catch (x) {
      setProblem(problemOf(x));
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <PageTitle>{t("settings")}</PageTitle>
      <Card>
        <Field label={t("language")}>
          <Select value={lang} onChange={(e) => setLang(e.target.value as "en" | "sw")}
            options={[{ value: "en", label: "English" }, { value: "sw", label: "Kiswahili" }]} />
        </Field>
      </Card>

      <Card title={t("deviceStatus")}>
        <dl>
          <Row label={t("device")}>{device.data ? <>{device.data.serial_number} <Badge value={device.data.status} /></> : (p.deviceId ?? "—")}</Row>
          {device.data?.last_seen_at && <Row label={t("heartbeat")}><DateTime value={device.data.last_seen_at} /></Row>}
          {!device.data && beat && <Row label={t("heartbeat")}><DateTime value={beat} /></Row>}
          <Row label={t("deviceKey")}>
            {activeKey ? <><span className="font-mono">{activeKey.key_algorithm} {activeKey.key_fingerprint.slice(0, 16)}</span> <Badge value={activeKey.status} /></>
              : keys.data ? <Badge value="REVOKED" /> : "—"}
          </Row>
          <Row label="App">{config.appVersion}</Row>
        </dl>
        {keys.data && !activeKey && <p className="mt-2 text-sm text-muted">{t("deviceNotRegistered")}</p>}
        <Button variant="secondary" block className="mt-2" disabled={!live || !p.outletId} onClick={reRegister}>{t("registerDevice")}</Button>
        <label className="mt-3 flex min-h-12 items-center gap-3">
          <input type="checkbox" className="h-6 w-6" checked={geo} onChange={(e) => { setGeo(e.target.checked); setGeoEnabled(e.target.checked); }} />
          {t("shareLocation")}
        </label>
        <ProblemBox problem={problem} />
        <Button variant="secondary" block className="mt-2" disabled={!live || !p.deviceId} onClick={heartbeat}>{t("sendHeartbeat")}</Button>
      </Card>

      <Card>
        <label className="flex min-h-12 items-center gap-3">
          <input type="checkbox" data-testid="training-offline" className="h-6 w-6" checked={training}
            onChange={(e) => { setTraining(e.target.checked); setForcedOffline(e.target.checked); }} />
          {t("simulateOffline")}
        </label>
      </Card>

      <div className="flex flex-col gap-2">
        {install && <Button variant="secondary" onClick={() => void install.prompt()}>{t("installApp")}</Button>}
        <Button variant="secondary" onClick={lockApp}>{t("lock")}</Button>
        <Button variant="secondary" disabled={ob.pending > 0 || !session} onClick={() => router.push("/setup")}>{t("changeAgent")}</Button>
        <Button variant="danger" onClick={() => { lockApp(); void signOut(session); }}>{t("signOut")}</Button>
      </div>
    </div>
  );
}
