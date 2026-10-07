"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { DEFAULT_TENANT, oidc, saveSession, takeReturnTo } from "@/lib/auth";
import { useSession } from "@/components/providers";
import { Button, Field, Input } from "@/components/ui";

export default function LoginPage() {
  const router = useRouter();
  const { setSession } = useSession();
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [user, setUser] = useState("developer");
  const [busy, setBusy] = useState(false);

  function devSignIn(e: React.FormEvent) {
    e.preventDefault();
    const s = { mode: "dev" as const, user, tenantId };
    saveSession(s);
    setSession(s);
    router.replace(takeReturnTo());
  }

  return (
    <main className="grid min-h-screen place-items-center px-4">
      <div className="w-full max-w-sm">
        <Link href="/" className="mb-8 flex items-center gap-2">
          <span className="grid h-9 w-9 place-items-center rounded-lg bg-accent font-bold text-accent-fg">M</span>
          <div>
            <h1 className="text-lg font-semibold">Meridian Developers</h1>
            <p className="text-xs text-muted">Apps, API keys, webhooks and the sandbox console</p>
          </div>
        </Link>
        <div className="rounded-[var(--radius)] border border-border bg-surface p-6">
          <Button className="w-full justify-center" disabled={busy}
            onClick={async () => { setBusy(true); await oidc().signinRedirect(); }}>
            Sign in with single sign-on
          </Button>
          <div className="my-5 flex items-center gap-3 text-xs text-muted">
            <span className="h-px flex-1 bg-border" />local development<span className="h-px flex-1 bg-border" />
          </div>
          <form onSubmit={devSignIn} className="flex flex-col gap-4">
            <Field label="Tenant ID" hint="Services must run in dev security mode">
              <Input value={tenantId} onChange={(e) => setTenantId(e.target.value)} required
                pattern="[\-0-9a-fA-F]{36}" />
            </Field>
            <Field label="User">
              <Input value={user} onChange={(e) => setUser(e.target.value)} required />
            </Field>
            <Button type="submit" variant="secondary" className="justify-center">Continue in dev mode</Button>
          </form>
        </div>
        <p className="mt-4 text-center text-xs text-muted">
          The documentation is public — <Link href="/docs" className="text-accent hover:underline">browse the guides</Link> without signing in.
        </p>
      </div>
    </main>
  );
}
