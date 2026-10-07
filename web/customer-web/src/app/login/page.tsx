"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { customers, unwrap } from "@/api/client";
import type { Customer } from "@/api/types";
import { DEFAULT_TENANT, oidc, saveSession, type Session } from "@/lib/auth";
import { useT } from "@/lib/i18n";
import { useSession } from "@/components/providers";
import { Preferences } from "@/components/shell";
import { Badge, Button, ErrorBanner, Field, Input } from "@/components/ui";

const UUID = /^[0-9a-fA-F-]{36}$/;

export default function LoginPage() {
  const t = useT();
  const router = useRouter();
  const { setSession } = useSession();
  const [busy, setBusy] = useState(false);
  const [ssoError, setSsoError] = useState<unknown>(null);
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [q, setQ] = useState("");
  const [results, setResults] = useState<Customer[] | null>(null);
  const [searchError, setSearchError] = useState<unknown>(null);
  const [searching, setSearching] = useState(false);

  const devHeaders = { "X-Tenant-Id": tenantId, "X-User": "dev-login" };

  async function search(e: FormEvent) {
    e.preventDefault();
    setSearchError(null);
    setSearching(true);
    try {
      if (UUID.test(q.trim())) {
        const c = unwrap(await customers.GET("/v1/customers/{id}", { params: { path: { id: q.trim() } }, headers: devHeaders }));
        setResults([c]);
      } else {
        const r = unwrap(await customers.GET("/v1/customers/search", { params: { query: { q, limit: 10 } }, headers: devHeaders }));
        setResults(r.data);
      }
    } catch (err) {
      setSearchError(err);
      setResults(null);
    } finally {
      setSearching(false);
    }
  }

  function signInAs(c: Customer) {
    const s: Session = { mode: "dev", tenantId, customerId: c.id!, user: `customer:${c.customer_number ?? c.id}` };
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
          <h1 className="text-xl font-semibold">{t("login.title")}</h1>
          <p className="text-sm text-muted">{t("login.subtitle")}</p>
        </div>
      </div>

      <section aria-labelledby="sso" className="rounded-[var(--radius)] border border-border bg-surface p-5">
        <h2 id="sso" className="sr-only">{t("login.sso")}</h2>
        <Button className="w-full" disabled={busy} onClick={async () => {
          setBusy(true);
          setSsoError(null);
          try { await oidc().signinRedirect(); } catch (e) { setSsoError(e); setBusy(false); }
        }}>{t("login.sso")}</Button>
        <ErrorBanner error={ssoError} className="mt-3" />
        <p className="mt-4 text-center text-sm">
          {t("login.new")} <Link href="/signup" className="prose-link">{t("login.openAccount")}</Link>
        </p>
      </section>

      <section aria-labelledby="dev" className="mt-6 rounded-[var(--radius)] border border-dashed border-border-strong bg-surface p-5">
        <h2 id="dev" className="font-semibold">{t("login.devTitle")}</h2>
        <p className="mt-1 text-xs text-muted">{t("login.devHint")}</p>
        <form onSubmit={search} className="mt-4 flex flex-col gap-4">
          <Field label={t("login.tenant")}>
            <Input value={tenantId} onChange={(e) => setTenantId(e.target.value)} required pattern="[\-0-9a-fA-F]{36}"
              autoComplete="off" spellCheck={false} />
          </Field>
          <Field label={t("login.customerSearch")} hint={t("login.customerSearchHint")}>
            <Input value={q} onChange={(e) => setQ(e.target.value)} required autoComplete="off" />
          </Field>
          <Button type="submit" variant="secondary" disabled={searching}>{searching ? t("common.working") : t("login.find")}</Button>
        </form>
        <ErrorBanner error={searchError} className="mt-3" />
        {results && (
          <div className="mt-4" aria-live="polite">
            {results.length === 0 ? <p className="text-sm text-muted">{t("login.noMatch")}</p> : (
              <ul className="flex flex-col gap-2">
                {results.map((c) => (
                  <li key={c.id}>
                    <button type="button" onClick={() => signInAs(c)}
                      className="flex min-h-11 w-full items-center justify-between gap-3 rounded-[var(--radius)] border border-border px-3 py-2 text-left hover:bg-surface-2 focus-visible:outline-3 focus-visible:outline-focus">
                      <span>
                        <span className="block font-medium">{c.display_name ?? ([c.first_name, c.last_name].filter(Boolean).join(" ") || c.legal_name)}</span>
                        <span className="block text-xs text-muted">{c.customer_number}</span>
                      </span>
                      <Badge value={c.status} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      <section aria-label={t("prefs.title")} className="mt-6"><Preferences /></section>
    </main>
  );
}
