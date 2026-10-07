"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { unwrap, webhooks, type WebhookDelivery, type WebhookDeliveryAttempt, type WebhookEndpoint } from "@/api/client";
import { useEvents } from "@/lib/spec";
import { CodeBlock } from "./code";
import { Action, Details, Modal, Pager, idem, usePaged } from "./console";
import { Badge, Card, DateTime, ErrorBanner, Input, Loading, Mono, Select, Table, cx } from "./ui";

/* Webhook building blocks shared by the endpoint detail page and the global delivery log. */

const DELIVERY_STATUSES = ["", "PENDING", "RETRYING", "SUCCEEDED", "FAILED", "ABANDONED"] as const;
type DeliveryStatus = (typeof DELIVERY_STATUSES)[number];

/** Every event type in the AsyncAPI catalogue plus the wildcard patterns webhook-service accepts. */
export function useEventTypes() {
  const events = useEvents();
  return useMemo(() => {
    const types: { value: string; label: string; group: string }[] = [{ value: "*", label: "* (every event)", group: "Wildcards" }];
    for (const c of events.data?.channels ?? []) {
      types.push({ value: `${c.id}.*`, label: `${c.id}.* (all ${c.messages.length} ${c.id} events)`, group: "Wildcards" });
    }
    for (const c of events.data?.channels ?? []) {
      for (const m of c.messages) types.push({ value: m.name, label: m.title ? `${m.name} — ${m.title}` : m.name, group: c.description ?? c.id });
    }
    return { types, isLoading: events.isLoading };
  }, [events.data, events.isLoading]);
}

/** Searchable event-type picker fed by the event catalogue; `concrete` hides wildcard patterns (test events). */
export function EventTypePicker({ value, onChange, concrete }: { value: string; onChange: (v: string) => void; concrete?: boolean }) {
  const { types, isLoading } = useEventTypes();
  const [q, setQ] = useState("");
  const list = types.filter((t) => (!concrete || !t.value.includes("*")) && (!q || t.label.toLowerCase().includes(q.toLowerCase())));
  return (
    <div className="rounded-[var(--radius)] border border-border">
      <Input placeholder={isLoading ? "Loading event catalogue…" : `Search ${types.length} event types…`} value={q}
        onChange={(e) => setQ(e.target.value)} className="w-full rounded-b-none border-0 border-b" />
      <div className="max-h-56 overflow-y-auto text-sm" role="listbox">
        {list.slice(0, 300).map((t) => (
          <button key={t.value} type="button" role="option" aria-selected={t.value === value} onClick={() => onChange(t.value)}
            className={cx("block w-full truncate px-3 py-1 text-left", t.value === value ? "bg-accent-soft text-accent" : "hover:bg-surface-2")}>
            <span className="font-mono text-xs">{t.value}</span>
            {t.label !== t.value && <span className="ml-2 text-xs text-muted">{t.label.slice(t.value.length).replace(/^ — /, "")}</span>}
          </button>
        ))}
        {list.length > 300 && <p className="px-3 py-1 text-xs text-muted">{list.length - 300} more — refine the search.</p>}
        {!list.length && !isLoading && <p className="px-3 py-2 text-xs text-muted">No event types match.</p>}
      </div>
      {value && <p className="border-t border-border px-3 py-1.5 text-xs">Selected: <span className="font-mono">{value}</span></p>}
    </div>
  );
}

export function useEndpoints() {
  return useQuery({
    queryKey: ["webhook-endpoints", "all"],
    queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-endpoints", { params: { query: { limit: 200 } } })).data,
  });
}

