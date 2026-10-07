"use client";

import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

/**
 * Two sign-in modes:
 *  - "oidc": Keycloak (realm meridian, public client with authorization code + PKCE). The tenant comes from the
 *    token's tenant_id claim and the customer from its customer_id claim; a token without customer_id cannot be
 *    used here (it is not a customer identity).
 *  - "dev":  local services in dev security mode; the app sends X-Tenant-Id / X-User headers and the customer is
 *    picked on the dev login screen.
 *
 * Whatever the mode, every request this app makes is scoped to session.customerId (see src/api/me.ts).
 */
export type AuthMode = "dev" | "oidc";

export interface Session {
  mode: AuthMode;
  user: string;
  tenantId: string;
  customerId: string;
  accessToken?: string;
  /** Epoch seconds of the last interactive authentication (token auth_time), used for step-up. */
  authTime?: number;
  /** Epoch seconds when a dev-mode step-up confirmation was last given. */
  stepUpAt?: number;
}

export const SESSION_KEY = "meridian.customer.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";
/** Money movement needs an authentication (or dev confirmation) no older than this. */
export const STEP_UP_MAX_AGE_S = 300;

let manager: UserManager | null = null;

export function oidc(): UserManager {
  if (!manager) {
    const origin = window.location.origin;
    manager = new UserManager({
      authority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:15180/realms/meridian",
      client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "meridian-customer-web",
      redirect_uri: `${origin}/auth/callback`,
      popup_redirect_uri: `${origin}/auth/popup`,
      post_logout_redirect_uri: `${origin}/login`,
      response_type: "code",
      scope: "openid profile email",
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
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
  const p = tokenPayload(user)[name];
  return p === undefined || p === null ? "" : String(p);
}

/** Builds a session from an OIDC sign-in; customerId is empty when the token carries no customer_id claim. */
export function sessionFromOidc(user: User): Session {
  const authTime = Number(claim(user, "auth_time")) || Math.floor(Date.now() / 1000);
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    customerId: claim(user, "customer_id"),
    accessToken: user.access_token,
    authTime,
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

/** Headers that authenticate a request as the signed-in customer (bearer token, or dev-mode headers). */
export function authHeaders(s: Session | null = loadSession()): Record<string, string> {
  const headers: Record<string, string> = {};
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
  }
  return headers;
}

/** True when the last strong authentication is recent enough to move money without asking again. */
export function stepUpFresh(s: Session | null): boolean {
  if (!s) return false;
  const now = Math.floor(Date.now() / 1000);
  const at = s.mode === "oidc" ? s.authTime : s.stepUpAt;
  return !!at && now - at <= STEP_UP_MAX_AGE_S;
}

/**
 * Re-authenticates for a sensitive action. OIDC: a popup sign-in with max_age=0 forces Keycloak to prompt again
 * (the form on screen keeps its state). Dev: records the explicit confirmation time.
 */
export async function stepUp(s: Session): Promise<Session> {
  if (s.mode === "oidc") {
    const user = await oidc().signinPopup({ max_age: 0, prompt: "login" });
    const next = sessionFromOidc(user);
    if (next.customerId !== s.customerId) throw new Error("Signed in as a different customer");
    saveSession(next);
    return next;
  }
  const next = { ...s, stepUpAt: Math.floor(Date.now() / 1000) };
  saveSession(next);
  return next;
}

export async function signOut(session: Session | null) {
  saveSession(null);
  if (session?.mode === "oidc") {
    await oidc().signoutRedirect();
  } else {
    window.location.href = "/login";
  }
}
