"use client";

import { useEffect } from "react";
import { agents, idem, unwrap, type AgentDevice } from "@/api/client";
import { loadProfile } from "@/lib/agent-context";
import { config } from "@/lib/config";
import { isOnline } from "@/lib/connectivity";
import { loadSession } from "@/lib/auth";

const LAST = "meridian.agentapp.lastHeartbeat";
const GEO = "meridian.agentapp.heartbeatGeo";

export function lastHeartbeat(): string | null {
  try { return localStorage.getItem(LAST); } catch { return null; }
}

export function geoEnabled(): boolean {
  try { return localStorage.getItem(GEO) === "1"; } catch { return false; }
}

export function setGeoEnabled(v: boolean) {
  try { localStorage.setItem(GEO, v ? "1" : ""); } catch { /* ignore */ }
}

function position(): Promise<GeolocationPosition | null> {
  return new Promise((resolve) => {
    if (!geoEnabled() || !navigator.geolocation) return resolve(null);
    navigator.geolocation.getCurrentPosition(resolve, () => resolve(null), { timeout: 8000, maximumAge: 300_000 });
  });
}

/** Records a device heartbeat (agentDevicesHeartbeat) with the app version and, if the agent allowed it, location. */
export async function sendHeartbeat(): Promise<AgentDevice | null> {
  const p = loadProfile();
  if (!p?.deviceId || !loadSession() || !isOnline()) return null;
  const pos = await position();
  const d = unwrap(await agents.POST("/v1/agent-devices/{id}/heartbeat", {
    params: { path: { id: p.deviceId }, header: idem() },
    body: {
      app_version: config.appVersion,
      ...(pos ? { latitude: pos.coords.latitude.toFixed(6), longitude: pos.coords.longitude.toFixed(6) } : {}),
    },
  }));
  try { localStorage.setItem(LAST, new Date().toISOString()); } catch { /* ignore */ }
  return d;
}

/** Periodic heartbeat while the app is open and online. */
export function useHeartbeat() {
  useEffect(() => {
    const run = () => { void sendHeartbeat().catch(() => {}); };
    const first = setTimeout(run, 5_000);
    const t = setInterval(run, config.heartbeatMinutes * 60_000);
    return () => { clearTimeout(first); clearInterval(t); };
  }, []);
}
