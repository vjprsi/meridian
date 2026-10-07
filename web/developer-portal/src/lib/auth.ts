"use client";

import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

/**
 * Two sign-in modes (same as the ops console):
 *  - "oidc": Keycloak (realm meridian, client meridian-developer-portal: platform scopes only, authorization code + PKCE; SEC-14); the tenant comes from
 *    the token's tenant_id claim. Used in every shared environment.
 *  - "dev":  local services in dev security mode; the portal sends X-Tenant-Id / X-User headers.
 */
export type AuthMode = "dev" | "oidc";

export interface Session {
  mode: AuthMode;
  user: string;
  tenantId: string;
  accessToken?: string;
  scopes?: string[];
}

export const SESSION_KEY = "meridian.devportal.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";

let manager: UserManager | null = null;

export function oidc(): UserManager {
  if (!manager) {
    const origin = window.location.origin;
    manager = new UserManager({
      authority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:15180/realms/meridian",
      client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "meridian-developer-portal",
      redirect_uri: `${origin}/auth/callback`,
      post_logout_redirect_uri: `${origin}/`,
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
  return String(tokenPayload(user)[name] ?? "");
}

export function sessionFromOidc(user: User): Session {
  const scope = tokenPayload(user).scope;
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    accessToken: user.access_token,
    scopes: typeof scope === "string" ? scope.split(" ").filter(Boolean) : undefined,
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

/** Headers that authenticate a request as the signed-in developer (bearer token, or dev-mode tenant headers). */
export function authHeaders(s: Session | null = loadSession()): Record<string, string> {
  const headers: Record<string, string> = {};
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
  }
  return headers;
}

const RETURN_KEY = "devportal.returnTo";

/** Remembers the console page an unauthenticated visitor asked for, to return there after sign-in. */
export function rememberReturnTo(path: string) {
  try { sessionStorage.setItem(RETURN_KEY, path); } catch { /* ignore */ }
}

/** Where to go after sign-in: the remembered console page, else the console home. */
export function takeReturnTo(): string {
  try {
    const r = sessionStorage.getItem(RETURN_KEY);
    sessionStorage.removeItem(RETURN_KEY);
    return r && r.startsWith("/console") ? r : "/console";
  } catch {
    return "/console";
  }
}

export async function signOut(session: Session | null) {
  saveSession(null);
  if (session?.mode === "oidc") {
    await oidc().signoutRedirect();
  } else {
    window.location.href = "/";
  }
}
