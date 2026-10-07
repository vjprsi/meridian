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
        router.replace("/");
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
