"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { DEFAULT_TENANT, DEMO_ADMIN_USER, DEMO_BUSINESS, oidc, saveSession, type Session } from "@/lib/auth";
import { useSession } from "@/components/providers";
import { Button, ErrorBanner, Field, Input } from "@/components/ui";

const UUID = "[\\-0-9a-fA-F]{36}";

export default function LoginPage() {
  const router = useRouter();
  const { setSession } = useSession();
  const [busy, setBusy] = useState(false);
  const [ssoError, setSsoError] = useState<unknown>(null);
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [businessCustomerId, setBusinessCustomerId] = useState(DEMO_BUSINESS);
  const [businessUserId, setBusinessUserId] = useState(DEMO_ADMIN_USER);
  const [user, setUser] = useState("dev.business.user");

  function devSignIn(e: FormEvent) {
    e.preventDefault();
    const s: Session = {
      mode: "dev", tenantId: tenantId.trim(), businessCustomerId: businessCustomerId.trim(),
      businessUserId: businessUserId.trim(), user: user.trim() || "dev.business.user",
      authTime: Math.floor(Date.now() / 1000),
    };
    saveSession(s);
    setSession(s);
    router.replace("/");
  }

  return (
    <main id="main" className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center px-4 py-10">
      <div className="mb-8 flex items-center gap-3">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/icon.svg" alt="" width={44} height={44} />
        <div>
          <h1 className="text-xl font-semibold">Meridian Business</h1>
          <p className="text-sm text-muted">Business banking for owners and finance teams</p>
        </div>
      </div>

      <section aria-labelledby="sso" className="rounded-[var(--radius)] border border-border bg-surface p-5">
        <h2 id="sso" className="sr-only">Sign in</h2>
        <Button className="w-full" disabled={busy} onClick={async () => {
          setBusy(true);
          setSsoError(null);
          try { await oidc().signinRedirect(); } catch (e) { setSsoError(e); setBusy(false); }
        }}>Sign in with Meridian ID</Button>
        <ErrorBanner error={ssoError} className="mt-3" />
      </section>

      <section aria-labelledby="dev" className="mt-6 rounded-[var(--radius)] border border-dashed border-border-strong bg-surface p-5">
        <h2 id="dev" className="font-semibold">Developer sign-in</h2>
        <p className="mt-1 text-xs text-muted">
          For local services in dev security mode: requests carry X-Tenant-Id, X-Business-Customer-Id, X-Business-User-Id,
          X-User and X-Auth-Time instead of a token.
        </p>
        <form onSubmit={devSignIn} className="mt-4 flex flex-col gap-4">
          <Field label="Tenant id">
            <Input name="tenant" value={tenantId} onChange={(e) => setTenantId(e.target.value)} required pattern={UUID}
              autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Business customer id">
            <Input name="business_customer_id" value={businessCustomerId} onChange={(e) => setBusinessCustomerId(e.target.value)}
              required pattern={UUID} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Business user id" hint="The first user to sign in for a business without portal users becomes its ADMIN.">
            <Input name="business_user_id" value={businessUserId} onChange={(e) => setBusinessUserId(e.target.value)}
              required pattern={UUID} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Your name (X-User)">
            <Input name="user" value={user} onChange={(e) => setUser(e.target.value)} required autoComplete="off" />
          </Field>
          <Button type="submit" variant="secondary">Sign in (dev)</Button>
        </form>
      </section>
    </main>
  );
}
