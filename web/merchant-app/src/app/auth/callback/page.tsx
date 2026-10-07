"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { oidc, saveSession, sessionFromOidc } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { setSelectedStore } from "@/lib/store";
import { useSession } from "@/components/providers";

export default function AuthCallback() {
  const router = useRouter();
  const t = useT();
  const { setSession } = useSession();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    oidc().signinRedirectCallback()
      .then((user) => {
        const s = sessionFromOidc(user);
        setSelectedStore(null);
        saveSession(s);
        setSession(s);
        router.replace("/");
      })
      .catch((e) => setError(String(e)));
  }, [router, setSession]);

  return (
    <main id="main" className="grid min-h-screen place-items-center p-6 text-sm">
      {error ? <p role="alert" className="text-bad">{t("signInFailed")}: {error}</p>
        : <p role="status" className="text-muted">{t("signingIn")}</p>}
    </main>
  );
}
