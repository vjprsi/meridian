"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { oidc, saveSession, sessionFromOidc } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { useSession } from "@/components/providers";

export default function AuthCallback() {
  const t = useT();
  const router = useRouter();
  const { setSession } = useSession();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    oidc().signinRedirectCallback()
      .then((user) => {
        const s = sessionFromOidc(user);
        saveSession(s);
        setSession(s);
        // back from an identity provider action (enrol / remove a factor): return to the page that started it, with
        // Keycloak's kc_action_status (success, cancelled, error) for the notice there
        let to = "/";
        try {
          const saved = sessionStorage.getItem("meridian.customer.returnTo");
          sessionStorage.removeItem("meridian.customer.returnTo");
          if (saved && /^\/[A-Za-z0-9/_-]*$/.test(saved)) {
            const status = new URLSearchParams(window.location.search).get("kc_action_status");
            to = status ? `${saved}?action=${encodeURIComponent(status)}` : saved;
          }
        } catch { /* storage unavailable: home */ }
        router.replace(to);
      })
      .catch((e) => setError(String(e)));
  }, [router, setSession]);

  return (
    <main id="main" className="grid min-h-screen place-items-center p-6 text-sm">
      {error ? <p role="alert" className="text-bad">{t("auth.failed", { error })}</p>
        : <p role="status" className="text-muted">{t("auth.signingIn")}</p>}
    </main>
  );
}
