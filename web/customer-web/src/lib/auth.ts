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
 * Signed-in screens call only the customer channel (/v1/me/*): the server binds every request to the customer of the
 * token (dev: X-Customer-Id) and refuses objects of anyone else (see src/api/me.ts).
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
  /** Level of Authentication of the token (acr): "1" password, "2" password + second factor. */
  acr?: string;
  /** Authentication methods of the last authentication (amr: pwd, otp, hwk). */
  amr?: string[];
  /** Identity provider session (sid claim; dev mode: a random id per sign-in, sent as X-Session-Id). */
  sid?: string;
  /** Epoch seconds when a dev-mode step-up confirmation was last given. */
  stepUpAt?: number;
}

/** Level of Authentication the server asks for money movement and security changes (RFC 9470 acr_values). */
export const STEP_UP_ACR = "2";
/** The server's step-up max age (meridian.channels.step-up.max-age); sent as OIDC max_age on step-up. */
export const STEP_UP_SERVER_MAX_AGE_S = 300;
const DEVICE_KEY = "meridian.customer.device";

/**
 * A random id of this browser, sent as X-Device-Id so the bank recognises the device across sign-ins (a sign-in from
 * a device not seen before triggers a security alert). Not a secret and not personal data: localStorage, with a
 * per-tab fallback when storage is blocked.
 */
let fallbackDevice: string | null = null;
export function deviceId(): string {
  try {
    let id = window.localStorage.getItem(DEVICE_KEY);
    if (!id) {
      id = crypto.randomUUID();
      window.localStorage.setItem(DEVICE_KEY, id);
    }
    return id;
  } catch {
    fallbackDevice ??= crypto.randomUUID();
    return fallbackDevice;
  }
}

export const SESSION_KEY = "meridian.customer.session";
export const DEFAULT_TENANT = "0199a000-0000-7000-8000-00000000000a";
/**
 * Money movement needs an authentication (or dev confirmation) no older than this. The server enforces 300 s
 * (meridian.channels.step-up-max-age, 401 step_up_required); the app asks again a little earlier so a confirmation
 * dialog left open does not run into the server limit.
 */
export const STEP_UP_MAX_AGE_S = 240;

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
  const authTime = Number(claim(user, "auth_time")) || undefined;
  const amr = tokenPayload(user).amr;
  return {
    mode: "oidc",
    user: user.profile.preferred_username ?? user.profile.sub,
    tenantId: claim(user, "tenant_id"),
    customerId: claim(user, "customer_id"),
    accessToken: user.access_token,
    authTime,
    acr: claim(user, "acr") || undefined,
    amr: Array.isArray(amr) ? amr.map(String) : [],
    sid: claim(user, "sid") || undefined,
  };
}

/** A new dev-mode session id (stands in for the identity provider's sid; X-Session-Id). */
export function devSessionId(): string {
  return "dev-" + crypto.randomUUID();
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
  if (typeof window !== "undefined") headers["X-Device-Id"] = deviceId();
  if (s?.mode === "oidc" && s.accessToken) headers.Authorization = `Bearer ${s.accessToken}`;
  else if (s) {
    // dev security mode: the customer channel takes the customer, the session and the last sign-in from these
    // headers; a dev step-up confirmation stands for a two-factor authentication (acr 2)
    headers["X-Tenant-Id"] = s.tenantId;
    headers["X-User"] = s.user;
    if (s.customerId) headers["X-Customer-Id"] = s.customerId;
    if (s.sid) headers["X-Session-Id"] = s.sid;
    if (s.stepUpAt) {
      headers["X-Auth-Time"] = String(s.stepUpAt);
      headers["X-Acr"] = STEP_UP_ACR;
      headers["X-Amr"] = "pwd,otp";
    }
  }
  return headers;
}

/**
 * True when the session's authentication satisfies step-up without asking again: acr 2 (a second factor) from an
 * authentication no older than STEP_UP_MAX_AGE_S.
 */
export function stepUpFresh(s: Session | null): boolean {
  if (!s) return false;
  const now = Math.floor(Date.now() / 1000);
  const at = s.mode === "oidc" ? s.authTime : s.stepUpAt;
  const strong = s.mode === "oidc" ? Number(s.acr ?? 0) >= Number(STEP_UP_ACR) : true;
  return strong && !!at && now - at <= STEP_UP_MAX_AGE_S;
}

/** What the server asked for in its RFC 9470 challenge (WWW-Authenticate acr_values / max_age). */
export interface StepUpChallenge { acr?: string; maxAge?: number }

/** Parses `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="2", max_age=300`. */
export function parseChallenge(header: string | null | undefined): StepUpChallenge {
  if (!header || !/insufficient_user_authentication/.test(header)) return {};
  const acr = /acr_values="([^"]*)"/.exec(header)?.[1];
  const maxAge = /max_age=(\d+)/.exec(header)?.[1];
  return { acr: acr || undefined, maxAge: maxAge ? Number(maxAge) : undefined };
}

/**
 * Step-up for a sensitive action. OIDC: a popup authorization request with acr_values=2 and max_age (RFC 9470):
 * Keycloak asks for the second factor (WebAuthn / passkey / authenticator app), and for the password too when the
 * last sign-in is older than max_age; the form on screen keeps its state. Dev: records an explicit confirmation.
 */
export async function stepUp(s: Session, challenge: StepUpChallenge = {}): Promise<Session> {
  if (s.mode === "oidc") {
    const acr = challenge.acr ?? STEP_UP_ACR;
    const user = await oidc().signinPopup({ acr_values: acr, max_age: challenge.maxAge ?? STEP_UP_SERVER_MAX_AGE_S });
    const next = sessionFromOidc(user);
    if (next.customerId !== s.customerId) throw new Error("Signed in as a different customer");
    saveSession(next);
    if (Number(next.acr ?? 0) < Number(acr)) throw new StepUpUnavailable();
    return next;
  }
  const next = { ...s, stepUpAt: Math.floor(Date.now() / 1000) };
  saveSession(next);
  return next;
}

/** The identity provider did not reach the required level (no second factor enrolled). */
export class StepUpUnavailable extends Error {
  constructor() {
    super("A second factor is needed. Add a passkey or an authenticator app in Security, then try again.");
  }
}

/**
 * Starts an application-initiated action at the identity provider (Keycloak kc_action): enrol an authenticator app
 * (CONFIGURE_TOTP), a security key (webauthn-register) or a passkey (webauthn-register-passwordless), or remove a
 * factor (delete_credential:<id>). The browser leaves for Keycloak and comes back to the given page.
 */
export async function identityAction(action: string, returnTo = "/security") {
  sessionStorage.setItem("meridian.customer.returnTo", returnTo);
  await oidc().signinRedirect({ extraQueryParams: { kc_action: action } });
}

export async function signOut(session: Session | null) {
  saveSession(null);
  if (session?.mode === "oidc") {
    await oidc().signoutRedirect();
  } else {
    window.location.href = "/login";
  }
}
