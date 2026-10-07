"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { identity, unwrap, type ApiClient, type ApiKey } from "@/api/client";
import { Action, Details, Modal, Pager, SecretReveal, Tabs, idem, usePaged } from "@/components/console";
import { CodeBlock } from "@/components/code";
import { ScopePicker } from "@/components/scopes";
import { Badge, Button, Card, DateTime, ErrorBanner, Field, Input, Loading, Mono, PageHeader, Table } from "@/components/ui";

type Tab = "overview" | "keys" | "usage";
const TABS = [
  { id: "overview", label: "Overview" },
  { id: "keys", label: "API keys" },
  { id: "usage", label: "Usage" },
] as const;

const SECRET_METHODS = ["CLIENT_SECRET_BASIC", "CLIENT_SECRET_POST"];

function AppDetail() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<Tab>("overview");
  const [revealed, setRevealed] = useState<string | null>(null);
  const app = useQuery({
    queryKey: ["api-client", id],
    queryFn: async () => unwrap(await identity.GET("/v1/api-clients/{id}", { params: { path: { id } } })),
  });
  const c = app.data;
  const inv = [["api-client", id], ["api-clients"]];

  if (app.isLoading) return <Loading />;
  return (
    <>
      <p className="mb-1 text-xs text-muted"><Link href="/console/apps" className="hover:text-text">Apps</Link> / {c?.name}</p>
      <PageHeader title={c?.name ?? "App"}
        subtitle={c && <>{c.client_type} · {c.environment} · <Badge value={c.status} /> · client id <span className="font-mono">{c.client_id}</span></>}
        actions={c && <>
          {SECRET_METHODS.includes(c.auth_method) && c.status !== "REVOKED" && (
            <Action label="Rotate secret" invalidate={inv}
              confirm="A new client secret is generated and returned once. The current secret stops working immediately; update your integration right after."
              run={async () => unwrap(await identity.POST("/v1/api-clients/{id}/rotate-secret", { params: { path: { id }, header: idem() } }))}
              onDone={(r) => setRevealed(r.client_secret ?? null)} />
          )}
          {c.status === "ACTIVE" && (
            <Action label="Suspend" variant="danger" invalidate={inv} reason="Reason"
              confirm="Suspended apps cannot obtain tokens and their API keys are rejected until reactivated."
              run={async (reason) => unwrap(await identity.POST("/v1/api-clients/{id}/suspend", { params: { path: { id }, header: idem() }, body: { reason } }))} />
          )}
          {c.status === "SUSPENDED" && (
            <Action label="Reactivate" invalidate={inv}
              run={async () => unwrap(await identity.POST("/v1/api-clients/{id}/reactivate", { params: { path: { id }, header: idem() } }))} />
          )}
        </>} />
      <ErrorBanner error={app.error} />
      {c && (
        <>
          <Tabs tabs={TABS} value={tab} onChange={setTab} />
          {tab === "overview" && <Overview c={c} />}
          {tab === "keys" && <Keys c={c} />}
          {tab === "usage" && <Usage id={id} />}
        </>
      )}
      {revealed && <SecretReveal title="New client secret" label="Client secret" secret={revealed} onDone={() => setRevealed(null)} />}
    </>
  );
}

function Overview({ c }: { c: ApiClient }) {
  const [editing, setEditing] = useState(false);
  const scopes = (c.allowed_scopes as string[]) ?? [];
  return (
    <div className="flex flex-col gap-6">
      <Card title="Details" actions={c.status !== "REVOKED" && <Button variant="secondary" onClick={() => setEditing(true)}>Edit</Button>}>
        <Details items={[
          ["Client id", <Mono key="cid">{c.client_id}</Mono>],
          ["Auth method", c.auth_method],
          ["Environment", c.environment],
          ["Description", c.description],
          ["Owner", c.owner_email],
          ["Created", <DateTime key="c" value={c.created_at} />],
          ["Last token issued", <DateTime key="l" value={c.last_used_at} />],
          ["Expires", <DateTime key="e" value={c.expires_at} />],
          ["FAPI 2.0", c.fapi_profile ? "enforced" : "no"],
          ["Redirect URIs", (c.redirect_uris as string[] | null)?.join(", ")],
          ["IP allow-list", (c.allowed_ip_ranges as string[] | null)?.join(", ")],
          ["JWKS URI", c.jwks_uri],
        ]} />
      </Card>
      <Card title={`Scopes (${scopes.length})`}>
        <div className="flex flex-wrap gap-1.5">
          {scopes.map((s) => <span key={s} className="rounded bg-accent-soft px-2 py-0.5 font-mono text-xs text-accent">{s}</span>)}
          {!scopes.length && <span className="text-sm text-muted">No scopes.</span>}
        </div>
      </Card>
      <Card title="Get a token">
        <CodeBlock className="mt-0" code={`curl -s -X POST "$TOKEN_URL" \\
  -u "${c.client_id}:$CLIENT_SECRET" \\
  -d grant_type=client_credentials \\
  -d scope="${scopes.join(" ")}"`} />
      </Card>
      {editing && <EditApp c={c} onClose={() => setEditing(false)} />}
    </div>
  );
}

