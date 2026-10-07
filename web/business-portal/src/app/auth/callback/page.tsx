"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { oidc, saveSession, sessionFromOidc } from "@/lib/auth";
import { useSession } from "@/components/providers";

export default function AuthCallback() {
  const router = useRouter();
  const { setSession } = useSession();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    oidc().signinRedirectCallback()
      .then((user) => {
        const s = sessionFromOidc(user);
        saveSession(s);
        setSession(s);
        router.replace("/");
      })
      .catch((e) => setError(String(e)));
  }, [router, setSession]);

  return (
    <main id="main" className="grid min-h-screen place-items-center p-6 text-sm">
      {error ? <p role="alert" className="text-bad">Sign-in failed: {error}</p>
        : <p role="status" className="text-muted">Signing you in…</p>}
    </main>
  );
}
