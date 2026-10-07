"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { DEFAULT_TENANT, oidc, saveSession } from "@/lib/auth";
import { loadProfile } from "@/lib/agent-context";
import { useSession } from "@/components/providers";
import { Button, Field, Input, ProblemBox, Select } from "@/components/ui";
import { ConnectivityPill } from "@/components/shell";
import { setLang, useLang, useT } from "@/lib/i18n";

const DEV_OPERATOR = "0199a000-0000-7000-8000-0000000000a1";

export default function LoginPage() {
  const t = useT();
  const lang = useLang();
  const router = useRouter();
  const { setSession } = useSession();
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [user, setUser] = useState("agent.demo");
  const [userId, setUserId] = useState(DEV_OPERATOR);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function devSignIn(e: FormEvent) {
    e.preventDefault();
    const s = { mode: "dev" as const, user, userId, tenantId };
    saveSession(s);
    setSession(s);
    const p = loadProfile();
    router.replace(p && p.tenantId === tenantId ? "/" : "/setup");
  }

  async function sso() {
    setBusy(true);
    setErr(null);
    try {
      await (await oidc()).signinRedirect();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center gap-6 px-4 py-8">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <span aria-hidden className="grid h-11 w-11 place-items-center rounded-xl bg-accent text-xl font-black text-accent-fg">M</span>
          <div>
            <h1 className="text-xl font-bold">{t("appName")}</h1>
            <p className="text-sm text-muted">{t("signIn")}</p>
          </div>
        </div>
        <ConnectivityPill />
      </div>
      <Field label={t("language")}>
        <Select value={lang} onChange={(e) => setLang(e.target.value as "en" | "sw")}
          options={[{ value: "en", label: "English" }, { value: "sw", label: "Kiswahili" }]} />
      </Field>
      <Button block onClick={sso} disabled={busy}>{t("signInSso")}</Button>
      {err && <ProblemBox problem={{ title: "Single sign-on unavailable", detail: err }} />}
      <div className="flex items-center gap-3 text-sm text-muted">
        <span className="h-px flex-1 bg-border" />{t("devMode")}<span className="h-px flex-1 bg-border" />
      </div>
      <form onSubmit={devSignIn} className="flex flex-col gap-4">
        <Field label={t("tenant")} hint="X-Tenant-Id (services in dev security mode)">
          <Input value={tenantId} onChange={(e) => setTenantId(e.target.value)} required pattern="[\-0-9a-fA-F]{36}" />
        </Field>
        <Field label={t("user")} hint="X-User">
          <Input value={user} onChange={(e) => setUser(e.target.value)} required />
        </Field>
        <Field label={t("userId")}>
          <Input value={userId} onChange={(e) => setUserId(e.target.value)} required pattern="[\-0-9a-fA-F]{36}" />
        </Field>
        <Button type="submit" variant="secondary" block>{t("continueDev")}</Button>
      </form>
    </main>
  );
}
