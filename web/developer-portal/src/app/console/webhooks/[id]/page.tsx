"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { unwrap, webhooks, type WebhookEndpoint, type WebhookReplayJob, type WebhookSubscription } from "@/api/client";
import { Action, Details, Modal, Pager, SecretReveal, Tabs, Textarea, idem, usePaged } from "@/components/console";
import { DeliveryLog, EventTypePicker } from "@/components/webhooks";
import { Badge, Button, Card, DateTime, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";

type Tab = "subscriptions" | "deliveries" | "replays" | "settings";
const TABS = [
  { id: "subscriptions", label: "Subscriptions" },
  { id: "deliveries", label: "Deliveries" },
  { id: "replays", label: "Replays" },
  { id: "settings", label: "Settings" },
] as const;

function EndpointDetail() {
  const { id } = useParams<{ id: string }>();
  const params = useSearchParams();
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("subscriptions");
  const [secret, setSecret] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const ep = useQuery({
    queryKey: ["webhook-endpoint", id],
    queryFn: async () => unwrap(await webhooks.GET("/v1/webhook-endpoints/{id}", { params: { path: { id } } })),
  });
  const e = ep.data;
  const inv = [["webhook-endpoint", id], ["webhook-endpoints"]];

  if (ep.isLoading) return <Loading />;
  return (
    <>
      <p className="mb-1 text-xs text-muted"><Link href="/console/webhooks" className="hover:text-text">Webhooks</Link> / endpoint</p>
      <PageHeader title={e?.description || e?.url || "Endpoint"}
        subtitle={e && <><span className="font-mono">{e.url}</span> · {e.environment} · <Badge value={e.status} tone={e.status === "FAILING" ? "warn" : undefined} />
          {e.disabled_reason && <> · {e.disabled_reason}</>}</>}
        actions={e && <>
          <Button variant="secondary" disabled={e.status === "DISABLED"} onClick={() => setTesting(true)}>Send test event</Button>
          <Action label="Rotate secret" invalidate={inv}
            confirm="A new signing secret is generated and shown once. The old one stays valid for 24 hours; during that window each request carries both signatures."
            run={async () => unwrap(await webhooks.POST("/v1/webhook-endpoints/{id}/rotate-secret", { params: { path: { id }, header: idem() } }))}
            onDone={(r) => setSecret(r.signing_secret ?? null)} />
          {e.status === "DISABLED" ? (
            <Action label="Enable" invalidate={inv}
              run={async () => unwrap(await webhooks.POST("/v1/webhook-endpoints/{id}/enable", { params: { path: { id }, header: idem() } }))} />
          ) : (
            <Action label="Disable" invalidate={inv} reason="Reason" confirm="Deliveries are held while the endpoint is disabled."
              run={async (reason) => unwrap(await webhooks.POST("/v1/webhook-endpoints/{id}/disable", { params: { path: { id }, header: idem() }, body: { reason } }))} />
          )}
          <Action label="Delete" variant="danger" invalidate={[["webhook-endpoints"]]}
            confirm="Delete this endpoint and its subscriptions? Pending deliveries are dropped."
            run={async () => {
              const r = await webhooks.DELETE("/v1/webhook-endpoints/{id}", { params: { path: { id } } });
              if (!r.response.ok) unwrap(r);
              return true;
            }}
            onDone={() => router.replace("/console/webhooks")} />
        </>} />
      <ErrorBanner error={ep.error} />
      {e && (
        <>
          <Tabs tabs={TABS} value={tab} onChange={setTab} />
          {tab === "subscriptions" && <Subscriptions endpointId={id} preset={params.get("subscribe")} />}
          {tab === "deliveries" && <DeliveryLog endpointId={id} />}
          {tab === "replays" && <Replays endpointId={id} />}
          {tab === "settings" && <Settings e={e} />}
        </>
      )}
      {testing && <TestEvent endpointId={id} onClose={() => setTesting(false)} onSent={() => { setTesting(false); setTab("deliveries"); }} />}
      {secret && <SecretReveal title="New signing secret" label="Signing secret" secret={secret} onDone={() => setSecret(null)} />}
    </>
  );
}

function TestEvent({ endpointId, onClose, onSent }: { endpointId: string; onClose: () => void; onSent: () => void }) {
  const qc = useQueryClient();
  const [type, setType] = useState("");
  const m = useMutation({
    mutationFn: async () => unwrap(await webhooks.POST("/v1/webhook-endpoints/{id}/test", {
      params: { path: { id: endpointId }, header: idem() }, body: { event_type: type },
    })),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["webhook-deliveries"] }); onSent(); },
  });
  return (
    <Modal title="Send test event" onClose={onClose} wide>
      <ErrorBanner error={m.error} />
      <p className="mb-3 text-sm text-muted">Sends one synthetic, signed event (<span className="font-mono">{"{ \"test\": true, … }"}</span>) to the endpoint right away, without retries. The result appears in the delivery log.</p>
      <EventTypePicker value={type} onChange={setType} concrete />
      <div className="mt-5 flex justify-end gap-2">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button disabled={!type || m.isPending} onClick={() => m.mutate()}>{m.isPending ? "Sending…" : "Send"}</Button>
      </div>
    </Modal>
  );
}

