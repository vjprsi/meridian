"use client";

import { useQueryClient } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";
import { ApiError, isStepUp } from "@/api/client";
import { stepUp } from "@/lib/auth";
import { useSession } from "./providers";
import { Button, Dialog, ErrorBanner } from "./ui";

/** Asks the user to re-authenticate; resolves true once they did, false when they cancelled. */
type RequestStepUp = (maxAge?: number) => Promise<boolean>;

const StepUpContext = createContext<RequestStepUp>(async () => false);

/**
 * Step-up for approvals, money movement and admin changes. The server decides when a fresh authentication is needed
 * (401 step_up_required, WWW-Authenticate: Bearer error="insufficient_user_authentication", max_age=<s>); this
 * provider then asks the user to confirm: OIDC sessions sign in again in a popup (max_age, prompt=login), dev
 * sessions refresh X-Auth-Time. {@link useAction} then retries the same request with the same Idempotency-Key.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const { session, setSession } = useSession();
  const [pending, setPending] = useState<{ maxAge?: number } | null>(null);
  const resolver = useRef<((ok: boolean) => void) | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const request = useCallback<RequestStepUp>((maxAge) => new Promise<boolean>((resolve) => {
    resolver.current?.(false);
    resolver.current = resolve;
    setError(null);
    setPending({ maxAge });
  }), []);

  function finish(ok: boolean) {
    resolver.current?.(ok);
    resolver.current = null;
    setPending(null);
    setBusy(false);
  }

  async function confirm() {
    if (!session) return finish(false);
    setBusy(true);
    setError(null);
    try {
      setSession(await stepUp(session, pending?.maxAge ?? 0));
      finish(true);
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <StepUpContext.Provider value={request}>
      {children}
      <Dialog open={!!pending} onClose={() => finish(false)} title="Confirm it's you"
        footer={<>
          <Button variant="secondary" onClick={() => finish(false)} disabled={busy}>Cancel</Button>
          <Button onClick={confirm} disabled={busy}>{busy ? "Working…" : "Confirm and continue"}</Button>
        </>}>
        <p>This action needs a recent sign-in.</p>
        <p className="mt-2 text-xs text-muted">
          {session?.mode === "oidc"
            ? "A sign-in window opens; after you sign in again the action continues automatically."
            : "Dev mode: confirming refreshes your authentication time (X-Auth-Time) and the action continues."}
        </p>
        <ErrorBanner error={error} className="mt-3" />
      </Dialog>
    </StepUpContext.Provider>
  );
}

export function useStepUp(): RequestStepUp {
  return useContext(StepUpContext);
}

/**
 * Runs a write: `fn` receives the Idempotency-Key to send. When the server answers step_up_required the user
 * re-authenticates and the same request is retried once with the SAME key, so it can never be performed twice.
 */
export function useAction() {
  const requestStepUp = useStepUp();
  return useCallback(async <T,>(fn: (key: string) => Promise<T>, key: string = crypto.randomUUID()): Promise<T> => {
    try {
      return await fn(key);
    } catch (e) {
      if (!isStepUp(e)) throw e;
      if (!(await requestStepUp(e.stepUpMaxAge))) throw e;
      return await fn(key);
    }
  }, [requestStepUp]);
}

/**
 * State for one confirmed action: `run(fn)` performs it (with step-up) using the key of the current confirmation,
 * tracks pending/error, and refreshes every query afterwards. `newKey()` starts a new confirmation.
 */
export function useRun() {
  const action = useAction();
  const qc = useQueryClient();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const key = useRef<string>("");

  const newKey = useCallback(() => {
    key.current = crypto.randomUUID();
    setError(null);
  }, []);

  const run = useCallback(async <T,>(fn: (key: string) => Promise<T>): Promise<{ ok: true; data: T } | { ok: false }> => {
    if (!key.current) key.current = crypto.randomUUID();
    setPending(true);
    setError(null);
    try {
      const data = await action(fn, key.current);
      key.current = "";
      void qc.invalidateQueries();
      return { ok: true, data };
    } catch (e) {
      // A refused request (4xx) was not performed: the next attempt is a new request with a new key. After a
      // network or server error the outcome is unknown, so a resubmit keeps the key and cannot double-post.
      const status = e instanceof ApiError ? e.problem.status ?? 0 : 0;
      if (status >= 400 && status < 500) key.current = "";
      setError(e);
      return { ok: false };
    } finally {
      setPending(false);
    }
  }, [action, qc]);

  return { run, pending, error, setError, newKey };
}
