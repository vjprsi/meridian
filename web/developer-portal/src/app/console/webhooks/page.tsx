"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { identity, unwrap, webhooks, type WebhookEndpoint, type WebhookEndpointCreate } from "@/api/client";
import { Modal, Pager, SecretReveal, Textarea, idem, usePaged } from "@/components/console";
import { Badge, Button, Card, DateTime, ErrorBanner, Field, Input, Loading, PageHeader, Select, Table } from "@/components/ui";

type Env = "" | "SANDBOX" | "PRODUCTION";
type Status = "" | "ACTIVE" | "DISABLED" | "FAILING";

function WebhooksPage() {
  const router = useRouter();
  const params = useSearchParams();
  const subscribe = params.get("subscribe");
  const [environment, setEnvironment] = useState<Env>("");
  const [status, setStatus] = useState<Status>("");
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<WebhookEndpoint | null>(null);
  const list = usePaged<WebhookEndpoint>(["webhook-endpoints", "page"], async (cursor) => unwrap(await webhooks.GET("/v1/webhook-endpoints", {
    params: { query: { limit: 25, cursor, environment: environment || undefined, status: status || undefined } },
  })), { environment, status });
  const open = (id: string) => router.push(`/console/webhooks/${id}${subscribe ? `?subscribe=${encodeURIComponent(subscribe)}` : ""}`);

  return (
    <>
      <PageHeader title="Webhooks"
        subtitle="HTTPS endpoints that receive signed event notifications. Open an endpoint to manage its subscriptions, deliveries and replays."
        actions={<Button onClick={() => setCreating(true)}>Add endpoint</Button>} />
      {subscribe && (
        <div className="mb-4 rounded-[var(--radius)] border border-accent/30 bg-accent-soft px-4 py-3 text-sm">
          Choose the endpoint that should receive <span className="font-mono">{subscribe}</span> (or add a new one).
        </div>
      )}
      <Card>
        <div className="mb-4 flex flex-wrap gap-3">
          <Select value={environment} onChange={(e) => setEnvironment(e.target.value as Env)} options={["", "SANDBOX", "PRODUCTION"]} aria-label="Environments" />
          <Select value={status} onChange={(e) => setStatus(e.target.value as Status)} options={["", "ACTIVE", "FAILING", "DISABLED"]} aria-label="Statuses" />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows as unknown as Record<string, unknown>[]} onRow={(r) => open(String(r.id))}
            empty="No endpoints yet."
            columns={[
              { key: "url", header: "URL", render: (r) => <span className="font-mono text-xs">{String(r.url)}</span> },
              { key: "description", header: "Description" },
              { key: "environment", header: "Env", render: (r) => <Badge value={String(r.environment)} tone={r.environment === "PRODUCTION" ? "info" : "muted"} /> },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} tone={r.status === "FAILING" ? "warn" : undefined} /> },
              { key: "consecutive_failures", header: "Failures", align: "right" },
              { key: "last_success_at", header: "Last success", render: (r) => <DateTime value={r.last_success_at as string | null} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
      {creating && <CreateEndpoint onClose={() => setCreating(false)} onCreated={(e) => { setCreating(false); setCreated(e); }} />}
      {created && (
        <SecretReveal title="Endpoint created" label="Signing secret" secret={created.signing_secret ?? "(not returned — rotate the secret to obtain one)"}
          note={<>Verify the <span className="font-mono">Meridian-Signature</span> header with this secret — see the webhooks guide for Java, Node and Python code.</>}
          onDone={() => { const id = created.id; setCreated(null); open(id); }} />
      )}
    </>
  );
}

function CreateEndpoint({ onClose, onCreated }: { onClose: () => void; onCreated: (e: WebhookEndpoint) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<WebhookEndpointCreate>({ url: "", description: "", environment: "SANDBOX", max_requests_per_second: 10, mtls_enabled: false });
  const [headers, setHeaders] = useState("");
  const apps = useQuery({
    queryKey: ["api-clients", "all"],
    queryFn: async () => unwrap(await identity.GET("/v1/api-clients", { params: { query: { limit: 200, status: "ACTIVE" } } })).data,
    retry: false,
  });
  const set = <K extends keyof WebhookEndpointCreate>(k: K, v: WebhookEndpointCreate[K]) => setForm((f) => ({ ...f, [k]: v }));
  const m = useMutation({
    mutationFn: async () => {
      const body: WebhookEndpointCreate = { ...form };
      if (!body.description) delete body.description;
      if (!body.api_client_id) delete body.api_client_id;
      if (headers.trim()) {
        try { body.custom_headers = JSON.parse(headers); } catch { throw new Error("Custom headers must be a JSON object, e.g. {\"X-Team\": \"orders\"}"); }
      }
      return unwrap(await webhooks.POST("/v1/webhook-endpoints", { params: { header: idem() }, body }));
    },
    onSuccess: (e) => { qc.invalidateQueries({ queryKey: ["webhook-endpoints"] }); onCreated(e); },
  });
  return (
    <Modal title="Add webhook endpoint" onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <Field label="Endpoint URL" hint="HTTPS; must answer 2xx within 10 seconds">
          <Input type="url" required maxLength={500} placeholder="https://example.com/webhooks/meridian" value={form.url} onChange={(e) => set("url", e.target.value)} />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Description"><Input maxLength={255} value={form.description ?? ""} onChange={(e) => set("description", e.target.value)} /></Field>
          <Field label="Environment">
            <Select value={form.environment} options={["SANDBOX", "PRODUCTION"]} onChange={(e) => set("environment", e.target.value as "SANDBOX" | "PRODUCTION")} />
          </Field>
          <Field label="App (optional)">
            <select className="rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm" value={form.api_client_id ?? ""}
              onChange={(e) => set("api_client_id", e.target.value || undefined)}>
              <option value="">— none —</option>
              {(apps.data ?? []).map((a) => <option key={a.id} value={a.id}>{a.name} ({a.environment})</option>)}
            </select>
          </Field>
          <Field label="Max requests per second">
            <Input type="number" min={1} max={1000} value={form.max_requests_per_second ?? 10} onChange={(e) => set("max_requests_per_second", Number(e.target.value))} />
          </Field>
        </div>
        <Field label="Custom headers (JSON, optional)">
          <Textarea rows={2} value={headers} onChange={(e) => setHeaders(e.target.value)} placeholder='{"X-Team": "orders"}' />
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={!!form.mtls_enabled} onChange={(e) => set("mtls_enabled", e.target.checked)} /> Present Meridian&apos;s client certificate (mTLS)
        </label>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={m.isPending || !form.url}>{m.isPending ? "Creating…" : "Create endpoint"}</Button>
        </div>
      </form>
    </Modal>
  );
}

export default function Page() {
  return <Suspense><WebhooksPage /></Suspense>;
}
