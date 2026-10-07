"use client";

import { useQuery } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { usePermissions } from "@/api/merchant";
import { api, unwrap } from "@/api/client";
import type { ApiClient, WebhookEndpoint } from "@/api/types";
import { useT } from "@/lib/i18n";
import { useRun } from "@/components/step-up";
import {
  Async, Badge, Button, Card, DateTime, Empty, ErrorBanner, Field, Input, Notice, PageHeader, Select, Table, Tabs,
} from "@/components/ui";

/** API clients, keys and webhook endpoints of the merchant (OWNER only). */
export default function DevelopersPage() {
  const t = useT();
  const perms = usePermissions();
  const [tab, setTab] = useState<"clients" | "webhooks">("clients");
  if (perms.loaded && !perms.isOwner) {
    return <Empty title={t("code_role_not_allowed")}>{t("code_role_not_allowed_hint")}</Empty>;
  }
  return (
    <>
      <PageHeader title={t("developers")} />
      <Tabs value={tab} onChange={setTab} tabs={[{ key: "clients", label: t("apiClients") }, { key: "webhooks", label: t("webhooks") }]} />
      {perms.loaded && (tab === "clients" ? <Clients /> : <Webhooks />)}
    </>
  );
}

function useClients() {
  return useQuery({
    queryKey: ["api-clients"],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/api-clients", { params: { query: { limit: 100 } } })).data,
  });
}