function Subscriptions({ endpointId, preset }: { endpointId: string; preset: string | null }) {
  const [adding, setAdding] = useState(!!preset);
  const list = usePaged<WebhookSubscription>(["webhook-subscriptions", endpointId], async (cursor) => unwrap(await webhooks.GET("/v1/webhook-subscriptions", {
    params: { query: { limit: 50, cursor, webhook_endpoint_id: endpointId } },
  })));
  const inv = [["webhook-subscriptions", endpointId]];
  return (
    <Card title="Event subscriptions" actions={<Button onClick={() => setAdding(true)}>Subscribe to events</Button>}>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows as unknown as Record<string, unknown>[]} empty="No subscriptions: this endpoint receives nothing yet."
          columns={[
            { key: "event_type", header: "Event type", render: (r) => <Link href={String(r.event_type).includes("*") ? "/events" : `/events?channel=${String(r.event_type).split(".")[0]}#${String(r.event_type)}`} className="font-mono text-xs text-accent hover:underline">{String(r.event_type)}</Link> },
            { key: "filter_expression", header: "Filter", render: (r) => r.filter_expression ? <span className="font-mono text-xs">{String(r.filter_expression)}</span> : <span className="text-muted">—</span> },
            { key: "is_active", header: "Active", render: (r) => <Badge value={r.is_active ? "ACTIVE" : "PAUSED"} /> },
            { key: "created_at", header: "Since", render: (r) => <DateTime value={String(r.created_at)} /> },
            { key: "actions", header: "", render: (r) => {
              const s = r as unknown as WebhookSubscription;
              return (
                <span className="flex justify-end gap-2">
                  <Action label={s.is_active ? "Pause" : "Resume"} invalidate={inv}
                    run={async () => unwrap(await webhooks.PATCH("/v1/webhook-subscriptions/{id}", {
                      params: { path: { id: s.id }, header: { "If-Match": String(s.row_version) } }, body: { is_active: !s.is_active },
                    }))} />
                  <Action label="Remove" variant="danger" invalidate={inv} confirm={`Stop sending ${s.event_type} to this endpoint?`}
                    run={async () => {
                      const res = await webhooks.DELETE("/v1/webhook-subscriptions/{id}", { params: { path: { id: s.id } } });
                      if (!res.response.ok) unwrap(res);
                      return true;
                    }} />
                </span>
              );
            } },
          ]} />
      )}
      <Pager p={list} />
      {adding && <AddSubscription endpointId={endpointId} preset={preset ?? ""} onClose={() => setAdding(false)} />}
    </Card>
  );
}

function AddSubscription({ endpointId, preset, onClose }: { endpointId: string; preset: string; onClose: () => void }) {
  const qc = useQueryClient();
  const [type, setType] = useState(preset);
  const [filter, setFilter] = useState("");
  const m = useMutation({
    mutationFn: async () => unwrap(await webhooks.POST("/v1/webhook-subscriptions", {
      params: { header: idem() },
      body: { webhook_endpoint_id: endpointId, event_type: type, is_active: true, ...(filter.trim() ? { filter_expression: filter.trim() } : {}) },
    })),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["webhook-subscriptions", endpointId] }); onClose(); },
  });
  return (
    <Modal title="Subscribe to events" onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <Field label="Event type" hint="An exact type, a domain wildcard (payments.*) or * for everything">
          <EventTypePicker value={type} onChange={setType} />
        </Field>
        <Field label="Filter (optional)" hint='Conjunction of == / != comparisons on payload fields, e.g. data.currency == "USD" && status != "FAILED"'>
          <Input value={filter} onChange={(e) => setFilter(e.target.value)} className="font-mono text-xs" />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={!type || m.isPending}>{m.isPending ? "Saving…" : "Subscribe"}</Button>
        </div>
      </form>
    </Modal>
  );
}

function Replays({ endpointId }: { endpointId: string }) {
  const [creating, setCreating] = useState(false);
  const list = usePaged<WebhookReplayJob>(["event-replays", endpointId], async (cursor) => unwrap(await webhooks.GET("/v1/event-replays", {
    params: { query: { limit: 25, cursor, webhook_endpoint_id: endpointId } },
  })));
  const inv = [["event-replays", endpointId]];
  return (
    <Card title="Event replays" actions={<Button onClick={() => setCreating(true)}>Replay events</Button>}>
      <p className="mb-4 text-sm text-muted">Re-deliver past events for a time window (up to 31 days) — e.g. after an outage of your endpoint. Replayed deliveries show up in the delivery log with their replay job.</p>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows as unknown as Record<string, unknown>[]} empty="No replays."
          columns={[
            { key: "created_at", header: "Requested", render: (r) => <DateTime value={String(r.created_at)} /> },
            { key: "window", header: "Window", render: (r) => <span className="text-xs"><DateTime value={String(r.from_time)} /> → <DateTime value={String(r.to_time)} /></span> },
            { key: "event_types", header: "Events", render: (r) => <span className="font-mono text-xs">{((r.event_types as string[] | null) ?? []).join(", ") || "all subscribed"}</span> },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "progress", header: "Replayed", align: "right", render: (r) => <span className="num">{r.events_replayed == null ? "—" : `${String(r.events_replayed)} / ${String(r.events_total ?? "?")}`}</span> },
            { key: "actions", header: "", render: (r) => ["QUEUED", "RUNNING"].includes(String(r.status)) ? (
              <Action label="Cancel" variant="danger" invalidate={inv} confirm="Cancel this replay? Events already re-sent stay delivered."
                run={async () => unwrap(await webhooks.POST("/v1/event-replays/{id}/cancel", { params: { path: { id: String(r.id) }, header: idem() } }))} />
            ) : null },
          ]} />
      )}
      <Pager p={list} />
      {creating && <CreateReplay endpointId={endpointId} onClose={() => setCreating(false)} />}
    </Card>
  );
}

