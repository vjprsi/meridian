#!/usr/bin/env node
/**
 * Copies the generated contracts (generated/openapi, generated/asyncapi, generated/services.json) into
 * public/specs so the portal serves them as static files, and derives what the browser needs:
 *
 *   public/specs/openapi/<domain>.openapi.yaml   verbatim copy (download link)
 *   public/specs/openapi/<domain>.json           parsed contract (aliases resolved) for the reference viewer
 *   public/specs/asyncapi/meridian.asyncapi.yaml verbatim copy
 *   public/specs/asyncapi/events.json            channels -> messages with resolved payload schemas
 *   public/specs/catalog.json                    domains, services, scopes and counts (landing, scope picker)
 *   public/specs/search-index.json               flat list of operations + events for client-side search
 *
 * Runs automatically before `dev` and `build` (predev / prebuild) and on demand with `npm run sync:specs`.
 * The specs are never edited here: change spec/domains/*.yaml and run tools/generate.py instead.
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";

const here = dirname(fileURLToPath(import.meta.url));
const app = resolve(here, "..");
const generated = resolve(process.env.MERIDIAN_GENERATED_DIR ?? join(app, "..", "..", "generated"));
const out = join(app, "public", "specs");

if (!existsSync(join(generated, "openapi"))) {
  console.error(`sync-specs: ${generated}/openapi not found (set MERIDIAN_GENERATED_DIR)`);
  process.exit(1);
}

rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, "openapi"), { recursive: true });
mkdirSync(join(out, "asyncapi"), { recursive: true });

const services = JSON.parse(readFileSync(join(generated, "services.json"), "utf8"));
const METHODS = ["get", "post", "put", "patch", "delete"];

/** Drops noise the viewer never shows (Java annotations generated for the services). */
function trimOperation(op) {
  delete op["x-operation-extra-annotation"];
  return op;
}

const load = (file) => yaml.load(readFileSync(file, "utf8"), { json: true });
const domainFiles = readdirSync(join(generated, "openapi"))
  .filter((f) => f.endsWith(".openapi.yaml") && f !== "meridian.openapi.yaml")
  .sort();

const catalog = { generated_at: new Date().toISOString(), domains: [], scopes: [], events: { channels: 0, messages: 0 } };
const search = [];

for (const file of domainFiles) {
  const id = file.replace(".openapi.yaml", "");
  const spec = load(join(generated, "openapi", file));
  copyFileSync(join(generated, "openapi", file), join(out, "openapi", file));

  let ops = 0;
  const scopes = new Set();
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    for (const m of METHODS) {
      const op = item[m];
      if (!op) continue;
      trimOperation(op);
      ops++;
      if (op["x-required-scope"]) scopes.add(op["x-required-scope"]);
      search.push({
        k: "op", d: id, id: op.operationId, m: m.toUpperCase(), p: path, s: op.summary ?? "",
        t: op.tags?.[0] ?? "", sc: op["x-required-scope"] ?? "", sv: op["x-service"] ?? "",
      });
    }
  }
  writeFileSync(join(out, "openapi", `${id}.json`), JSON.stringify(spec));

  const svc = services.filter((s) => s.domain === id);
  const oauthScopes = spec.components?.securitySchemes?.oauth2?.flows?.clientCredentials?.scopes ?? {};
  for (const [scope, description] of Object.entries(oauthScopes)) catalog.scopes.push({ scope, domain: id, description });
  catalog.domains.push({
    id,
    title: svc[0]?.domain_title ?? spec.info?.title?.replace(/^Meridian Core — /, "") ?? id,
    description: (spec.info?.description ?? "").trim(),
    version: spec.info?.version ?? "1.0.0",
    operations: ops,
    tags: (spec.tags ?? []).length,
    scopes: [...scopes].sort(),
    services: svc.map((s) => ({ name: s.name, description: s.description, phase: s.phase })),
  });
}

// ---- AsyncAPI: flatten channels/messages and inline payload $refs one level (the viewer resolves the rest). ----
const asyncFile = join(generated, "asyncapi", "meridian.asyncapi.yaml");
const asyncSpec = load(asyncFile);
copyFileSync(asyncFile, join(out, "asyncapi", "meridian.asyncapi.yaml"));
const messages = asyncSpec.components?.messages ?? {};
const channels = [];
for (const [cid, ch] of Object.entries(asyncSpec.channels ?? {})) {
  const list = [];
  for (const [key, ref] of Object.entries(ch.messages ?? {})) {
    const name = String(ref.$ref ?? "").split("/").pop() || key;
    const msg = messages[name] ?? ref;
    list.push({ key: name, name: msg.name, title: msg.title, summary: msg.summary, headers: msg.headers, payload: msg.payload });
    search.push({ k: "ev", d: cid, id: msg.name, m: "EVENT", p: ch.address, s: msg.title ?? msg.summary ?? "", t: cid, sc: "", sv: "" });
  }
  channels.push({ id: cid, address: ch.address, description: ch.description, messages: list });
}
writeFileSync(join(out, "asyncapi", "events.json"), JSON.stringify({
  info: asyncSpec.info, defaultContentType: asyncSpec.defaultContentType, servers: asyncSpec.servers,
  channels, schemas: asyncSpec.components?.schemas ?? {},
}));
catalog.events = { channels: channels.length, messages: channels.reduce((n, c) => n + c.messages.length, 0) };

writeFileSync(join(out, "catalog.json"), JSON.stringify(catalog, null, 1));
writeFileSync(join(out, "search-index.json"), JSON.stringify(search));
console.log(`sync-specs: ${catalog.domains.length} domains, ${search.filter((s) => s.k === "op").length} operations, `
  + `${catalog.events.messages} events -> public/specs`);