function Clients() {
  const t = useT();
  const clients = useClients();
  const create = useRun();
  const [name, setName] = useState("");
  const [env, setEnv] = useState<"SANDBOX" | "PRODUCTION">("SANDBOX");
  const [selected, setSelected] = useState<ApiClient | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    const r = await create.run(async (key) => unwrap(await api.POST("/v1/merchant/api-clients", {
      params: { header: { "Idempotency-Key": key } }, body: { name: name.trim(), environment: env } })));
    if (r.ok) setName("");
  }

  return (
    <div className="flex flex-col gap-4">
      <Card title={t("newClient")}>
        <form onSubmit={submit} className="grid items-end gap-3 sm:grid-cols-3">
          <Field label={t("name")}><Input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label={t("environment")}>
            <Select value={env} onChange={(e) => setEnv(e.target.value as "SANDBOX" | "PRODUCTION")}
              options={[{ value: "SANDBOX", label: "SANDBOX" }, { value: "PRODUCTION", label: "PRODUCTION" }]} />
          </Field>
          <Button type="submit" disabled={create.pending}>{t("newClient")}</Button>
          <div className="sm:col-span-3"><ErrorBanner error={create.error} /></div>
        </form>
      </Card>
      <Async q={clients} empty={<Empty title={t("noClients")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-2">
            {rows.map((c) => (
              <li key={c.id}>
                <button type="button" onClick={() => setSelected(c)}
                  className="flex min-h-12 w-full items-center justify-between rounded-[var(--radius)] border border-border bg-surface px-4 py-2 text-left hover:bg-surface-2">
                  <span><span className="font-medium">{c.name}</span> <span className="text-xs text-muted">{c.environment}</span></span>
                  <Badge value={c.status} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Async>
      {selected && <Keys client={selected} />}
    </div>
  );
}

function Keys({ client }: { client: ApiClient }) {
  const t = useT();
  const keys = useQuery({
    queryKey: ["api-keys", client.id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/api-clients/{client_id}/keys",
      { params: { path: { client_id: client.id }, query: { limit: 100 } } })).data,
  });
  const create = useRun();
  const revoke = useRun();
  const [name, setName] = useState("");
  const [shown, setShown] = useState<string | null>(null);
  return (
    <Card title={`${t("keys")} · ${client.name}`}>
      <form className="mb-3 flex flex-wrap items-end gap-2" onSubmit={async (e) => {
        e.preventDefault();
        const r = await create.run(async (key) => unwrap(await api.POST("/v1/merchant/api-clients/{client_id}/keys", {
          params: { path: { client_id: client.id }, header: { "Idempotency-Key": key } }, body: { name: name.trim() } })));
        if (r.ok) { setShown(r.data.api_key ?? null); setName(""); }
      }}>
        <Field label={t("name")}><Input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} /></Field>
        <Button type="submit" disabled={create.pending}>{t("newKey")}</Button>
      </form>
      {shown && <div className="mb-3"><Notice tone="warn">{t("keyShownOnce")} <code className="break-all font-mono">{shown}</code></Notice></div>}
      <ErrorBanner error={create.error ?? revoke.error} className="mb-3" />
      <Async q={keys}>
        {(rows) => (
          <Table rows={rows} rowKey={(k) => k.id} columns={[
            { header: t("name"), cell: (k) => k.name },
            { header: "Prefix", cell: (k) => <span className="font-mono">{k.key_prefix}</span> },
            { header: t("status"), cell: (k) => <Badge value={k.status} /> },
            { header: "", cell: (k) => k.status === "ACTIVE" && (
              <Button size="sm" variant="danger" onClick={() => { revoke.newKey(); void revoke.run(async (key) =>
                unwrap(await api.POST("/v1/merchant/api-clients/{client_id}/keys/{key_id}/revoke", {
                  params: { path: { client_id: client.id, key_id: k.id }, header: { "Idempotency-Key": key } },
                  body: { reason: "Revoked in the merchant app" } }))); }}>{t("revoke")}</Button>) },
          ]} />
        )}
      </Async>
    </Card>
  );
}

function Webhooks() {
  const t = useT();
  const clients = useClients();
  const endpoints = useQuery({
    queryKey: ["webhooks"],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/webhook-endpoints", { params: { query: { limit: 100 } } })).data,
  });
  const create = useRun();
  const [url, setUrl] = useState("");
  const [client, setClient] = useState("");
  const [selected, setSelected] = useState<WebhookEndpoint | null>(null);
  return (
    <div className="flex flex-col gap-4">
      <Card title={t("newEndpoint")}>
        <form className="grid items-end gap-3 sm:grid-cols-3" onSubmit={async (e) => {
          e.preventDefault();
          const r = await create.run(async (key) => unwrap(await api.POST("/v1/merchant/webhook-endpoints", {
            params: { header: { "Idempotency-Key": key } }, body: { url: url.trim(), api_client_id: client } })));
          if (r.ok) setUrl("");
        }}>
          <Field label={t("url")}><Input type="url" required pattern="https://.*" value={url} onChange={(e) => setUrl(e.target.value)} /></Field>
          <Field label={t("apiClient")}>
            <Select required value={client} onChange={(e) => setClient(e.target.value)} placeholder="—"
              options={(clients.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
          </Field>
          <Button type="submit" disabled={create.pending}>{t("newEndpoint")}</Button>
          <div className="sm:col-span-3"><ErrorBanner error={create.error} /></div>
        </form>
      </Card>
      <Async q={endpoints} empty={<Empty title={t("noEndpoints")} />}>
        {(rows) => (
          <ul className="flex flex-col gap-2">
            {rows.map((w) => (
              <li key={w.id}>
                <button type="button" onClick={() => setSelected(w)}
                  className="flex min-h-12 w-full items-center justify-between gap-3 rounded-[var(--radius)] border border-border bg-surface px-4 py-2 text-left hover:bg-surface-2">
                  <span className="min-w-0 truncate">{w.url}</span>
                  <Badge value={w.status} />
                </button>
              </li>
            ))}
          </ul>
        )}
      </Async>
      {selected && <Endpoint endpoint={selected} />}
    </div>
  );
}

function Endpoint({ endpoint }: { endpoint: WebhookEndpoint }) {
  const t = useT();
  const subs = useQuery({
    queryKey: ["webhook-subs", endpoint.id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/webhook-endpoints/{endpoint_id}/subscriptions",
      { params: { path: { endpoint_id: endpoint.id }, query: { limit: 100 } } })).data,
  });
  const deliveries = useQuery({
    queryKey: ["webhook-deliveries", endpoint.id],
    queryFn: async () => unwrap(await api.GET("/v1/merchant/webhook-endpoints/{endpoint_id}/deliveries",
      { params: { path: { endpoint_id: endpoint.id }, query: { limit: 50 } } })).data,
  });
  const act = useRun();
  const [eventType, setEventType] = useState("wallets.merchant_qr.paid");
  const path = { endpoint_id: endpoint.id };
  return (
    <Card title={endpoint.url}>
      <div className="mb-3 flex flex-wrap gap-2">
        {endpoint.status !== "DISABLED"
          ? <Button size="sm" variant="secondary" onClick={() => { act.newKey(); void act.run(async (key) =>
            unwrap(await api.POST("/v1/merchant/webhook-endpoints/{endpoint_id}/disable",
              { params: { path, header: { "Idempotency-Key": key } }, body: {} }))); }}>{t("disable")}</Button>
          : <Button size="sm" variant="secondary" onClick={() => { act.newKey(); void act.run(async (key) =>
            unwrap(await api.POST("/v1/merchant/webhook-endpoints/{endpoint_id}/enable",
              { params: { path, header: { "Idempotency-Key": key } } }))); }}>{t("enable")}</Button>}
        <Button size="sm" variant="danger" onClick={() => { act.newKey(); void act.run(async (key) => {
          const r = await api.DELETE("/v1/merchant/webhook-endpoints/{endpoint_id}",
            { params: { path, header: { "Idempotency-Key": key } } });
          if (!r.response.ok) unwrap(r);
          return null;
        }); }}>{t("delete")}</Button>
      </div>
      <form className="mb-3 flex flex-wrap items-end gap-2" onSubmit={(e) => {
        e.preventDefault();
        act.newKey();
        void act.run(async (key) => unwrap(await api.POST("/v1/merchant/webhook-endpoints/{endpoint_id}/subscriptions",
          { params: { path, header: { "Idempotency-Key": key } }, body: { event_type: eventType.trim() } })));
      }}>
        <Field label={t("eventType")}><Input required value={eventType} onChange={(e) => setEventType(e.target.value)} /></Field>
        <Button type="submit" disabled={act.pending}>{t("subscribe")}</Button>
      </form>
      <ErrorBanner error={act.error} className="mb-3" />
      <h3 className="mb-1 text-sm font-semibold">{t("subscriptions")}</h3>
      <Async q={subs}>
        {(rows) => <ul className="mb-4 text-sm">{rows.map((s) => <li key={s.id} className="font-mono">{s.event_type}</li>)}</ul>}
      </Async>
      <h3 className="mb-1 text-sm font-semibold">{t("deliveries")}</h3>
      <Async q={deliveries}>
        {(rows) => (
          <Table rows={rows} rowKey={(d) => d.id} columns={[
            { header: t("eventType"), cell: (d) => d.event_type },
            { header: t("status"), cell: (d) => <Badge value={d.status} /> },
            { header: t("when"), cell: (d) => <DateTime value={d.created_at} /> },
          ]} />
        )}
      </Async>
    </Card>
  );
}