function CreateReplay({ endpointId, onClose }: { endpointId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const now = new Date();
  const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const [from, setFrom] = useState(local(new Date(now.getTime() - 24 * 3600_000)));
  const [to, setTo] = useState(local(now));
  const [types, setTypes] = useState("");
  const m = useMutation({
    mutationFn: async () => unwrap(await webhooks.POST("/v1/event-replays", {
      params: { header: idem() },
      body: {
        webhook_endpoint_id: endpointId,
        from_time: new Date(from).toISOString(),
        to_time: new Date(to).toISOString(),
        ...(types.trim() ? { event_types: types.split(/[\s,]+/).filter(Boolean) } : {}),
      },
    })),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["event-replays", endpointId] }); onClose(); },
  });
  return (
    <Modal title="Replay events" onClose={onClose}>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="From"><Input type="datetime-local" required value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><Input type="datetime-local" required value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </div>
        <Field label="Event types (optional)" hint="Space or comma separated; empty = everything the endpoint subscribes to">
          <Textarea rows={2} value={types} onChange={(e) => setTypes(e.target.value)} placeholder="payments.payment.settled payments.payment.failed" />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={m.isPending}>{m.isPending ? "Queuing…" : "Start replay"}</Button>
        </div>
      </form>
    </Modal>
  );
}

function Settings({ e }: { e: WebhookEndpoint }) {
  const qc = useQueryClient();
  const [url, setUrl] = useState(e.url);
  const [description, setDescription] = useState(e.description ?? "");
  const [rps, setRps] = useState(String(e.max_requests_per_second));
  const [headers, setHeaders] = useState(e.custom_headers ? JSON.stringify(e.custom_headers, null, 2) : "");
  const m = useMutation({
    mutationFn: async () => {
      let custom: Record<string, unknown> | undefined;
      if (headers.trim()) {
        try { custom = JSON.parse(headers); } catch { throw new Error("Custom headers must be a JSON object"); }
      }
      return unwrap(await webhooks.PATCH("/v1/webhook-endpoints/{id}", {
        params: { path: { id: e.id }, header: { "If-Match": String(e.row_version) } },
        body: { url, description, max_requests_per_second: Number(rps), ...(custom ? { custom_headers: custom } : {}) },
      }));
    },
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["webhook-endpoint", e.id] }); qc.invalidateQueries({ queryKey: ["webhook-endpoints"] }); },
  });
  return (
    <div className="flex flex-col gap-6">
      <Card title="Status">
        <Details items={[
          ["Endpoint id", <Mono key="id">{e.id}</Mono>],
          ["Payload version", e.api_version],
          ["Signing secret version", e.signing_secret_version],
          ["Previous secret valid until", <DateTime key="p" value={e.previous_secret_expires_at} />],
          ["Consecutive failures", e.consecutive_failures],
          ["Last success", <DateTime key="s" value={e.last_success_at} />],
          ["Last failure", <DateTime key="f" value={e.last_failure_at} />],
          ["mTLS", e.mtls_enabled ? "enabled" : "off"],
          ["App", e.api_client_id ? <Link key="a" className="text-accent hover:underline" href={`/console/apps/${e.api_client_id}`}>open app</Link> : null],
        ]} />
      </Card>
      <Card title="Edit">
        <form className="flex max-w-2xl flex-col gap-4" onSubmit={(ev) => { ev.preventDefault(); m.mutate(); }}>
          <ErrorBanner error={m.error} />
          {m.isSuccess && <p className="text-sm text-good">Saved.</p>}
          <Field label="URL"><Input type="url" required value={url} onChange={(ev) => setUrl(ev.target.value)} /></Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Description"><Input value={description} onChange={(ev) => setDescription(ev.target.value)} /></Field>
            <Field label="Max requests per second"><Input type="number" min={1} value={rps} onChange={(ev) => setRps(ev.target.value)} /></Field>
          </div>
          <Field label="Custom headers (JSON)"><Textarea rows={3} value={headers} onChange={(ev) => setHeaders(ev.target.value)} /></Field>
          <div><Button type="submit" disabled={m.isPending}>{m.isPending ? "Saving…" : "Save changes"}</Button></div>
        </form>
      </Card>
    </div>
  );
}

export default function Page() {
  return <Suspense><EndpointDetail /></Suspense>;
}
