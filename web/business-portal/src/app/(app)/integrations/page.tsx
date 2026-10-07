"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { usePermissions } from "@/api/business";
import { api, idem, unwrap } from "@/api/client";
import type { ApiClient, ApiKey, WebhookEndpoint } from "@/api/types";
import { ActionButton, SecretOnce, isoInstant, opt } from "@/components/actions";
import { AdminOnly } from "@/components/admin-only";
import { Async, Badge, Button, Card, DateTime, Empty, PageHeader, Table, Tabs } from "@/components/ui";

function Keys({ client, onSecret }: { client: ApiClient; onSecret: (s: { label: string; value?: string | null }) => void }) {
  const q = useQuery({
    queryKey: ["api-keys", client.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/api-clients/{client_id}/keys",
      { params: { path: { client_id: client.id }, query: { limit: 100 } } })).data,
  });
  return (
    <div className="space-y-3">
      {client.status === "ACTIVE" && (
        <ActionButton<ApiKey> label="Create key" confirmLabel="Create key"
          fields={[{ name: "name", label: "Name", required: true }, { name: "expires_at", label: "Expires at", type: "datetime-local" }]}
          run={async (v, key) => unwrap(await api.POST("/v1/business/api-clients/{client_id}/keys", {
            params: { path: { client_id: client.id }, header: idem(key) }, body: { name: v.name, expires_at: isoInstant(v.expires_at) } }))}
          onDone={(k) => onSecret({ label: `API key ${k.name}`, value: k.api_key })} />
      )}
      <Async q={q} empty={<Empty title="No keys" />}>
        {(rows) => (
          <Table rows={rows} rowKey={(k) => k.id} caption="API keys" columns={[
            { header: "Name", cell: (k) => k.name },
            { header: "Prefix", cell: (k) => <span className="font-mono text-xs">{k.key_prefix}…</span> },
            { header: "Expires", cell: (k) => <DateTime value={k.expires_at} /> },
            { header: "Last used", cell: (k) => <DateTime value={k.last_used_at} /> },
            { header: "Status", cell: (k) => <Badge value={k.status} /> },
            { header: "", align: "right", cell: (k) => k.status === "ACTIVE" && (
              <ActionButton label="Revoke" variant="danger" title={`Revoke key ${k.name}?`} confirmLabel="Revoke"
                fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
                run={async (v, key) => unwrap(await api.POST("/v1/business/api-clients/{client_id}/keys/{key_id}/revoke", {
                  params: { path: { client_id: client.id, key_id: k.id }, header: idem(key) }, body: { reason: v.reason } }))} />
            ) },
          ]} />
        )}
      </Async>
    </div>
  );
}

function Subscriptions({ endpoint }: { endpoint: WebhookEndpoint }) {
  const subs = useQuery({
    queryKey: ["webhook-subs", endpoint.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/webhook-endpoints/{endpoint_id}/subscriptions",
      { params: { path: { endpoint_id: endpoint.id }, query: { limit: 100 } } })).data,
  });
  const deliveries = useQuery({
    queryKey: ["webhook-deliveries", endpoint.id],
    queryFn: async () => unwrap(await api.GET("/v1/business/webhook-endpoints/{endpoint_id}/deliveries",
      { params: { path: { endpoint_id: endpoint.id }, query: { limit: 50 } } })).data,
  });
  return (
    <div className="grid gap-5 xl:grid-cols-2">
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h3 className="font-semibold">Subscriptions</h3>
          <ActionButton label="Subscribe" confirmLabel="Subscribe"
            description="Only events of your business are delivered (the filter customer_id == your business is always added)."
            fields={[{ name: "event_type", label: "Event type", required: true, placeholder: "payments.payment.settled" }]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/webhook-endpoints/{endpoint_id}/subscriptions", {
              params: { path: { endpoint_id: endpoint.id }, header: idem(key) }, body: { event_type: v.event_type } }))} />
        </div>
        <Async q={subs} empty={<Empty title="No subscriptions" />}>
          {(rows) => (
            <Table rows={rows} rowKey={(s) => s.id} caption="Subscriptions" columns={[
              { header: "Event type", cell: (s) => <span className="font-mono text-xs">{s.event_type}</span> },
              { header: "Filter", cell: (s) => <span className="font-mono text-xs">{s.filter_expression ?? "—"}</span> },
              { header: "Active", cell: (s) => <Badge value={s.is_active ? "ACTIVE" : "INACTIVE"} /> },
            ]} />
          )}
        </Async>
      </div>
      <div className="space-y-3">
        <h3 className="font-semibold">Recent deliveries</h3>
        <Async q={deliveries} empty={<Empty title="No deliveries yet" />}>
          {(rows) => (
            <Table rows={rows} rowKey={(d) => d.id} caption="Deliveries" columns={[
              { header: "When", cell: (d) => <DateTime value={d.created_at} /> },
              { header: "Event", cell: (d) => <span className="font-mono text-xs">{d.event_type}</span> },
              { header: "Attempts", cell: (d) => d.attempt_count },
              { header: "HTTP", cell: (d) => d.last_status_code ?? "—" },
              { header: "Status", cell: (d) => <Badge value={d.status} /> },
            ]} />
          )}
        </Async>
      </div>
    </div>
  );
}

