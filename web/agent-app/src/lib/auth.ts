"use client";

import type { User, UserManager } from "oidc-client-ts";

/**
 * Two sign-in modes (same as the ops console and developer portal):
 *  - "oidc": Keycloak (realm meridian, authorization code + PKCE); the tenant comes from the token's tenant_id claim.
 *  - "dev":  local services in dev security mode; the app sends X-Tenant-Id / X-User / X-Agent-Id headers and the
 *            sign-in time as X-Auth-Time (the agent channel's step-up window).
 * The agent comes from the token's agent_id claim (Keycloak client meridian-agent-app, admin-managed attribute), never
 * from a choice in the app.
 * The session lives in sessionStorage (gone when the tab or installed app closes); the agent profile and the
 * encrypted outbox live on the device so work captured offline survives restarts.
 */
export type AuthMode = "dev" | "oidc";

export interface Session {
  mode: AuthMode;
  user: string;
  /** Platform user id (uuid): the till operator. OIDC: the token subject; dev mode: entered at sign-in. */
  userId: string;
  tenantId: string;
  /** The signed-in agent (token claim agent_id; dev mode: entered at sign-in). */
  agentId?: string;
  /** Dev mode: time of the sign-in (epoch seconds), sent as X-Auth-Time. */
  authTime?: number;
  accessToken?: string;
  expiresAt?: number;
}

export const SESSION_KEY = "meridian.agentapp.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";

let manager: UserManager | null = null;

/** The OIDC client, loaded on demand (keeps oidc-client-ts out of the first-load bundle). */
export async function oidc(): Promise<UserManager> {
  if (!manager) {
    const { UserManager, WebStorageStateStore } = await import("oidc-client-ts");
    const origin = window.location.origin;
    manager = new UserManager({
      authority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:15180/realms/meridian",
      client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "meridian-agent-app",
      redirect_uri: `${origin}/auth/callback`,
      post_logout_redirect_uri: `${origin}/login`,
      response_type: "code",
      scope: "openid profile",
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    });
    manager.events.addUserLoaded((u) => {
      const s = loadSession();
      if (s?.mode === "oidc") saveSession({ ...s, accessToken: u.access_token, expiresAt: u.expires_at });
    });
  }
  return manager;
}

function tokenPayload(user: User): Record<string, unknown> {
  try {
    const part = user.access_token.split(".")[1];
    return JSON.parse(atob(part.replace(/-/g, "+").replace(/_/g, "/")));
  } catch {
    return {};
  }
}

function claim(user: User, name: string): string {
  const v = (user.profile as Record<string, unknown>)[name];
  if (typeof v === "string") return v;
  return String(tokenPayload(user)[name] ?? "");
}

export function sessionFromOidc(user: User): Session {
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    userId: user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    agentId: claim(user, "agent_id") || undefined,
    accessToken: user.access_token,
    expiresAt: user.expires_at,
  };
}

export function loadSession(): Session | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null) {
  try {
    if (s) window.sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else window.sessionStorage.removeItem(SESSION_KEY);
  } catch {
    /* storage unavailable: session lives only in memory for this tab */
  }
}

/** Headers that authenticate a request as the signed-in agent (bearer token, or dev-mode tenant headers). */
export function authHeaders(s: Session | null = loadSession()): Record<string, string> {
  const headers: Record<string, string> = {};
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
    if (s.agentId) headers["X-Agent-Id"] = s.agentId;
    if (s.authTime) headers["X-Auth-Time"] = String(s.authTime);
  }
  return headers;
}

export async function signOut(session: Session | null) {
  saveSession(null);
  // Drop cached reference data (it was fetched with this user's credentials).
  navigator.serviceWorker?.controller?.postMessage({ type: "clear-ref" });
  if (session?.mode === "oidc" && navigator.onLine) {
    try {
      await (await oidc()).signoutRedirect();
      return;
    } catch {
      /* offline or IdP unreachable: fall through to the local sign-out */
    }
  }
  // A full load (not a client navigation) so in-memory secrets and caches of this page are discarded.
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.href = "/login";
}
