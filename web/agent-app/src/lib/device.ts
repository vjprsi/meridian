"use client";

/** A stable random identifier for this installation (used as device serial and offline-batch fingerprint). */
export function deviceSerial(): string {
  const KEY = "meridian.agentapp.deviceSerial";
  try {
    let v = localStorage.getItem(KEY);
    if (!v) {
      v = "PWA-" + crypto.randomUUID().replace(/-/g, "").slice(0, 16).toUpperCase();
      localStorage.setItem(KEY, v);
    }
    return v;
  } catch {
    return "PWA-UNKNOWN";
  }
}

export function deviceModel(): string {
  const ua = typeof navigator === "undefined" ? "" : navigator.userAgent;
  const m = ua.match(/\(([^)]+)\)/);
  return (m?.[1] ?? "Browser").slice(0, 60);
}

export function deviceType(): "SMARTPHONE" | "TABLET" {
  return typeof window !== "undefined" && Math.min(window.screen.width, window.screen.height) >= 600 ? "TABLET" : "SMARTPHONE";
}