export default function IntegrationsPage() {
  const perms = usePermissions();
  const [tab, setTab] = useState<"clients" | "webhooks">("clients");
  const [openClient, setOpenClient] = useState<ApiClient | null>(null);
  const [openEndpoint, setOpenEndpoint] = useState<WebhookEndpoint | null>(null);
  const [secret, setSecret] = useState<{ label: string; value?: string | null } | null>(null);
  const clients = useQuery({
    queryKey: ["api-clients"], enabled: perms.isAdmin,
    queryFn: async () => unwrap(await api.GET("/v1/business/api-clients", { params: { query: { limit: 100 } } })).data,
  });
  const endpoints = useQuery({
    queryKey: ["webhook-endpoints"], enabled: perms.isAdmin && tab === "webhooks",
    queryFn: async () => unwrap(await api.GET("/v1/business/webhook-endpoints", { params: { query: { limit: 100 } } })).data,
  });
  const clientOptions = (clients.data ?? []).filter((c) => c.status === "ACTIVE").map((c) => ({ value: c.id, label: `${c.name} (${c.environment})` }));
  const envOptions = [{ value: "SANDBOX", label: "Sandbox" }, { value: "PRODUCTION", label: "Production" }];

  return (
    <AdminOnly>
      <PageHeader title="Integrations" subtitle="API clients, keys and webhooks for your own systems (ERP, accounting, payroll)"
        actions={tab === "clients" ? (
          <ActionButton<ApiClient> label="Create API client" variant="primary" size="md" confirmLabel="Create"
            fields={[
              { name: "name", label: "Name", required: true },
              { name: "description", label: "Description" },
              { name: "environment", label: "Environment", type: "select", required: true, options: envOptions },
              { name: "owner_email", label: "Technical contact email", type: "email" },
              { name: "technical_role", label: "Machine access (technical user role)", type: "select", required: true,
                options: [{ value: "VIEWER", label: "Read only (VIEWER)" }, { value: "MAKER", label: "Draft and submit (MAKER)" }],
                hint: "The client acts as a technical user with this role; grant it account entitlements under Users. It never approves." },
              { name: "auth_method", label: "Authentication", type: "select", required: true,
                options: [{ value: "CLIENT_SECRET_BASIC", label: "Client secret" }, { value: "PRIVATE_KEY_JWT", label: "Private key JWT (JWKS)" }] },
              { name: "jwks_uri", label: "JWKS URL (private key JWT only)", type: "url", placeholder: "https://erp.example.com/.well-known/jwks.json" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/api-clients", {
              params: { header: idem(key) },
              body: { name: v.name, description: opt(v.description), environment: v.environment as ApiClient["environment"], owner_email: opt(v.owner_email),
                technical_role: v.technical_role as "VIEWER" | "MAKER", auth_method: v.auth_method, jwks_uri: opt(v.jwks_uri) } }))}
            onDone={(c) => c.client_secret && setSecret({ label: `Client secret of ${c.name} (client_id ${c.client_id})`, value: c.client_secret })} />
        ) : (
          <ActionButton<WebhookEndpoint> label="Add endpoint" variant="primary" size="md" confirmLabel="Add endpoint"
            fields={[
              { name: "url", label: "HTTPS URL", type: "url", required: true, placeholder: "https://erp.example.com/meridian/webhooks" },
              { name: "api_client_id", label: "API client", type: "select", required: true, options: clientOptions },
              { name: "environment", label: "Environment", type: "select", required: true, options: envOptions },
              { name: "description", label: "Description" },
            ]}
            run={async (v, key) => unwrap(await api.POST("/v1/business/webhook-endpoints", {
              params: { header: idem(key) },
              body: { url: v.url, api_client_id: v.api_client_id, environment: v.environment as WebhookEndpoint["environment"], description: opt(v.description) } }))}
            onDone={(w) => w.signing_secret && setSecret({ label: "Webhook signing secret", value: w.signing_secret })} />
        )} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "clients", label: "API clients & keys" }, { key: "webhooks", label: "Webhooks" }]} />

      {tab === "clients" ? (
        <>
          <Card flush>
            <Async q={clients} empty={<div className="p-4"><Empty title="No API clients yet" /></div>}>
              {(rows) => (
                <Table rows={rows} rowKey={(c) => c.id} caption="API clients" columns={[
                  { header: "Name", cell: (c) => <>{c.name}<span className="block text-xs text-muted">{c.description}</span></> },
                  { header: "client_id", cell: (c) => <span className="font-mono text-xs">{c.client_id}</span> },
                  { header: "Environment", cell: (c) => c.environment },
                  { header: "Scopes", cell: (c) => <span className="text-xs">{(c.allowed_scopes as string[]).join(" ")}</span> },
                  { header: "Technical user", cell: (c) => <span className="font-mono text-xs">{c.business_user_id ? c.business_user_id.slice(-8) : "—"}</span> },
                  { header: "Last used", cell: (c) => <DateTime value={c.last_used_at} /> },
                  { header: "Status", cell: (c) => <Badge value={c.status} /> },
                  { header: "", align: "right", cell: (c) => (
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="secondary" onClick={() => setOpenClient(c)}>Keys</Button>
                      {c.status === "ACTIVE" && (
                        <ActionButton label="Suspend" variant="danger" title={`Suspend ${c.name}?`} confirmLabel="Suspend"
                          fields={[{ name: "reason", label: "Reason", type: "textarea", required: true }]}
                          run={async (v, key) => unwrap(await api.POST("/v1/business/api-clients/{client_id}/suspend", {
                            params: { path: { client_id: c.id }, header: idem(key) }, body: { reason: v.reason } }))} />
                      )}
                    </div>
                  ) },
                ]} />
              )}
            </Async>
          </Card>
          {openClient && (
            <Card className="mt-5" title={`Keys · ${openClient.name}`} actions={<Button size="sm" variant="ghost" onClick={() => setOpenClient(null)}>Close</Button>}>
              <Keys client={openClient} onSecret={setSecret} />
            </Card>
          )}
        </>
      ) : (
        <>
          <Card flush>
            <Async q={endpoints} empty={<div className="p-4"><Empty title="No webhook endpoints yet" /></div>}>
              {(rows) => (
                <Table rows={rows} rowKey={(w) => w.id} caption="Webhook endpoints" columns={[
                  { header: "URL", cell: (w) => <span className="break-all font-mono text-xs">{w.url}</span> },
                  { header: "Environment", cell: (w) => w.environment },
                  { header: "Failures", cell: (w) => w.consecutive_failures },
                  { header: "Last success", cell: (w) => <DateTime value={w.last_success_at} /> },
                  { header: "Status", cell: (w) => <Badge value={w.status} /> },
                  { header: "", align: "right", cell: (w) => (
                    <div className="flex justify-end gap-2">
                      <Button size="sm" variant="secondary" onClick={() => setOpenEndpoint(w)}>Subscriptions</Button>
                      {w.status === "DISABLED" ? (
                        <ActionButton label="Enable" confirmLabel="Enable"
                          run={async (_v, key) => unwrap(await api.POST("/v1/business/webhook-endpoints/{endpoint_id}/enable", {
                            params: { path: { endpoint_id: w.id }, header: idem(key) } }))} />
                      ) : (
                        <ActionButton label="Disable" confirmLabel="Disable" fields={[{ name: "reason", label: "Reason", type: "textarea" }]}
                          run={async (v, key) => unwrap(await api.POST("/v1/business/webhook-endpoints/{endpoint_id}/disable", {
                            params: { path: { endpoint_id: w.id }, header: idem(key) }, body: { reason: opt(v.reason) } }))} />
                      )}
                      <ActionButton label="Delete" variant="danger" title="Delete this endpoint?" confirmLabel="Delete"
                        run={async (_v, key) => unwrap(await api.DELETE("/v1/business/webhook-endpoints/{endpoint_id}", {
                          params: { path: { endpoint_id: w.id }, header: idem(key) } }))} />
                    </div>
                  ) },
                ]} />
              )}
            </Async>
          </Card>
          {openEndpoint && (
            <Card className="mt-5" title={<span className="break-all">{openEndpoint.url}</span>}
              actions={<Button size="sm" variant="ghost" onClick={() => setOpenEndpoint(null)}>Close</Button>}>
              <Subscriptions endpoint={openEndpoint} />
            </Card>
          )}
        </>
      )}
      <SecretOnce label={secret?.label ?? ""} value={secret?.value} onClose={() => setSecret(null)} />
    </AdminOnly>
  );
}
