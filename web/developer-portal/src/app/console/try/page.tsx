"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useMemo, useState } from "react";
import { rawCall, type RawResult } from "@/api/client";
import { CodeBlock, MethodBadge } from "@/components/code";
import { Textarea } from "@/components/console";
import { Button, Card, ErrorBanner, Field, Input, PageHeader, cx } from "@/components/ui";
import { buildIndex, searchIndex } from "@/lib/search";
import { exampleOf, resolve, useDomainSpec, useSearchIndex, type Json, type Parameter, type SearchEntry } from "@/lib/spec";

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"] as const;
type Row = { key: string; value: string; hint?: string; required?: boolean };

function newKey() {
  return crypto.randomUUID();
}

/** Operation finder backed by the client-side search index. */
function OperationFinder({ onPick }: { onPick: (e: SearchEntry) => void }) {
  const [q, setQ] = useState("");
  const idx = useSearchIndex();
  const index = useMemo(() => (idx.data ? buildIndex(idx.data.filter((e) => e.k === "op")) : []), [idx.data]);
  const hits = useMemo(() => searchIndex(index, q, 12), [index, q]);
  return (
    <div className="relative">
      <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Find an operation: e.g. list deposit accounts, payments create, /v1/cards"
        className="w-full" aria-label="Find an operation" />
      {q.trim() && (
        <div className="absolute right-0 left-0 z-20 mt-1 max-h-80 overflow-auto rounded-[var(--radius)] border border-border bg-surface shadow-lg">
          {hits.length === 0 && <p className="px-3 py-2 text-xs text-muted">{idx.isLoading ? "Loading…" : "No matches."}</p>}
          {hits.map((h) => (
            <button key={`${h.d}:${h.id}`} type="button" onClick={() => { onPick(h); setQ(""); }}
              className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-surface-2">
              <MethodBadge method={h.m} />
              <span className="font-mono text-xs">{h.p}</span>
              <span className="truncate text-xs text-muted">{h.s}</span>
              <span className="ml-auto text-[10px] text-muted">{h.sc}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function KeyValues({ rows, onChange, keyLabel }: { rows: Row[]; onChange: (r: Row[]) => void; keyLabel: string }) {
  return (
    <div className="flex flex-col gap-1.5">
      {rows.map((r, i) => (
        <div key={i} className="flex gap-2">
          <Input value={r.key} placeholder={keyLabel} className={cx("w-44 font-mono text-xs", r.required && "border-warn")}
            onChange={(e) => onChange(rows.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
          <Input value={r.value} placeholder={r.hint ?? "value"} className="flex-1 font-mono text-xs"
            onChange={(e) => onChange(rows.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
          <button type="button" className="px-1 text-muted hover:text-bad" aria-label="Remove" onClick={() => onChange(rows.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="self-start text-xs text-accent hover:underline" onClick={() => onChange([...rows, { key: "", value: "" }])}>+ add</button>
    </div>
  );
}

function TryIt() {
  const params = useSearchParams();
  const [method, setMethod] = useState<string>(params.get("method")?.toUpperCase() ?? "GET");
  const [path, setPath] = useState(params.get("path") ?? "/v1/customers");
  const [pathValues, setPathValues] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<Row[]>(params.get("path") ? [] : [{ key: "limit", value: "10" }]);
  const [headers, setHeaders] = useState<Row[]>([]);
  const [body, setBody] = useState(params.get("body") ?? "");
  const [idemOn, setIdemOn] = useState(true);
  const [idemKey, setIdemKey] = useState("");
  const [ifMatch, setIfMatch] = useState("");
  const [picked, setPicked] = useState<{ domain: string; operationId: string } | null>(() => {
    const domain = params.get("domain");
    const operationId = params.get("op");
    return domain && operationId ? { domain, operationId } : null;
  });
  const [result, setResult] = useState<RawResult | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<{ method: string; url: string; status: number; ms: number }[]>([]);
  const spec = useDomainSpec(picked?.domain);

  useEffect(() => { setIdemKey(newKey()); }, []);

  // When an operation is picked from the finder, derive params / body from its contract.
  useEffect(() => {
    if (!spec.data || !picked) return;
    const doc = spec.data as unknown as Json;
    for (const [p, item] of Object.entries(spec.data.paths)) {
      for (const [m, op] of Object.entries(item)) {
        if (!op || typeof op !== "object" || !("operationId" in op) || op.operationId !== picked.operationId) continue;
        const ps = (op.parameters ?? []).map((x: Parameter) => resolve(doc, x));
        setMethod(m.toUpperCase());
        setPath(p);
        setQuery(ps.filter((x) => x.in === "query" && !/^custom_field_\d$/.test(x.name) && !["created_after", "created_before"].includes(x.name))
          .map((x) => ({ key: x.name, value: x.name === "limit" ? "10" : "", required: x.required,
            hint: x.schema?.enum ? x.schema.enum.join(" | ") : x.schema?.format ?? x.schema?.type?.toString() })));
        setHeaders(ps.filter((x) => x.in === "header" && !["Idempotency-Key", "If-Match"].includes(x.name)).map((x) => ({ key: x.name, value: "", required: x.required })));
        const schema = op.requestBody?.content?.["application/json"]?.schema;
        setBody(schema ? JSON.stringify(exampleOf(doc, schema), null, 2) : "");
        setIdemOn(ps.some((x) => x.name === "Idempotency-Key"));
        setIfMatch("");
        return;
      }
    }
  }, [spec.data, picked]);

  const names = useMemo(() => [...path.matchAll(/\{([^}]+)\}/g)].map((m) => m[1]), [path]);
  const needsIfMatch = method === "PATCH";
  const hasBody = method !== "GET" && method !== "DELETE";
  let bodyError: string | null = null;
  if (hasBody && body.trim()) {
    try { JSON.parse(body); } catch (e) { bodyError = (e as Error).message; }
  }

  const url = useMemo(() => {
    const p = path.replace(/\{([^}]+)\}/g, (_, n) => encodeURIComponent(pathValues[n] ?? "") || `{${n}}`);
    const qs = new URLSearchParams();
    for (const r of query) if (r.key && r.value !== "") qs.set(r.key, r.value);
    const s = qs.toString();
    return `${p}${s ? `?${s}` : ""}`;
  }, [path, pathValues, query]);

  const extra = useMemo(() => {
    const h: Record<string, string> = {};
    for (const r of headers) if (r.key && r.value) h[r.key] = r.value;
    if (idemOn && method !== "GET") h["Idempotency-Key"] = idemKey;
    if (needsIfMatch && ifMatch) h["If-Match"] = ifMatch;
    return h;
  }, [headers, idemOn, idemKey, method, needsIfMatch, ifMatch]);

  const valid = url.startsWith("/v1/") && !/\{[^}]+\}/.test(url) && !bodyError;

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const r = await rawCall(method, url, hasBody ? body : undefined, extra);
      setResult(r);
      setHistory((h) => [{ method, url, status: r.status, ms: r.ms }, ...h].slice(0, 10));
      // A successful write consumed the key: prepare a fresh one so the next send is a new request.
      if (method !== "GET" && r.status < 500) setIdemKey(newKey());
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  const pretty = useMemo(() => {
    if (!result?.body) return "";
    try { return JSON.stringify(JSON.parse(result.body), null, 2); } catch { return result.body; }
  }, [result]);

  return (
    <>
      <PageHeader title="Try it (sandbox)"
        subtitle={<>Send real requests through the edge gateway as yourself — signed with your session token, in your tenant. Writes change sandbox data.
          Browse operations in the <Link href="/reference" className="text-accent hover:underline">API reference</Link>.</>} />
      <div className="grid gap-6 xl:grid-cols-2">
        <Card title="Request">
          <div className="flex flex-col gap-4">
            <Field label="Operation"><OperationFinder onPick={(h) => setPicked({ domain: h.d, operationId: h.id })} /></Field>
            {picked && <p className="-mt-2 text-xs text-muted">From contract: <span className="font-mono">{picked.domain} / {picked.operationId}</span>{spec.isLoading && " (loading…)"}</p>}
            <div className="flex gap-2">
              <select value={method} onChange={(e) => setMethod(e.target.value)} aria-label="Method"
                className="rounded-[var(--radius)] border border-border bg-surface px-2 py-2 font-mono text-sm">
                {METHODS.map((m) => <option key={m}>{m}</option>)}
              </select>
              <Input value={path} onChange={(e) => setPath(e.target.value)} className="flex-1 font-mono text-xs" aria-label="Path" />
            </div>
            {names.length > 0 && (
              <Field label="Path parameters">
                <div className="flex flex-col gap-1.5">
                  {names.map((n) => (
                    <div key={n} className="flex items-center gap-2">
                      <span className="w-44 font-mono text-xs">{n}</span>
                      <Input value={pathValues[n] ?? ""} onChange={(e) => setPathValues({ ...pathValues, [n]: e.target.value })} className="flex-1 font-mono text-xs" placeholder="uuid" />
                    </div>
                  ))}
                </div>
              </Field>
            )}
            <Field label="Query parameters"><KeyValues rows={query} onChange={setQuery} keyLabel="name" /></Field>
            <Field label="Headers"><KeyValues rows={headers} onChange={setHeaders} keyLabel="header" /></Field>
            {method !== "GET" && (
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <label className="flex items-center gap-2"><input type="checkbox" checked={idemOn} onChange={(e) => setIdemOn(e.target.checked)} /> Idempotency-Key</label>
                <Input value={idemKey} onChange={(e) => setIdemKey(e.target.value)} disabled={!idemOn} className="flex-1 font-mono text-xs" />
                <Button type="button" variant="ghost" onClick={() => setIdemKey(newKey())}>New key</Button>
              </div>
            )}
            {needsIfMatch && (
              <Field label="If-Match" hint="The resource's current row_version (GET it first)">
                <Input value={ifMatch} onChange={(e) => setIfMatch(e.target.value)} className="font-mono text-xs" />
              </Field>
            )}
            {hasBody && (
              <Field label="JSON body">
                <Textarea rows={12} value={body} onChange={(e) => setBody(e.target.value)} spellCheck={false} />
                {bodyError && <span className="text-xs text-bad">{bodyError}</span>}
              </Field>
            )}
            <div className="flex items-center gap-3">
              <Button onClick={send} disabled={!valid || busy}>{busy ? "Sending…" : "Send request"}</Button>
              <span className="font-mono text-xs break-all text-muted">{method} {url}</span>
            </div>
            {!url.startsWith("/v1/") && <p className="text-xs text-bad">Paths must start with /v1/ (the gateway proxy).</p>}
          </div>
        </Card>
        <div className="flex min-w-0 flex-col gap-6">
          <Card title="Response" actions={result && (
            <span className="flex items-center gap-3 text-sm">
              <span className={cx("font-mono font-semibold", result.status < 300 ? "text-good" : result.status < 500 ? "text-warn" : "text-bad")}>{result.status} {result.statusText}</span>
              <span className="num text-muted">{result.ms.toFixed(0)} ms</span>
              <span className="num text-muted">{new Blob([result.body]).size.toLocaleString()} B</span>
            </span>
          )}>
            <ErrorBanner error={error} />
            {!result ? <p className="text-sm text-muted">Send a request to see the response, headers and timing.</p> : (
              <>
                <CodeBlock className="mt-0" code={pretty || "(empty body)"} title={result.headers.find(([k]) => k === "content-type")?.[1] ?? "body"} />
                <details className="mt-3 text-sm" open>
                  <summary className="cursor-pointer text-xs font-semibold uppercase tracking-wide text-muted">Response headers ({result.headers.length})</summary>
                  <table className="mt-2 w-full text-xs">
                    <tbody>
                      {result.headers.map(([k, v]) => (
                        <tr key={k} className="border-t border-border"><td className="py-1 pr-3 font-mono">{k}</td><td className="py-1 font-mono break-all text-muted">{v}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </details>
                <details className="mt-3 text-sm">
                  <summary className="cursor-pointer text-xs font-semibold uppercase tracking-wide text-muted">Request headers sent</summary>
                  <table className="mt-2 w-full text-xs">
                    <tbody>
                      {Object.entries(result.requestHeaders).map(([k, v]) => (
                        <tr key={k} className="border-t border-border"><td className="py-1 pr-3 font-mono">{k}</td><td className="py-1 font-mono break-all text-muted">{v}</td></tr>
                      ))}
                    </tbody>
                  </table>
                </details>
              </>
            )}
          </Card>
          {history.length > 0 && (
            <Card title="History (this tab)">
              <ul className="text-xs">
                {history.map((h, i) => (
                  <li key={i} className="flex items-center gap-2 border-b border-border py-1 last:border-0">
                    <MethodBadge method={h.method} />
                    <span className="flex-1 truncate font-mono">{h.url}</span>
                    <span className={cx("font-mono", h.status < 300 ? "text-good" : "text-bad")}>{h.status}</span>
                    <span className="num w-14 text-right text-muted">{h.ms.toFixed(0)} ms</span>
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>
      </div>
    </>
  );
}

export default function Page() {
  return <Suspense><TryIt /></Suspense>;
}
