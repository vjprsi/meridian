"use client";

import Link from "next/link";
import { CodeBlock } from "@/components/code";
import { GUIDES } from "@/lib/guides";
import { useCatalog } from "@/lib/spec";

const QUICKSTART = `# 1. Get a token with your sandbox client (OAuth 2.0 client credentials)
TOKEN=$(curl -s -X POST https://auth.meridian.example/oauth2/token \\
  -d grant_type=client_credentials \\
  -d client_id=$CLIENT_ID -d client_secret=$CLIENT_SECRET \\
  -d scope="party:read accounts:read" | jq -r .access_token)

# 2. Make your first call
curl https://sandbox.api.meridian.example/v1/customers?limit=5 \\
  -H "Authorization: Bearer $TOKEN"`;

export default function Landing() {
  const catalog = useCatalog();
  const c = catalog.data;
  const ops = c?.domains.reduce((n, d) => n + d.operations, 0);
  return (
    <main>
      <section className="border-b border-border bg-surface">
        <div className="mx-auto grid max-w-[90rem] gap-10 px-6 py-14 lg:grid-cols-2 lg:py-20">
          <div>
            <p className="mb-3 text-xs font-semibold uppercase tracking-wider text-accent">Meridian Core APIs</p>
            <h1 className="text-3xl font-semibold tracking-tight md:text-4xl">Build on a real-time, API-first core bank</h1>
            <p className="mt-4 max-w-xl text-muted">
              Deposits, ledger, payments, cards, lending, wallets, risk and open banking behind one consistent REST API
              with OAuth 2.0 scopes, idempotent writes, keyset pagination, RFC 9457 errors and signed webhooks.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link href="/docs/guides/getting-started" className="rounded-[var(--radius)] bg-accent px-4 py-2 text-sm font-medium text-accent-fg hover:opacity-90">Get started</Link>
              <Link href="/reference" className="rounded-[var(--radius)] border border-border bg-surface px-4 py-2 text-sm font-medium hover:bg-surface-2">API reference</Link>
              <Link href="/console" className="rounded-[var(--radius)] px-4 py-2 text-sm font-medium text-muted hover:text-text">Open the console →</Link>
            </div>
            <dl className="mt-10 grid max-w-md grid-cols-3 gap-4 text-sm">
              <div><dt className="text-muted">Domains</dt><dd className="num text-2xl font-semibold">{c?.domains.length ?? "—"}</dd></div>
              <div><dt className="text-muted">Operations</dt><dd className="num text-2xl font-semibold">{ops?.toLocaleString() ?? "—"}</dd></div>
              <div><dt className="text-muted">Event types</dt><dd className="num text-2xl font-semibold">{c?.events.messages.toLocaleString() ?? "—"}</dd></div>
            </dl>
          </div>
          <div className="min-w-0"><CodeBlock code={QUICKSTART} title="Quickstart" /></div>
        </div>
      </section>

      <section className="mx-auto max-w-[90rem] px-6 py-12">
        <div className="grid gap-4 md:grid-cols-3">
          {[
            { href: "/docs", t: "Guides", d: "Sandbox access, OAuth scopes, idempotency, pagination, errors, webhooks and versioning." },
            { href: "/reference", t: "API reference", d: "Every operation per domain with parameters, schemas, required scopes and example requests." },
            { href: "/events", t: "Event catalogue", d: "Domain events (CloudEvents 1.0) with payload schemas — subscribe to them with webhooks." },
          ].map((x) => (
            <Link key={x.href} href={x.href} className="rounded-[var(--radius)] border border-border bg-surface p-5 hover:border-accent">
              <h2 className="font-semibold">{x.t}</h2>
              <p className="mt-1 text-sm text-muted">{x.d}</p>
            </Link>
          ))}
        </div>

        <h2 className="mt-12 mb-4 text-lg font-semibold">API domains</h2>
        {catalog.error && <p className="text-sm text-bad">{String(catalog.error)}</p>}
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {(c?.domains ?? []).map((d) => (
            <Link key={d.id} href={`/reference/${d.id}`} className="rounded-[var(--radius)] border border-border bg-surface p-4 hover:border-accent">
              <div className="flex items-baseline justify-between gap-2">
                <h3 className="text-sm font-semibold">{d.title}</h3>
                <span className="num text-xs text-muted">{d.operations} ops</span>
              </div>
              <p className="mt-1 line-clamp-3 text-xs text-muted">{d.description}</p>
            </Link>
          ))}
        </div>

        <h2 className="mt-12 mb-4 text-lg font-semibold">Guides</h2>
        <div className="flex flex-wrap gap-2">
          {GUIDES.map((g) => (
            <Link key={g.slug} href={`/docs/guides/${g.slug}`} className="rounded-full border border-border bg-surface px-3 py-1 text-sm hover:border-accent">{g.title}</Link>
          ))}
        </div>
      </section>
    </main>
  );
}
