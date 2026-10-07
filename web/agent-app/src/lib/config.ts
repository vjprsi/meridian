import type { OfflinePolicy } from "./outbox/outbox";

function num(v: string | undefined, d: number): number {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : d;
}

/** Deployment settings (NEXT_PUBLIC_* are inlined at build time; defaults suit the local stack). */
export const config = {
  appVersion: "0.1.0",
  defaultCurrency: process.env.NEXT_PUBLIC_AGENT_CURRENCY ?? "KES",
  /** Prefix added to local phone numbers (07xx...) to form E.164 (+2547xx...). */
  countryCode: process.env.NEXT_PUBLIC_COUNTRY_CODE ?? "+254",
  heartbeatMinutes: num(process.env.NEXT_PUBLIC_HEARTBEAT_MINUTES, 5),
  /** The app locks (outbox key and agent PIN wiped from memory) after this much inactivity. */
  idleLockMinutes: num(process.env.NEXT_PUBLIC_IDLE_LOCK_MINUTES, 10),
};

/** Offline limits; deployments tighten or loosen them per market. */
export const offlinePolicy: OfflinePolicy = {
  maxAmountPerOp: process.env.NEXT_PUBLIC_OFFLINE_MAX_AMOUNT ?? "5000",
  maxTotalAmount: process.env.NEXT_PUBLIC_OFFLINE_MAX_TOTAL ?? "20000",
  maxCount: num(process.env.NEXT_PUBLIC_OFFLINE_MAX_COUNT, 10),
  maxOfflineHours: num(process.env.NEXT_PUBLIC_OFFLINE_MAX_HOURS, 12),
};

/** Normalises a phone number to E.164 using the configured country code. */
export function toMsisdn(input: string): string {
  const s = input.replace(/[\s\-()]/g, "");
  if (s.startsWith("+")) return s;
  if (s.startsWith("00")) return "+" + s.slice(2);
  if (s.startsWith("0")) return config.countryCode + s.slice(1);
  return config.countryCode + s;
}

export function isMsisdn(s: string): boolean {
  return /^\+[1-9]\d{6,14}$/.test(s);
}
