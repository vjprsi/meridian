"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { oidc, saveSession, sessionFromOidc } from "@/lib/auth";
import { loadProfile } from "@/lib/agent-context";
import { useSession } from "@/components/providers";

export default function AuthCallback() {
  const router = useRouter();
  const { setSession } = useSession();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    oidc().then((m) => m.signinRedirectCallback())
      .then((user) => {
        const s = sessionFromOidc(user);
        saveSession(s);
        setSession(s);
        const p = loadProfile();
        router.replace(p && p.tenantId === s.tenantId ? "/" : "/setup");
      })
      .catch((e) => setError(String(e)));
  }, [router, setSession]);

  return (
    <main className="grid min-h-screen place-items-center p-6 text-muted">
      {error ? <p role="alert" className="font-semibold text-bad">Sign-in failed: {error}</p> : <p>Signing you in…</p>}
    </main>
  );
}
