"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { CodeBlock } from "@/components/code";
import { SchemaTree } from "@/components/reference";
import { ErrorBanner, Input, Loading, PageHeader, cx } from "@/components/ui";
import { fullExample, refName, typeLabel, useEvents, type EventMessage, type Json } from "@/lib/spec";

/** The JSON body webhook-service POSTs to subscribed endpoints (signed with Meridian-Signature). */
function webhookBody(doc: Json, m: EventMessage): string {
  return JSON.stringify({
    id: "0199a6f2-7c1e-7b55-9a0e-3f2d4c5b6a71",
    type: m.name,
    api_version: "v1",
    created_at: "2026-01-01T12:00:00Z",
    delivery_id: "0199a6f2-7c1f-7a10-8c3d-5e6f7a8b9c0d",
    data: fullExample(doc, m.payload),
  }, null, 2);
}

function EventCard({ doc, m }: { doc: Json; m: EventMessage }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (decodeURIComponent(window.location.hash.slice(1)) === m.name) {
      setOpen(true);
      requestAnimationFrame(() => document.getElementById(m.name)?.scrollIntoView({ block: "start" }));
    }
  }, [m.name]);
  return (
    <article id={m.name} className="scroll-mt-24 rounded-[var(--radius)] border border-border bg-surface">
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}
        className="flex w-full flex-wrap items-center gap-3 px-4 py-2.5 text-left hover:bg-surface-2">
        <span className="w-3 font-mono text-xs text-muted">{open ? "−" : "+"}</span>
        <code className="font-mono text-sm font-medium">{m.name}</code>
        <span className="text-sm text-muted">{m.title ?? m.summary}</span>
        {m.payload && <span className="ml-auto font-mono text-[11px] text-accent">{typeLabel(m.payload)}</span>}
      </button>
      {open && (
        <div className="grid gap-6 border-t border-border p-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
          <div className="min-w-0">
            <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-muted">
              Payload (<span className="font-mono normal-case">data</span>){m.payload?.$ref && <> · <span className="font-mono normal-case text-accent">{refName(m.payload.$ref)}</span></>}
            </h4>
            {m.payload ? <SchemaTree doc={doc} schema={m.payload} /> : <p className="text-xs text-muted">No payload schema.</p>}
            {m.headers && (
              <>
                <h4 className="mt-4 mb-1 text-xs font-semibold uppercase tracking-wide text-muted">Kafka headers (CloudEvents binary mode)</h4>
                <SchemaTree doc={doc} schema={m.headers} />
              </>
            )}
          </div>
          <div className="min-w-0">
            <CodeBlock code={webhookBody(doc, m)} title="Webhook delivery body" className="mt-0" />
            <Link href={`/console/webhooks?subscribe=${encodeURIComponent(m.name)}`} className="text-xs text-accent hover:underline">
              Subscribe an endpoint to this event →
            </Link>
          </div>
        </div>
      )}
    </article>
  );
}

function Events() {
  const params = useSearchParams();
  const events = useEvents();
  const [filter, setFilter] = useState("");
  const [channel, setChannel] = useState<string | null>(params.get("channel"));
  const doc = useMemo<Json>(() => ({ components: { schemas: events.data?.schemas ?? {} } }), [events.data]);
  const channels = events.data?.channels ?? [];
  const f = filter.trim().toLowerCase();
  const active = f ? null : channels.find((c) => c.id === channel) ?? channels[0];
  const shown = f
    ? channels.map((c) => ({ ...c, messages: c.messages.filter((m) => `${m.name} ${m.title ?? ""}`.toLowerCase().includes(f)) }))
      .filter((c) => c.messages.length)
    : active ? [active] : [];

  return (
    <>
      <PageHeader title="Event catalogue"
        subtitle={<>{events.data?.info.description} Content type <span className="font-mono">{events.data?.defaultContentType}</span>.
          Every event can be delivered to your HTTPS endpoint by <Link href="/docs/guides/webhooks" className="text-accent underline">webhooks</Link>.</>} />
      <ErrorBanner error={events.error} />
      {events.isLoading ? <Loading /> : (
        <div className="flex gap-6">
          <nav className="sticky top-[77px] hidden max-h-[calc(100vh-100px)] w-56 shrink-0 overflow-y-auto md:block" aria-label="Channels">
            <Input placeholder="Filter events…" value={filter} onChange={(e) => setFilter(e.target.value)} className="mb-2 w-full" />
            {channels.map((c) => (
              <button key={c.id} type="button" onClick={() => { setFilter(""); setChannel(c.id); history.replaceState(null, "", `/events?channel=${c.id}`); }}
                className={cx("flex w-full items-center justify-between rounded-md px-2 py-1 text-left text-sm",
                  !f && c.id === active?.id ? "bg-accent-soft font-medium text-accent" : "hover:bg-surface-2")}>
                <span className="truncate">{c.description?.replace(/ events$/, "") ?? c.id}</span>
                <span className="num ml-2 text-[11px] text-muted">{c.messages.length}</span>
              </button>
            ))}
          </nav>
          <div className="min-w-0 flex-1">
            <Input placeholder="Filter events…" value={filter} onChange={(e) => setFilter(e.target.value)} className="mb-4 w-full md:hidden" />
            {shown.map((c) => (
              <section key={c.id} className="mb-8">
                <h2 className="text-lg font-semibold">{c.description ?? c.id}</h2>
                <p className="mt-0.5 mb-3 text-xs text-muted">Kafka topic <span className="font-mono">{c.address}</span> · key = aggregate id · {c.messages.length} event types</p>
                <div className="flex flex-col gap-2">
                  {c.messages.map((m) => <EventCard key={m.key} doc={doc} m={m} />)}
                </div>
              </section>
            ))}
            {f && shown.length === 0 && <p className="text-sm text-muted">No events match “{filter}”.</p>}
          </div>
        </div>
      )}
    </>
  );
}

export default function Page() {
  return <Suspense fallback={<Loading />}><Events /></Suspense>;
}