/** Delivery log with filters, attempt history and manual retry; scoped to one endpoint when endpointId is set. */
export function DeliveryLog({ endpointId }: { endpointId?: string }) {
  const [status, setStatus] = useState<DeliveryStatus>("");
  const [eventType, setEventType] = useState("");
  const [endpoint, setEndpoint] = useState(endpointId ?? "");
  const [open, setOpen] = useState<WebhookDelivery | null>(null);
  const endpoints = useEndpoints();
  const urls = new Map((endpoints.data ?? []).map((e) => [e.id, e.url]));
  const filters = { status, eventType, endpoint };
  const list = usePaged<WebhookDelivery>(["webhook-deliveries"], async (cursor) => unwrap(await webhooks.GET("/v1/webhook-deliveries", {
    params: { query: {
      limit: 25, cursor,
      status: status || undefined,
      event_type: eventType.trim() || undefined,
      webhook_endpoint_id: endpoint || undefined,
    } },
  })), filters);

  return (
    <Card title="Deliveries">
      <div className="mb-4 flex flex-wrap gap-3">
        {!endpointId && (
          <select value={endpoint} onChange={(e) => setEndpoint(e.target.value)} aria-label="Endpoint"
            className="max-w-xs rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm">
            <option value="">All endpoints</option>
            {(endpoints.data ?? []).map((e) => <option key={e.id} value={e.id}>{e.url}</option>)}
          </select>
        )}
        <Select value={status} onChange={(e) => setStatus(e.target.value as DeliveryStatus)} options={[...DELIVERY_STATUSES]} aria-label="Statuses" />
        <Input value={eventType} onChange={(e) => setEventType(e.target.value)} placeholder="Event type (exact)" className="w-72" />
      </div>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows as unknown as Record<string, unknown>[]} onRow={(r) => setOpen(r as unknown as WebhookDelivery)}
          empty="No deliveries yet. Subscribe an endpoint to events, or send a test event."
          columns={[
            { key: "created_at", header: "Created", render: (r) => <DateTime value={String(r.created_at)} /> },
            { key: "event_type", header: "Event", render: (r) => <span className="font-mono text-xs">{String(r.event_type)}</span> },
            ...(endpointId ? [] : [{ key: "webhook_endpoint_id", header: "Endpoint", render: (r: Record<string, unknown>) => <span className="text-xs">{urls.get(String(r.webhook_endpoint_id)) ?? <Mono>{String(r.webhook_endpoint_id).slice(0, 8)}…</Mono>}</span> }]),
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "attempt_count", header: "Attempts", align: "right" as const },
            { key: "last_status_code", header: "HTTP", align: "right" as const, render: (r) => <span className="num">{r.last_status_code == null ? "—" : String(r.last_status_code)}</span> },
            { key: "next_attempt_at", header: "Next attempt", render: (r) => <DateTime value={r.next_attempt_at as string | null} /> },
          ]} />
      )}
      <Pager p={list} />
      {open && <DeliveryDetail d={open} onClose={() => setOpen(null)} />}
    </Card>
  );
}

function DeliveryDetail({ d, onClose }: { d: WebhookDelivery; onClose: () => void }) {
  const fresh = useQuery({
    queryKey: ["webhook-delivery", d.id],
    queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-deliveries/{id}", { params: { path: { id: d.id } } })),
    initialData: d,
  });
  const attempts = useQuery({
    queryKey: ["webhook-delivery-attempts", d.id],
    queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-deliveries/{id}/attempts", { params: { path: { id: d.id }, query: { limit: 50 } } })).data,
  });
  const x = fresh.data;
  const retryable = ["FAILED", "ABANDONED", "RETRYING"].includes(x.status);
  return (
    <Modal title={`Delivery ${x.event_type}`} onClose={onClose} wide>
      <div className="mb-4 flex items-center justify-between gap-3">
        <Badge value={x.status} />
        {retryable && (
          <Action label="Retry now" variant="primary"
            invalidate={[["webhook-deliveries"], ["webhook-delivery", d.id], ["webhook-delivery-attempts", d.id]]}
            run={async () => unwrap(await webhooks.POST("/v1/webhook-deliveries/{id}/retry", { params: { path: { id: d.id }, header: idem() } }))} />
        )}
      </div>
      <Details items={[
        ["Delivery id", <Mono key="d">{x.id}</Mono>],
        ["Event id", <Mono key="e">{x.event_id}</Mono>],
        ["Attempts", x.attempt_count],
        ["Last HTTP status", x.last_status_code],
        ["Delivered", <DateTime key="dl" value={x.delivered_at} />],
        ["Next attempt", <DateTime key="n" value={x.next_attempt_at} />],
        ["Ordering key", x.ordering_key],
        ["Replay job", x.replay_job_id],
        ["Last error", x.last_error],
      ]} />
      <h3 className="mt-5 mb-2 text-xs font-semibold uppercase tracking-wide text-muted">Attempts</h3>
      <ErrorBanner error={attempts.error} />
      {attempts.isLoading ? <Loading /> : (
        <div className="flex flex-col gap-2">
          {(attempts.data ?? []).length === 0 && <p className="text-sm text-muted">No attempts yet.</p>}
          {(attempts.data ?? []).map((a: WebhookDeliveryAttempt) => (
            <details key={a.id} className="rounded border border-border px-3 py-2 text-sm">
              <summary className="flex cursor-pointer flex-wrap items-center gap-3">
                <span className="font-medium">#{a.attempt_no}</span>
                <span className={cx("num font-mono", a.status_code && a.status_code < 300 ? "text-good" : "text-bad")}>{a.status_code ?? "no response"}</span>
                <span className="num text-xs text-muted">{a.latency_ms != null ? `${a.latency_ms} ms` : ""}</span>
                <DateTime value={a.created_at} />
                {a.error && <span className="text-xs text-bad">{a.error}</span>}
              </summary>
              {a.request_headers && <CodeBlock title="Request headers" code={JSON.stringify(a.request_headers, null, 2)} />}
              {a.response_body && <CodeBlock title="Response body" code={a.response_body} />}
            </details>
          ))}
        </div>
      )}
      <CodeBlock title="Payload (data)" code={JSON.stringify(x.payload, null, 2)} />
    </Modal>
  );
}

export function endpointLabel(e: Pick<WebhookEndpoint, "url" | "description">) {
  return e.description ? `${e.description} (${e.url})` : e.url;
}