function EditApp({ c, onClose }: { c: ApiClient; onClose: () => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState(c.name);
  const [description, setDescription] = useState(c.description ?? "");
  const [owner, setOwner] = useState(c.owner_email ?? "");
  const [scopes, setScopes] = useState<string[]>((c.allowed_scopes as string[]) ?? []);
  const m = useMutation({
    mutationFn: async () => unwrap(await identity.PATCH("/v1/api-clients/{id}", {
      params: { path: { id: c.id }, header: { "If-Match": String(c.row_version) } },
      body: { name, description, allowed_scopes: scopes, ...(owner ? { owner_email: owner } : {}) },
    })),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["api-client", c.id] }); qc.invalidateQueries({ queryKey: ["api-clients"] }); onClose(); },
  });
  return (
    <Modal title="Edit app" onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Name"><Input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} /></Field>
          <Field label="Technical owner e-mail"><Input type="email" value={owner} onChange={(e) => setOwner(e.target.value)} /></Field>
        </div>
        <Field label="Description"><Input maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} /></Field>
        <Field label="Scopes"><ScopePicker value={scopes} onChange={setScopes} /></Field>
        <p className="text-xs text-muted">Saved with <span className="font-mono">If-Match: {c.row_version}</span>; if someone else changed the app meanwhile you&apos;ll be asked to reload.</p>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={m.isPending || !scopes.length}>{m.isPending ? "Saving…" : "Save"}</Button>
        </div>
      </form>
    </Modal>
  );
}

function Keys({ c }: { c: ApiClient }) {
  const [creating, setCreating] = useState(false);
  const [revealed, setRevealed] = useState<ApiKey | null>(null);
  const list = usePaged<ApiKey>(["api-keys", c.id], async (cursor) => unwrap(await identity.GET("/v1/api-clients/{api_client_id}/keys", {
    params: { path: { api_client_id: c.id }, query: { limit: 25, cursor } },
  })));
  const inv = [["api-keys", c.id]];
  return (
    <Card title="API keys" actions={c.status === "ACTIVE" && <Button onClick={() => setCreating(true)}>New API key</Button>}>
      <p className="mb-4 text-sm text-muted">Long-lived keys for integrations that cannot run an OAuth flow. A key&apos;s scopes are a subset of the app&apos;s. Keys are shown once.</p>
      <ErrorBanner error={list.error} />
      {list.isLoading ? <Loading /> : (
        <Table rows={list.rows as unknown as Record<string, unknown>[]} empty="No API keys."
          columns={[
            { key: "name", header: "Name", render: (r) => <span className="font-medium">{String(r.name)}</span> },
            { key: "key_prefix", header: "Prefix", render: (r) => <Mono>{String(r.key_prefix)}…</Mono> },
            { key: "scopes", header: "Scopes", render: (r) => <span className="font-mono text-xs">{((r.scopes as string[] | null) ?? []).join(" ") || "all of the app's"}</span> },
            { key: "status", header: "Status", render: (r) => <Badge value={String(r.status)} /> },
            { key: "expires_at", header: "Expires", render: (r) => <DateTime value={r.expires_at as string | null} /> },
            { key: "last_used_at", header: "Last used", render: (r) => <DateTime value={r.last_used_at as string | null} /> },
            { key: "actions", header: "", render: (r) => {
              const k = r as unknown as ApiKey;
              if (k.status === "REVOKED" || k.status === "EXPIRED") return null;
              return (
                <span className="flex justify-end gap-2" onClick={(e) => e.stopPropagation()}>
                  <RotateKey k={k} onDone={setRevealed} />
                  <Action label="Revoke" variant="danger" reason="Reason" invalidate={inv}
                    confirm={`Revoke "${k.name}" (${k.key_prefix}…) immediately? Requests using it start failing at once.`}
                    run={async (reason) => unwrap(await identity.POST("/v1/api-clients/{api_client_id}/keys/{id}/revoke", {
                      params: { path: { api_client_id: c.id, id: k.id }, header: idem() }, body: { reason },
                    }))} />
                </span>
              );
            } },
          ]} />
      )}
      <Pager p={list} />
      {creating && <CreateKey c={c} onClose={() => setCreating(false)} onCreated={(k) => { setCreating(false); setRevealed(k); }} />}
      {revealed?.api_key && (
        <SecretReveal title="API key created" label="API key" secret={revealed.api_key}
          note={<>Send it as <span className="font-mono">Authorization: Bearer &lt;key&gt;</span>. Prefix <span className="font-mono">{revealed.key_prefix}</span> identifies it in logs.</>}
          onDone={() => setRevealed(null)} />
      )}
    </Card>
  );
}

