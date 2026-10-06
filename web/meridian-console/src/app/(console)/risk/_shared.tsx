"use client";

import { Badge } from "@/components/ui";

/** Shared enums and cells for the risk & compliance screens (values from the risk service contracts). */
export const SEVERITIES = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
export const CASE_TYPES = ["AML", "FRAUD", "SANCTIONS", "KYC", "MULE", "TERRORIST_FINANCING", "BRIBERY_CORRUPTION"] as const;

/** Severity / priority chip: CRITICAL and HIGH stand out, MEDIUM warns, LOW is muted. */
export function Severity({ value }: { value?: unknown }) {
  const v = value ? String(value) : undefined;
  const tone = v === "CRITICAL" || v === "HIGH" ? "bad" : v === "MEDIUM" ? "warn" : "muted";
  return <Badge value={v} tone={tone} />;
}

/** True when an SLA / due timestamp is in the past. */
export function overdue(v: unknown): boolean {
  return typeof v === "string" && new Date(v).getTime() < Date.now();
}
