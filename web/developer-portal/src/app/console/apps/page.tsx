"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { identity, unwrap, type ApiClient, type ApiClientCreate } from "@/api/client";
import { Modal, Pager, SecretReveal, idem, usePaged } from "@/components/console";
import { ScopePicker } from "@/components/scopes";
import { Badge, Button, Card, DateTime, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Select, Table } from "@/components/ui";

const CLIENT_TYPES = ["SERVER", "PUBLIC_APP", "PARTNER", "TPP"] as const;
const AUTH_METHODS = ["CLIENT_SECRET_BASIC", "CLIENT_SECRET_POST", "PRIVATE_KEY_JWT", "TLS_CLIENT_AUTH", "NONE"] as const;

type Env = "SANDBOX" | "PRODUCTION" | "";
type Status = "ACTIVE" | "SUSPENDED" | "REVOKED" | "";

export default function AppsPage() {
  const router = useRouter();
  const [environment, setEnvironment] = useState<Env>("");
  const [status, setStatus] = useState<Status>("");
  const [creating, setCreating] = useState(false);
  const [secret, setSecret] = useState<ApiClient | null>(null);
  const list = usePaged<ApiClient>(["api-clients"], async (cursor) => unwrap(await identity.GET("/v1/api-clients", {
    params: { query: { limit: 25, cursor, environment: environment || undefined, status: status || undefined } },
  })), { environment, status });

  return (
    <>
      <PageHeader title="Apps & API keys"
        subtitle="Each app is an OAuth 2.1 client of the Meridian APIs. Open an app to manage its secret, scopes and API keys."
        actions={<Button onClick={() => setCreating(true)}>New app</Button>} />
      <Card>
        <div className="mb-4 flex flex-wrap gap-3">
          <Select value={environment} onChange={(e) => setEnvironment(e.target.value as Env)} options={["", "SANDBOX", "PRODUCTION"]} aria-label="Environments" />
          <Select value={status} onChange={(e) => setStatus(e.target.value as Status)} options={["", "ACTIVE", "SUSPENDED", "REVOKED"]} aria-label="Statuses" />
        </div>
        <ErrorBanner error={list.error} />
        {list.isLoading ? <Loading /> : (
          <Table rows={list.rows as unknown as Record<string, unknown>[]} onRow={(r) => router.push(`/console/apps/${String(r.id)}`)}
            empty="No apps yet. Create one to get a client id and secret."
            columns={[
              { key: "name", header: "Name", render: (r) => <span className="font-medium">{String(r.name)}</span> },
              { key: "client_id", header: "Client id", render: (r) => <Mono>{String(r.client_id)}</Mono> },
              { key: "environment", header: "Env", render: (r) => <Badge value={String(r.environment)} tone={r.environment === "PRODUCTION" ? "info" : "muted"} /> },
              { key: "client_type", header: "Type" },
              { key: "allowed_scopes", header: "Scopes", render: (r) => <span className="text-xs text-muted">{(r.allowed_scopes as string[] | undefined)?.length ?? 0}</span> },
              { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
              { key: "last_used_at", header: "Last token", render: (r) => <DateTime value={r.last_used_at as string | null} /> },
            ]} />
        )}
        <Pager p={list} />
      </Card>
      {creating && <CreateApp onClose={() => setCreating(false)} onCreated={(c) => { setCreating(false); setSecret(c); }} />}
      {secret && (secret.client_secret ? (
        <SecretReveal title="App created" label="Client secret" secret={secret.client_secret}
          note={<>Client id <span className="font-mono">{secret.client_id}</span>. Exchange both at the token endpoint with <span className="font-mono">grant_type=client_credentials</span>.</>}
          onDone={() => { const id = secret.id; setSecret(null); router.push(`/console/apps/${id}`); }} />
      ) : (
        <Modal title="App created" onClose={() => { const id = secret.id; setSecret(null); router.push(`/console/apps/${id}`); }}>
          <p className="text-sm">Client id <span className="font-mono">{secret.client_id}</span>. This auth method has no client secret.</p>
        </Modal>
      ))}
    </>
  );
}

function CreateApp({ onClose, onCreated }: { onClose: () => void; onCreated: (c: ApiClient) => void }) {
  const qc = useQueryClient();
  const [form, setForm] = useState<ApiClientCreate>({
    name: "", description: "", client_type: "SERVER", auth_method: "CLIENT_SECRET_BASIC", environment: "SANDBOX",
    allowed_scopes: [], fapi_profile: false,
  });
  const [redirects, setRedirects] = useState("");
  const [ips, setIps] = useState("");
  const set = <K extends keyof ApiClientCreate>(k: K, v: ApiClientCreate[K]) => setForm((f) => ({ ...f, [k]: v }));
  const m = useMutation({
    mutationFn: async () => {
      const body: ApiClientCreate = { ...form };
      const lines = (s: string) => s.split(/[\n,]/).map((x) => x.trim()).filter(Boolean);
      if (lines(redirects).length) body.redirect_uris = lines(redirects);
      if (lines(ips).length) body.allowed_ip_ranges = lines(ips);
      for (const k of ["description", "owner_email", "jwks_uri"] as const) if (!body[k]) delete body[k];
      return unwrap(await identity.POST("/v1/api-clients", { params: { header: idem() }, body }));
    },
    onSuccess: (c) => { qc.invalidateQueries({ queryKey: ["api-clients"] }); onCreated(c); },
  });
  const needsRedirects = form.client_type === "PUBLIC_APP" || form.client_type === "TPP";
  return (
    <Modal title="New app" onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name"><Input required maxLength={120} value={form.name} onChange={(e) => set("name", e.target.value)} /></Field>
          <Field label="Environment">
            <Select value={form.environment} options={["SANDBOX", "PRODUCTION"]} onChange={(e) => set("environment", e.target.value as "SANDBOX" | "PRODUCTION")} />
          </Field>
          <Field label="Client type">
            <Select value={form.client_type} options={[...CLIENT_TYPES]} onChange={(e) => set("client_type", e.target.value as ApiClientCreate["client_type"])} />
          </Field>
          <Field label="Token endpoint auth" hint="Secret-based methods return a client secret once">
            <Select value={form.auth_method} options={[...AUTH_METHODS]} onChange={(e) => set("auth_method", e.target.value as ApiClientCreate["auth_method"])} />
          </Field>
          <Field label="Description"><Input maxLength={500} value={form.description ?? ""} onChange={(e) => set("description", e.target.value)} /></Field>
          <Field label="Technical owner e-mail"><Input type="email" value={form.owner_email ?? ""} onChange={(e) => set("owner_email", e.target.value)} /></Field>
          {form.auth_method === "PRIVATE_KEY_JWT" && (
            <Field label="JWKS URI"><Input type="url" required value={form.jwks_uri ?? ""} onChange={(e) => set("jwks_uri", e.target.value)} /></Field>
          )}
          {needsRedirects && (
            <Field label="Redirect URIs" hint="One per line">
              <textarea className="rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm" rows={2} value={redirects} onChange={(e) => setRedirects(e.target.value)} />
            </Field>
          )}
          <Field label="Allowed IP ranges (CIDR)" hint="Optional, one per line">
            <textarea className="rounded-[var(--radius)] border border-border bg-surface px-3 py-2 text-sm" rows={2} value={ips} onChange={(e) => setIps(e.target.value)} />
          </Field>
          {form.client_type === "TPP" && (
            <label className="flex items-center gap-2 self-end text-sm">
              <input type="checkbox" checked={!!form.fapi_profile} onChange={(e) => set("fapi_profile", e.target.checked)} /> Enforce FAPI 2.0
            </label>
          )}
        </div>
        <Field label="Scopes" hint="domain:read for GET operations, domain:write for everything else">
          <ScopePicker value={(form.allowed_scopes as string[]) ?? []} onChange={(v) => set("allowed_scopes", v)} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={m.isPending || !form.name || !(form.allowed_scopes as string[]).length}>
            {m.isPending ? "Creating…" : "Create app"}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
