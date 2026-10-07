"use client";

import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

/**
 * Two sign-in modes:
 *  - "oidc": Keycloak (realm meridian, public client meridian-merchant-app, authorization code + PKCE). The token
 *    carries scope channels:merchant and the claims merchant_id, merchant_user_id, tenant_id, auth_time and acr; the
 *    app sends it as a bearer token.
 *  - "dev":  local services in dev security mode; the app sends X-Tenant-Id, X-Merchant-Id, X-Merchant-User-Id,
 *    X-User and X-Auth-Time (the epoch seconds of the dev "sign-in"; step-up refreshes it).
 *
 * Signed-in screens call only the merchant channel (/v1/merchant/*): the server binds every request to the merchant
 * and the app user of the token and checks the user's role and store scope itself.
 */
export type AuthMode = "dev" | "oidc";

export interface Session {
  mode: AuthMode;
  user: string;
  tenantId: string;
  merchantId: string;
  merchantUserId: string;
  accessToken?: string;
  /** Epoch seconds of the last interactive authentication (token auth_time; dev: sign-in or last step-up). */
  authTime?: number;
}

export const SESSION_KEY = "meridian.merchant.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";
export const DEMO_MERCHANT = "0199a000-0000-7000-8000-0000000d0001";
export const DEMO_OWNER_USER = "0199a000-0000-7000-8000-0000000d0a01";

let manager: UserManager | null = null;

export function oidc(): UserManager {
  if (!manager) {
    const origin = window.location.origin;
    manager = new UserManager({
      authority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:15180/realms/meridian",
      client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "meridian-merchant-app",
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
  const p = tokenPayload(user)[name];
  if (p !== undefined && p !== null) return String(p);
  const v = (user.profile as Record<string, unknown>)[name];
  return v === undefined || v === null ? "" : String(v);
}

/** Builds a session from an OIDC sign-in; the merchant ids are empty when the token carries no merchant claims. */
export function sessionFromOidc(user: User): Session {
  const authTime = Number(claim(user, "auth_time")) || Math.floor(Date.now() / 1000);
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    merchantId: claim(user, "merchant_id"),
    merchantUserId: claim(user, "merchant_user_id"),
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

/** Headers that authenticate a request as the signed-in merchant user (bearer token, or dev-mode headers). */
export function authHeaders(s: Session | null = loadSession()): Record<string, string> {
  const headers: Record<string, string> = {};
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
    headers["X-Merchant-Id"] = s.merchantId;
    headers["X-Merchant-User-Id"] = s.merchantUserId;
    if (s.authTime) headers["X-Auth-Time"] = String(s.authTime);
  }
  return headers;
}

/**
 * Re-authenticates after the server answered 401 step_up_required. OIDC: a popup sign-in with max_age (0 by default)
 * and prompt=login forces Keycloak to ask again while the screen keeps its state. Dev: refreshes X-Auth-Time (the
 * caller asks for an explicit confirmation first).
 */
export async function stepUp(s: Session, maxAge = 0): Promise<Session> {
  if (s.mode === "oidc") {
    const user = await oidc().signinPopup({ max_age: maxAge, prompt: "login" });
    const next = sessionFromOidc(user);
    if (next.merchantUserId !== s.merchantUserId) throw new Error("Signed in as a different merchant user");
    saveSession(next);
    return next;
  }
  const next = { ...s, authTime: Math.floor(Date.now() / 1000) };
  saveSession(next);
  return next;
}

export async function signOut(session: Session | null) {
  saveSession(null);
  try { window.localStorage.removeItem("meridian.merchant.store"); } catch { /* ignore */ }
  if (session?.mode === "oidc") {
    await oidc().signoutRedirect();
  } else {
    // A full reload on purpose: drops every cached query of the signed-out user.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.href = "/login";
  }
}
