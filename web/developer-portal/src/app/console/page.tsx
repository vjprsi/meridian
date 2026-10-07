"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { identity, unwrap, webhooks } from "@/api/client";
import { useSession } from "@/components/providers";
import { Card, ErrorBanner, PageHeader } from "@/components/ui";

function Stat({ label, value, href, tone }: { label: string; value: number | string | undefined; href: string; tone?: "bad" }) {
  return (
    <Link href={href} className="rounded-[var(--radius)] border border-border bg-surface p-4 hover:border-accent">
      <p className="text-xs text-muted">{label}</p>
      <p className={`num mt-1 text-2xl font-semibold ${tone === "bad" ? "text-bad" : ""}`}>{value ?? "—"}</p>
    </Link>
  );
}

/** Counts a list up to `cap` items (one page) and says "cap+" when there are more. */
function count(page: { data: unknown[]; has_more: boolean } | undefined, cap: number) {
  if (!page) return undefined;
  return page.has_more ? `${cap}+` : page.data.length;
}

export default function ConsoleHome() {
  const { session } = useSession();
  const apps = useQuery({ queryKey: ["home", "apps"], queryFn: async () => unwrap(await identity.GET("/v1/api-clients", { params: { query: { limit: 100, status: "ACTIVE" } } })), retry: false });
  const endpoints = useQuery({ queryKey: ["home", "endpoints"], queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-endpoints", { params: { query: { limit: 100 } } })), retry: false });
  const failed = useQuery({ queryKey: ["home", "failed"], queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-deliveries", { params: { query: { limit: 100, status: "FAILED" } } })), retry: false });
  const failing = endpoints.data?.data.filter((e) => e.status !== "ACTIVE").length;

  return (
    <>
      <PageHeader title={`Welcome${session ? `, ${session.user}` : ""}`} subtitle="Your apps, keys and webhooks in this tenant." />
      <ErrorBanner error={apps.error ?? endpoints.error} />
      <div className="mb-8 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Active apps" value={count(apps.data, 100)} href="/console/apps" />
        <Stat label="Webhook endpoints" value={count(endpoints.data, 100)} href="/console/webhooks" />
        <Stat label="Endpoints failing / disabled" value={failing} href="/console/webhooks" tone={failing ? "bad" : undefined} />
        <Stat label="Failed deliveries" value={count(failed.data, 100)} href="/console/deliveries" tone={failed.data?.data.length ? "bad" : undefined} />
      </div>
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Next steps">
          <ol className="list-decimal space-y-2 pl-5 text-sm">
            <li><Link className="text-accent hover:underline" href="/console/apps">Create a sandbox app</Link> and pick its scopes; store the client secret.</li>
            <li>Exchange it for a token (<Link className="text-accent hover:underline" href="/docs/guides/getting-started">getting started</Link>) or create an API key.</li>
            <li>Explore operations in the <Link className="text-accent hover:underline" href="/console/try">try-it console</Link> with your own session.</li>
            <li><Link className="text-accent hover:underline" href="/console/webhooks">Add a webhook endpoint</Link>, subscribe to events and send a test event.</li>
          </ol>
        </Card>
        <Card title="Your session">
          {session && (
            <dl className="grid grid-cols-[8rem_1fr] gap-y-2 text-sm">
              <dt className="text-muted">User</dt><dd>{session.user}</dd>
              <dt className="text-muted">Tenant</dt><dd className="font-mono text-xs">{session.tenantId || "—"}</dd>
              <dt className="text-muted">Sign-in</dt><dd>{session.mode === "oidc" ? "Single sign-on (bearer token)" : "Dev mode (X-Tenant-Id / X-User headers)"}</dd>
              <dt className="text-muted">Token scopes</dt>
              <dd className="font-mono text-xs">{session.scopes?.filter((s) => s.includes(":")).join(" ") || "—"}</dd>
            </dl>
          )}
        </Card>
      </div>
    </>
  );
}