function RotateKey({ k, onDone }: { k: ApiKey; onDone: (k: ApiKey) => void }) {
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [hours, setHours] = useState("24");
  const m = useMutation({
    mutationFn: async () => unwrap(await identity.POST("/v1/api-clients/{api_client_id}/keys/{id}/rotate", {
      params: { path: { api_client_id: k.api_client_id, id: k.id }, header: idem() },
      body: { grace_period_hours: Number(hours) },
    })),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ["api-keys", k.api_client_id] }); setOpen(false); onDone(r); },
  });
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}>Rotate</Button>
      {open && (
        <Modal title={`Rotate "${k.name}"`} onClose={() => setOpen(false)}>
          <ErrorBanner error={m.error} />
          <p className="mb-4 text-sm">A replacement key is issued and shown once. The current key keeps working for the grace period so you can roll your deployments.</p>
          <Field label="Grace period (hours)"><Input type="number" min={0} max={720} value={hours} onChange={(e) => setHours(e.target.value)} /></Field>
          <div className="mt-5 flex justify-end gap-2">
            <Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button disabled={m.isPending} onClick={() => m.mutate()}>{m.isPending ? "Rotating…" : "Rotate key"}</Button>
          </div>
        </Modal>
      )}
    </>
  );
}

function CreateKey({ c, onClose, onCreated }: { c: ApiClient; onClose: () => void; onCreated: (k: ApiKey) => void }) {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [scopes, setScopes] = useState<string[]>([]);
  const [expires, setExpires] = useState("");
  const m = useMutation({
    mutationFn: async () => unwrap(await identity.POST("/v1/api-clients/{api_client_id}/keys", {
      params: { path: { api_client_id: c.id }, header: idem() },
      body: {
        name,
        ...(scopes.length ? { scopes } : {}),
        ...(expires ? { expires_at: new Date(`${expires}T23:59:59Z`).toISOString() } : {}),
      },
    })),
    onSuccess: (k) => { qc.invalidateQueries({ queryKey: ["api-keys", c.id] }); onCreated(k); },
  });
  return (
    <Modal title="New API key" onClose={onClose} wide>
      <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <ErrorBanner error={m.error} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Label"><Input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. reconciliation job" /></Field>
          <Field label="Expires on" hint="Optional; recommended"><Input type="date" value={expires} onChange={(e) => setExpires(e.target.value)} /></Field>
        </div>
        <Field label="Scopes" hint="Leave empty to inherit all of the app's scopes">
          <ScopePicker value={scopes} onChange={setScopes} allowed={(c.allowed_scopes as string[]) ?? []} />
        </Field>
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={m.isPending || !name}>{m.isPending ? "Creating…" : "Create key"}</Button>
        </div>
      </form>
    </Modal>
  );
}

function Usage({ id }: { id: string }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const q = useQuery({
    queryKey: ["api-client-usage", id, from, to],
    queryFn: async () => unwrap(await identity.GET("/v1/api-clients/{id}/usage", { params: { path: { id }, query: {
      ...(from ? { from: new Date(from).toISOString() } : {}),
      ...(to ? { to: new Date(`${to}T23:59:59Z`).toISOString() } : {}),
    } } })),
  });
  const data = q.data as Record<string, unknown> | undefined;
  return (
    <Card title="Usage" actions={<>
      <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} aria-label="From" />
      <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} aria-label="To" />
    </>}>
      <p className="mb-4 text-sm text-muted">Request counts, error rates and latency (default: last 30 days).</p>
      <ErrorBanner error={q.error} />
      {q.isLoading ? <Loading /> : data && (
        <>
          <Details items={Object.entries(data).filter(([, v]) => v === null || typeof v !== "object")
            .map(([k, v]) => [k.replaceAll("_", " "), <span key={k} className="num">{v === null ? "—" : String(v)}</span>])} />
          {Object.entries(data).filter(([, v]) => v !== null && typeof v === "object").map(([k, v]) => (
            <CodeBlock key={k} title={k} code={JSON.stringify(v, null, 2)} />
          ))}
        </>
      )}
    </Card>
  );
}

export default function Page() {
  return <Suspense><AppDetail /></Suspense>;
}
