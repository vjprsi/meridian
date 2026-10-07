"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { DEFAULT_TENANT, DEMO_MERCHANT, DEMO_OWNER_USER, oidc, saveSession, type Session } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { setSelectedStore } from "@/lib/store";
import { useSession } from "@/components/providers";
import { LangSelect } from "@/components/shell";
import { Button, ErrorBanner, Field, Input } from "@/components/ui";

const UUID = "[\\-0-9a-fA-F]{36}";

export default function LoginPage() {
  const router = useRouter();
  const t = useT();
  const { setSession } = useSession();
  const [busy, setBusy] = useState(false);
  const [ssoError, setSsoError] = useState<unknown>(null);
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [merchantId, setMerchantId] = useState(DEMO_MERCHANT);
  const [merchantUserId, setMerchantUserId] = useState(DEMO_OWNER_USER);
  const [user, setUser] = useState("dev.merchant.user");

  function devSignIn(e: FormEvent) {
    e.preventDefault();
    const s: Session = {
      mode: "dev", tenantId: tenantId.trim(), merchantId: merchantId.trim(),
      merchantUserId: merchantUserId.trim(), user: user.trim() || "dev.merchant.user",
      authTime: Math.floor(Date.now() / 1000),
    };
    setSelectedStore(null);
    saveSession(s);
    setSession(s);
    router.replace("/");
  }

  return (
    <main id="main" className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center px-4 py-10">
      <div className="mb-8 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/icon.svg" alt="" width={44} height={44} />
          <div>
            <h1 className="text-xl font-semibold">{t("appName")}</h1>
            <p className="text-sm text-muted">{t("tagline")}</p>
          </div>
        </div>
        <LangSelect />
      </div>

      <section aria-labelledby="sso" className="rounded-[var(--radius)] border border-border bg-surface p-5">
        <h2 id="sso" className="sr-only">{t("signIn")}</h2>
        <Button className="w-full" size="lg" disabled={busy} onClick={async () => {
          setBusy(true);
          setSsoError(null);
          try { await oidc().signinRedirect(); } catch (e) { setSsoError(e); setBusy(false); }
        }}>{t("signIn")}</Button>
        <ErrorBanner error={ssoError} className="mt-3" />
      </section>

      <section aria-labelledby="dev" className="mt-6 rounded-[var(--radius)] border border-dashed border-border-strong bg-surface p-5">
        <h2 id="dev" className="font-semibold">{t("devSignIn")}</h2>
        <p className="mt-1 text-xs text-muted">{t("devSignInHint")}</p>
        <form onSubmit={devSignIn} className="mt-4 flex flex-col gap-4">
          <Field label={t("tenantId")}>
            <Input name="tenant" value={tenantId} onChange={(e) => setTenantId(e.target.value)} required pattern={UUID}
              autoComplete="off" spellCheck={false} />
          </Field>
          <Field label={t("merchantId")}>
            <Input name="merchant_id" value={merchantId} onChange={(e) => setMerchantId(e.target.value)}
              required pattern={UUID} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label={t("merchantUserId")} hint={t("merchantUserIdHint")}>
            <Input name="merchant_user_id" value={merchantUserId} onChange={(e) => setMerchantUserId(e.target.value)}
              required pattern={UUID} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label={t("yourName")}>
            <Input name="user" value={user} onChange={(e) => setUser(e.target.value)} required autoComplete="off" />
          </Field>
          <Button type="submit" variant="secondary">{t("devSignInButton")}</Button>
        </form>
      </section>
    </main>
  );
}
