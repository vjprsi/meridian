"use client";

import { UserManager, WebStorageStateStore, type User } from "oidc-client-ts";

/**
 * Two sign-in modes:
 *  - "oidc": Keycloak (realm meridian, client meridian-console, authorization code + PKCE); the tenant comes from
 *    the token's tenant_id claim. Used in every shared environment.
 *  - "dev":  local services in dev security mode; the console sends X-Tenant-Id / X-User headers.
 */
export type AuthMode = "dev" | "oidc";

export interface Session {
  mode: AuthMode;
  user: string;
  tenantId: string;
  accessToken?: string;
}

const KEY = "meridian.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";

let manager: UserManager | null = null;

export function oidc(): UserManager {
  if (!manager) {
    const origin = window.location.origin;
    manager = new UserManager({
      authority: process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:15180/realms/meridian",
      client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "meridian-console",
      redirect_uri: `${origin}/auth/callback`,
      post_logout_redirect_uri: `${origin}/login`,
      response_type: "code",
      scope: "openid profile email",
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    });
  }
  return manager;
}

function claim(user: User, name: string): string {
  const v = (user.profile as Record<string, unknown>)[name];
  if (typeof v === "string") return v;
  const parts = user.access_token.split(".");
  try {
    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/")));
    return String(payload[name] ?? "");
  } catch {
    return "";
  }
}

export function sessionFromOidc(user: User): Session {
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    accessToken: user.access_token,
  };
}

export function loadSession(): Session | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(s: Session | null) {
  try {
    if (s) window.sessionStorage.setItem(KEY, JSON.stringify(s));
    else window.sessionStorage.removeItem(KEY);
  } catch {
    /* storage unavailable: session lives only in memory for this tab */
  }
}

export async function signOut(session: Session | null) {
  saveSession(null);
  if (session?.mode === "oidc") {
    await oidc().signoutRedirect();
  } else {
    window.location.href = "/login";
  }
}
